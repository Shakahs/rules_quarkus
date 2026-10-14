package com.clementguillot.quarkifier.dev;

import com.clementguillot.quarkifier.AugmentationException;
import com.clementguillot.quarkifier.BuildProperties;
import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.devhook.GenerationSwitch;
import com.clementguillot.quarkifier.maven.MavenCoordinateParser;
import com.clementguillot.quarkifier.model.ExplicitApplicationModelBuilder;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel;
import com.clementguillot.quarkifier.watcher.BazelFileWatcher;
import com.clementguillot.quarkifier.watcher.ProjectFileMirror;
import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.dev.DevModeContext;
import io.quarkus.deployment.dev.DevModeMain;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.PathList;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jboss.logging.Logger;

/**
 * Launches Quarkus in dev mode with full Dev UI support via {@code IsolatedDevModeMain}.
 *
 * <p>Creates a minimal "dev jar" with a serialized {@link DevModeContext} and a manifest classpath
 * pointing to core deployment infrastructure jars + parent-first runtime artifacts. A separate JVM
 * process runs {@link DevModeMain#main} which bootstraps {@code IsolatedDevModeMain} inside a clean
 * augment classloader.
 *
 * <p>The boundary between Bazel and dev mode is the application model. Dev mode runs what the model
 * names, and every workspace module from a {@link DevSession}: the session links each successful
 * build of the modules' Bazel directories into a generation of its own, and the application reads
 * them through the session's {@code current} link, which {@link GenerationSwitch} moves inside
 * Quarkus's scan. Bazel deletes an action's outputs before running it again, so the application
 * never reads Bazel's directories themselves. Dev mode never writes into Bazel's outputs and never
 * compiles; a source edit rebuilds through Bazel ({@code BazelFileWatcher}).
 *
 * @see <a href="../../../../../../docs/dev-mode.md">docs/dev-mode.md</a> for the full architecture
 */
@SuppressWarnings("PMD.ExceptionAsFlowControl")
public final class DevModeLauncher {

  private static final Logger LOGGER = Logger.getLogger(DevModeLauncher.class);

  private static final Path SRC_MAIN = Path.of("src", "main");

  private static final String CLASS_CHANGE_AGENT_GROUP_ID = "io.quarkus";
  private static final String CLASS_CHANGE_AGENT_ARTIFACT_ID = "quarkus-class-change-agent";

  private DevModeLauncher() {}

  /** The dev session's directory below the output directory. */
  private static final String SESSION_DIRECTORY = "dev-session";

  /**
   * Launches Quarkus dev mode in a separate JVM process.
   *
   * @param config CLI configuration including source dirs, classpath, output dir
   * @param explicitModel the validated dev application model
   * @throws AugmentationException if dev mode fails to start
   */
  public static void launch(QuarkifierConfig config, BazelApplicationModel explicitModel)
      throws AugmentationException {
    try {
      DevSession session =
          DevSession.start(
              sessionRoot(config), DevSession.modules(explicitModel, config.artifactPaths()));
      ApplicationModel appModel =
          ExplicitApplicationModelBuilder.build(
              explicitModel,
              config.artifactPaths(),
              new ExplicitApplicationModelBuilder.DevSessionPaths(
                  session.classPaths(), session.writeHookJar()));
      DevModeContext context = buildDevModeContext(config, appModel);

      // Ensure the target directory exists — Quarkus writes build-metrics.json there.
      // In a Bazel workspace this directory doesn't exist by default (unlike Maven's target/).
      Files.createDirectories(Path.of(context.getApplicationRoot().getTargetDir()));

      // AppModelSerializerImpl is version-specific; each supported minor writes the format its
      // ApplicationModelSerializer reads back in the dev JVM.
      AppModelSerializerStrategy serializer = new AppModelSerializerImpl();
      Path serializedModel = serializer.serialize(appModel);

      // Dev jar mirrors Maven's DevMojo / DevModeCommandLineBuilder.
      Path devJar = createDevJar(context, config, appModel);
      // Mirror the project files before Quarkus performs its initial scan. Starting the child
      // first creates a race where the application can boot with the previous session's files.
      ProjectFileMirror projectFiles = new ProjectFileMirror(config.projectFiles());
      if (!projectFiles.isEmpty()) {
        projectFiles.mirror();
      }
      BazelFileWatcher watcher = startWatcherIfConfigured(config, projectFiles, session);
      Process process = null;
      try {
        process = startDevProcess(config, serializedModel, devJar, session.root());

        final Process devProcess = process;
        Runtime.getRuntime()
            .addShutdownHook(
                new Thread(
                    () -> {
                      devProcess.destroyForcibly();
                      if (watcher != null) {
                        watcher.close();
                      }
                    }));
        int exitCode = process.waitFor();

        if (exitCode != 0) {
          throw new AugmentationException("Dev mode process exited with code " + exitCode);
        }
      } finally {
        if (process != null && process.isAlive()) {
          process.destroyForcibly();
        }
        if (watcher != null) {
          watcher.close();
        }
      }

    } catch (AugmentationException e) {
      throw e;
    } catch (Exception e) {
      throw new AugmentationException("Failed to launch dev mode: " + e.getMessage(), e);
    }
  }

