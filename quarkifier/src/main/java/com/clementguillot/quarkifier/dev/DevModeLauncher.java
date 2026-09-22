package com.clementguillot.quarkifier.dev;

import com.clementguillot.quarkifier.AugmentationException;
import com.clementguillot.quarkifier.BuildProperties;
import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.maven.MavenCoordinateParser;
import com.clementguillot.quarkifier.watcher.BazelFileWatcher;
import com.clementguillot.quarkifier.watcher.ProjectFileMirror;
import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.dev.DevModeContext;
import io.quarkus.deployment.dev.DevModeMain;
import io.quarkus.maven.dependency.ArtifactKey;
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
 * @see <a href="../../../../../../docs/dev-mode.md">docs/dev-mode.md</a> for the full architecture
 */
@SuppressWarnings("PMD.ExceptionAsFlowControl")
public final class DevModeLauncher {

  private static final Logger LOGGER = Logger.getLogger(DevModeLauncher.class);

  private static final Path SRC_MAIN = Path.of("src", "main");

  private static final String CLASS_CHANGE_AGENT_GROUP_ID = "io.quarkus";
  private static final String CLASS_CHANGE_AGENT_ARTIFACT_ID = "quarkus-class-change-agent";

  private DevModeLauncher() {}

  /**
   * Launches Quarkus dev mode in a separate JVM process.
   *
   * @param config CLI configuration including source dirs, classpath, output dir
   * @param appModel the ApplicationModel built from classpath jars
   * @throws AugmentationException if dev mode fails to start
   */
  public static void launch(QuarkifierConfig config, ApplicationModel appModel)
      throws AugmentationException {
    try {
      DevModeContext context = buildDevModeContext(config);

      // Ensure the target directory exists — Quarkus writes build-metrics.json there.
      // In a Bazel workspace this directory doesn't exist by default (unlike Maven's target/).
      Files.createDirectories(Path.of(context.getApplicationRoot().getTargetDir()));

      // AppModelSerializerImpl is version-specific:
      // - 3.27: Java Object Serialization (BootstrapUtils)
      // - 3.33+: JSON format (ApplicationModelSerializer)
      AppModelSerializerStrategy serializer = new AppModelSerializerImpl();
      Path serializedModel = serializer.serialize(appModel);

      // Dev jar mirrors Maven's DevMojo / DevModeCommandLineBuilder.
      Path devJar = createDevJar(context, config, appModel);
      // Mirror the project files and populate the mutable classes directory before Quarkus
      // performs its initial scan. Starting the child first creates a race where the application
      // can boot without any user classes, or with the previous session's project files.
      ProjectFileMirror projectFiles = new ProjectFileMirror(config.projectFiles());
      if (!projectFiles.isEmpty()) {
        projectFiles.mirror();
      }
      BazelFileWatcher watcher = startWatcherIfConfigured(config, projectFiles);
      Process process = null;
      try {
        process = startDevProcess(config, serializedModel, devJar);

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

  /** Starts the child JVM running {@link DevModeMain} from the dev jar. */
  private static Process startDevProcess(QuarkifierConfig config, Path serializedModel, Path devJar)
      throws Exception {
    ProcessBuilder pb = new ProcessBuilder(devProcessCommand(config, serializedModel, devJar));
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
   */
  // Visible for testing
  static List<String> devProcessCommand(
      QuarkifierConfig config, Path serializedModel, Path devJar) {
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
    // Required for jboss-threads on Java 24+
    cmd.add("--add-opens");
    cmd.add("java.base/java.lang=ALL-UNNAMED");
    // Required for Quarkus 3.33+
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

  /** Starts the hot-reload file watcher when classes dir, targets, and source dirs are set. */
  private static BazelFileWatcher startWatcherIfConfigured(
      QuarkifierConfig config, ProjectFileMirror projectFiles) throws Exception {
    if (config.classesDir() == null
        || config.bazelTargets().isEmpty()
        || (config.sourceDirs().isEmpty()
            && config.watchDirs().isEmpty()
            && config.resources().isEmpty()
            && config.codegenInputDirs().isEmpty())) {
      return null;
    }
    LOGGER.debug("[hot-reload] Starting file watcher...");
    return BazelFileWatcher.startInBackground(config, projectFiles);
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

  /** Builds a {@link DevModeContext} configured for Bazel-managed dev mode. */
  static DevModeContext buildDevModeContext(QuarkifierConfig config) {
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

    context.setApplicationRoot(buildAppModuleInfo(config, projectRoot));
    return context;
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

  /** Builds the {@link DevModeContext.ModuleInfo} for the application root. */
  private static DevModeContext.ModuleInfo buildAppModuleInfo(
      QuarkifierConfig config, Path projectRoot) {
    Path appJar = config.applicationClasspath().get(0);
    var coords = MavenCoordinateParser.parse(appJar);

    // Key: classesPath points to mutable directory when available, otherwise the jar
    Path classesPath = config.classesDir() != null ? config.classesDir() : appJar;

    // The mutable directory is the module's resource root as well as its class root, and
    // deliberately its only one. `RuntimeUpdatesProcessor.checkForFileChange` reads declared
    // resource paths when it has them and copies changed files out of them, and scans the
    // classes directory only when it has none — so declaring the workspace's resource
    // directories would leave everything Bazel writes into the mutable directory unwatched.
    // An extension that watches by classpath location would then never see a rebuild: the
    // Web Bundler registers such a watch over its whole web root, which is how a relinked
    // browser module reaches the running application at all.
    //
    // The cost is that a resource edit is delivered by the rebuild rather than copied
    // straight out of the source tree, so it takes as long as one. That is the same
    // exchange the rest of dev mode makes here: what runs is what Bazel built, and a
    // source tree Quarkus copies from independently would diverge from it until the next
    // rebuild happened to agree.

    // targetDir must be a child of projectRoot so that WorkspaceProcessor (which does
    // targetDir.getParent() to find the project root) shows the correct source tree.
    // We create the directory in launch() since Bazel workspaces don't have a target/ dir.
    Path targetDir = projectRoot.resolve("target");
    Path resourcesOutputPath = config.classesDir() != null ? config.classesDir() : targetDir;

    return new DevModeContext.ModuleInfo.Builder()
        .setArtifactKey(ArtifactKey.ga(coords.groupId(), coords.artifactId()))
        .setName(config.appName() != null ? config.appName() : coords.artifactId())
        .setProjectDirectory(projectRoot.toAbsolutePath().toString())
        .setSourcePaths(PathList.from(config.sourceDirs()))
        .setClassesPath(classesPath.toAbsolutePath().toString())
        .setResourcePaths(PathList.from(List.<Path>of()))
        .setResourcesOutputPath(resourcesOutputPath.toAbsolutePath().toString())
        .setTargetDir(targetDir.toAbsolutePath().toString())
        .build();
  }
}