  /**
   * Merges the declared build configuration with the dev-lifecycle invariants.
   *
   * <p>Dev mode carries this same set over two channels — the child JVM's {@code -D} flags and the
   * serialized {@link DevModeContext} build-system properties — so both must derive it here.
   */
  private static java.util.Properties devBuildProperties(QuarkifierConfig config) {
    return BuildProperties.defaults(
        config.buildProperties(), config.mainClass(), null, config.packageType());
  }

  /**
   * The session directory: inside the output directory, which the launcher creates per run and
   * removes on exit. Generations hard-link Bazel's outputs, so it belongs on the file system of
   * Bazel's output base; elsewhere they are copied ({@link DevSession}).
   */
  private static Path sessionRoot(QuarkifierConfig config) throws java.io.IOException {
    return config.outputDir() != null
        ? config.outputDir().toAbsolutePath().resolve(SESSION_DIRECTORY)
        : Files.createTempDirectory("quarkus-dev-session");
  }

  /** Starts the child JVM running {@link DevModeMain} from the dev jar. */
  private static Process startDevProcess(
      QuarkifierConfig config, Path serializedModel, Path devJar, Path sessionRoot)
      throws Exception {
    ProcessBuilder pb =
        new ProcessBuilder(devProcessCommand(config, serializedModel, devJar, sessionRoot));
    if (config.workspaceDir() != null) {
      pb.directory(config.workspaceDir().toFile());
    }
    pb.inheritIO();
    return pb.start();
  }

  /**
   * The child JVM's command line.
   *
   * <p>Quarkus's class-change agent, which is part of the core deployment closure, is loaded as a
   * Java agent rather than put on the classpath, as Maven's {@code DevMojo} and Gradle's {@code
   * QuarkusDev} load it. Its premain hands the JVM's {@link java.lang.instrument.Instrumentation}
   * to {@code ClassChangeAgent}, and {@code RuntimeUpdatesProcessor} attempts an
   * instrumentation-based reload (redefining changed classes in place of restarting the
   * application, when only method bodies changed) only when that is present and {@code
   * quarkus.live-reload.instrumentation} is enabled.
   *
   * <p>The session directory reaches {@link GenerationSwitch} as a system property.
   */
  // Visible for testing
  static List<String> devProcessCommand(
      QuarkifierConfig config, Path serializedModel, Path devJar, Path sessionRoot) {
    List<String> cmd = new ArrayList<>();
    cmd.add(System.getProperty("java.home") + "/bin/java");
    // Declared build properties come first so every launcher-owned setting
    // below wins any key conflict: the JVM keeps the last `-D` for a name.
    // This matches the ordering the test launcher pins.
    var buildProperties = devBuildProperties(config);
    buildProperties.stringPropertyNames().stream()
        .sorted()
        .map(name -> "-D" + name + "=" + buildProperties.getProperty(name))
        .forEach(cmd::add);
    cmd.add("-Djava.util.logging.manager=org.jboss.logmanager.LogManager");
    cmd.add("-D" + GenerationSwitch.SESSION_PROPERTY + "=" + sessionRoot.toAbsolutePath());
    // Required for jboss-threads on Java 24+
    cmd.add("--add-opens");
    cmd.add("java.base/java.lang=ALL-UNNAMED");
    // Quarkus's own dev-mode command line opens this package as well.
    cmd.add("--add-opens");
    cmd.add("java.base/java.lang.invoke=ALL-UNNAMED");
    classChangeAgent(config).ifPresent(agent -> cmd.add("-javaagent:" + agent.toAbsolutePath()));
    cmd.add(
        "-D" + BootstrapConstants.SERIALIZED_APP_MODEL + "=" + serializedModel.toAbsolutePath());
    cmd.add("-jar");
    cmd.add(devJar.toAbsolutePath().toString());
    return cmd;
  }

  /** The class-change agent jar among the core deployment jars, if the closure has one. */
  private static Optional<Path> classChangeAgent(QuarkifierConfig config) {
    return config.coreDeploymentClasspath().stream()
        .filter(jar -> isClassChangeAgent(MavenCoordinateParser.parse(jar)))
        .findFirst();
  }

  private static boolean isClassChangeAgent(MavenCoordinateParser.Coordinates coords) {
    return CLASS_CHANGE_AGENT_GROUP_ID.equals(coords.groupId())
        && CLASS_CHANGE_AGENT_ARTIFACT_ID.equals(coords.artifactId());
  }

  /** Starts the hot-reload file watcher when there are targets to rebuild and inputs to watch. */
  private static BazelFileWatcher startWatcherIfConfigured(
      QuarkifierConfig config, ProjectFileMirror projectFiles, DevSession session)
      throws Exception {
    if (config.bazelTargets().isEmpty()
        || (config.sourceDirs().isEmpty()
            && config.watchDirs().isEmpty()
            && config.resources().isEmpty()
            && config.codegenInputDirs().isEmpty())) {
      return null;
    }
    LOGGER.debug("[hot-reload] Starting file watcher...");
    return BazelFileWatcher.startInBackground(config, projectFiles, session);
  }

  /**
   * Creates a minimal JAR with a serialized {@link DevModeContext} and a manifest {@code
   * Class-Path} containing core deployment infrastructure jars and parent-first runtime artifacts.
   */
  private static Path createDevJar(
      DevModeContext context, QuarkifierConfig config, ApplicationModel appModel) throws Exception {
    Path tempFile = Files.createTempFile("quarkus-dev", ".jar");
    tempFile.toFile().deleteOnExit();

    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, DevModeMain.class.getName());
    manifest
        .getMainAttributes()
        .put(Attributes.Name.CLASS_PATH, buildManifestClassPath(config, appModel));

    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tempFile))) {
      out.putNextEntry(new ZipEntry("META-INF/"));
      out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
      manifest.write(out);

      out.putNextEntry(new ZipEntry(DevModeMain.DEV_MODE_CONTEXT));
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ObjectOutputStream obj = new ObjectOutputStream(bytes)) {
        obj.writeObject(context);
      }
      out.write(bytes.toByteArray());
    }

    return tempFile;
  }

  /**
   * Builds the dev jar's {@code Class-Path}: dev process infrastructure jars (deduplicated by
   * artifactId) followed by parent-first runtime artifacts not already covered by that set.
   *
   * <p>For jars that also exist on the application classpath, the application classpath version is
   * preferred — the ApplicationModel references that jar file, and the system classloader must use
   * the same file to avoid dual-classloader class identity conflicts.
   *
   * <p>The class-change agent is left out: the JVM loads it as a Java agent (see {@link
   * #devProcessCommand}), which already places it on the system class path.
   */
  // Visible for testing
  static String buildManifestClassPath(QuarkifierConfig config, ApplicationModel appModel) {
    Map<String, Path> appCpByArtifactId = new LinkedHashMap<>();
    for (Path jar : config.applicationClasspath()) {
      appCpByArtifactId.put(MavenCoordinateParser.parse(jar).artifactId(), jar);
    }

    StringBuilder classPath = new StringBuilder();
    Set<String> addedToManifest = new HashSet<>();
    for (Path jar : config.coreDeploymentClasspath()) {
      var coords = MavenCoordinateParser.parse(jar);
      if (addedToManifest.contains(coords.artifactId()) || isClassChangeAgent(coords)) {
        continue;
      }
      Path effectiveJar = appCpByArtifactId.getOrDefault(coords.artifactId(), jar);
      classPath.append(effectiveJar.toAbsolutePath().toUri()).append(' ');
      addedToManifest.add(coords.artifactId());
    }

    for (Path jar : collectParentFirstRuntimeJars(config, appModel)) {
      var coords = MavenCoordinateParser.parse(jar);
      if (!addedToManifest.contains(coords.artifactId())) {
        classPath.append(jar.toAbsolutePath().toUri()).append(' ');
        addedToManifest.add(coords.artifactId());
      }
    }

    return classPath.toString().trim();
  }

  /**
   * Finds runtime jars that extensions declare must be on the parent classloader, excluding those
   * already covered by the core deployment classpath. Matches Maven's {@code
   * ConfiguredClassLoading.getParentFirstArtifacts()}.
   */
  // Visible for testing
  static List<Path> collectParentFirstRuntimeJars(
      QuarkifierConfig config, ApplicationModel appModel) {
    Set<String> coreArtifactIds = new HashSet<>();
    for (Path jar : config.coreDeploymentClasspath()) {
      coreArtifactIds.add(MavenCoordinateParser.parse(jar).artifactId());
    }

    var parentFirstJars = new LinkedHashSet<Path>();
    for (ResolvedDependency dependency : appModel.getDependencies()) {
      if (!dependency.isClassLoaderParentFirst()
          || coreArtifactIds.contains(dependency.getArtifactId())) {
        continue;
      }
      for (Path path : dependency.getResolvedPaths()) {
        if (path.toString().endsWith(".jar")) {
          parentFirstJars.add(path);
        }
      }
    }
    return new ArrayList<>(parentFirstJars);
  }

  /**
   * Builds a {@link DevModeContext} configured for Bazel-managed dev mode.
   *
   * <p>The modules are the model's: the application's own, and one per reloadable workspace
   * dependency, each over its class directory in the dev session ({@link #moduleInfo}).
   */
  static DevModeContext buildDevModeContext(QuarkifierConfig config, ApplicationModel appModel) {
    var context = new DevModeContext();
    context.setAbortOnFailedStart(true);
    context.setLocalProjectDiscovery(false);
    context.setMode(QuarkusBootstrap.Mode.DEV);
    context.setBaseName(config.appName() != null ? config.appName() : "quarkus-app");
    context.setArgs(new String[0]);

    // The project root is a directory in the user's source tree, not Bazel's output directory,
    // so that the Dev UI "Workspace" tab shows the real sources. Prefer the application's own
    // module over the workspace root: extensions look for a Maven layout there.
    Path workspaceRoot = config.workspaceDir() != null ? config.workspaceDir() : config.outputDir();
    if (config.workspaceDir() == null) {
      LOGGER.warn(
          "Workspace directory not set. Dev UI workspace tab will not show source files."
              + " Use 'bazel run' to launch dev mode.");
    }
    Path projectRoot = applicationModuleRoot(config, workspaceRoot).orElse(workspaceRoot);
    context.setProjectDir(projectRoot.toAbsolutePath().toFile());

    // Platform properties for SmallRye Config expression resolution
    devBuildProperties(config)
        .forEach((k, v) -> context.getBuildSystemProperties().put((String) k, (String) v));

    ResolvedDependency application = appModel.getAppArtifact();
    context.setApplicationRoot(
        moduleInfo(
            application,
            config.appName() != null ? config.appName() : application.getArtifactId(),
            projectRoot,
            // targetDir must be a child of projectRoot so that WorkspaceProcessor (which does
            // targetDir.getParent() to find the project root) shows the correct source tree.
            // launch() creates it, since Bazel workspaces don't have a target/ dir.
            projectRoot.resolve("target")));
    for (ResolvedDependency dependency : reloadableWorkspaceDependencies(appModel)) {
      context
          .getAdditionalModules()
          .add(
              moduleInfo(
                  dependency,
                  dependency.getArtifactId(),
                  dependency.getWorkspaceModule().getModuleDir().toPath(),
                  null));
    }
    return context;
  }

  /**
   * The workspace dependencies Quarkus reloads, in model order: exactly the ones {@code
   * CuratedApplication} puts in the reloadable classloader. Each is a dev module, so that Quarkus's
   * scan covers every directory a restart reloads.
   */
  // Visible for testing
  static List<ResolvedDependency> reloadableWorkspaceDependencies(ApplicationModel appModel) {
    var reloadableKeys = appModel.getReloadableWorkspaceDependencies();
    return appModel.getDependencies().stream()
        .filter(
            dependency ->
                dependency.getWorkspaceModule() != null
                    && dependency.isRuntimeCp()
                    && dependency.isJar()
                    && dependency.isReloadable()
                    && reloadableKeys.contains(dependency.getKey()))
        .toList();
  }

  /**
   * The Maven-layout root of the module owning the application: the parent of the {@code src/main}
   * directory that holds its declared sources and resources.
   *
   * <p>Quarkus takes the build target directory from the dev context, and an extension that wants
   * the project it is building for walks up from there looking for a {@code src/main} marker —
   * Web Bundler's project scanner does, and reports no project root at all when the walk reaches
   * the filesystem root. A Bazel workspace root has no Maven layout, so the walk has to start
   * inside the application's own module. {@code quarkus_app} treats its first dep as the
   * application and the rule emits source and resource directories in dep order, which makes the
   * first entry with a {@code src/main} ancestor that module.
   *
   * <p>A module outside the workspace is not the application's: the directories are resolved
   * against the workspace root before they reach the launcher, so anything else is a relative
   * path that resolved against the current directory instead.
   */
  // Visible for testing
  static Optional<Path> applicationModuleRoot(QuarkifierConfig config, Path workspaceRoot) {
    Path workspace = workspaceRoot.toAbsolutePath();
    return Stream.concat(config.sourceDirs().stream(), config.resources().stream())
        .map(DevModeLauncher::mavenLayoutRoot)
        .flatMap(Optional::stream)
        .filter(root -> root.startsWith(workspace))
        .findFirst();
  }

  /** The module a {@code src/main} path belongs to, or empty when there is no such ancestor. */
  private static Optional<Path> mavenLayoutRoot(Path directory) {
    for (Path path = directory.toAbsolutePath(); path != null; path = path.getParent()) {
      if (path.endsWith(SRC_MAIN)) {
        // src/main is two elements, so the module is the grandparent.
        return Optional.ofNullable(path.getParent()).map(Path::getParent);
      }
    }
    return Optional.empty();
  }

  /**
   * Builds the {@link DevModeContext.ModuleInfo} of one model artifact: its class path is the
   * directory the model resolves it to, the dev session's directory for it, which holds the files
   * of the last build the session published.
   *
   * <p>The module declares no sources. Quarkus compiles whatever it finds under a module's sources
   * into the module's class path, and a module's class path here holds Bazel's outputs: Bazel owns
   * every compile, and its outputs are read-only to dev mode. With no sources, Quarkus's scan
   * reads the directory and never writes to it — it neither compiles into it nor deletes a class
   * whose source it cannot find.
   *
   * <p>The directory is the module's resource root as well as its class root, and deliberately its
   * only one. {@code RuntimeUpdatesProcessor.checkForFileChange} reads declared resource paths when
   * it has them and copies changed files out of them, and scans the classes directory only when it
   * has none — so declaring the workspace's resource directories would make Quarkus copy out of
   * the source tree and leave what Bazel writes unwatched. An extension that watches by classpath
   * location would then never see a rebuild: the Web Bundler registers such a watch over its whole
   * web root, which is how a relinked browser module reaches the running application at all.
   *
   * @param artifact the application artifact or a reloadable workspace dependency
   * @param name the module's display name
   * @param projectDirectory the workspace directory the module's sources live in
   * @param targetDir the module's build target directory, or {@code null} for a dependency
   * @throws IllegalStateException if the model does not resolve the artifact to exactly one path
   */
  private static DevModeContext.ModuleInfo moduleInfo(
      ResolvedDependency artifact, String name, Path projectDirectory, Path targetDir) {
    // Quarkus reads a module's class path as a single directory when it scans for changed
    // resources, so an artifact the model spreads over several paths has no faithful module.
    var paths = artifact.getResolvedPaths();
    if (paths.size() != 1) {
      throw new IllegalStateException(
          "Dev module "
              + artifact.toCompactCoords()
              + " must resolve to exactly one class directory, got "
              + paths);
    }
    String classesPath = paths.iterator().next().toAbsolutePath().toString();
    var builder =
        new DevModeContext.ModuleInfo.Builder()
            .setArtifactKey(artifact.getKey())
            .setName(name)
            .setProjectDirectory(projectDirectory.toAbsolutePath().toString())
            .setSourcePaths(PathList.empty())
            .setClassesPath(classesPath)
            .setResourcePaths(PathList.empty())
            .setResourcesOutputPath(classesPath);
    if (targetDir != null) {
      builder.setTargetDir(targetDir.toAbsolutePath().toString());
    }
    return builder.build();
  }
}
