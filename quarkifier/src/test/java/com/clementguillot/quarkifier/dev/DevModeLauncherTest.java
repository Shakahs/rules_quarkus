package com.clementguillot.quarkifier.dev;

import static org.junit.jupiter.api.Assertions.*;

import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.TestQuarkifierConfig;
import com.clementguillot.quarkifier.devhook.GenerationSwitch;
import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.deployment.dev.DevModeContext;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.paths.PathList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link DevModeLauncher}: the dev context, the child JVM and its classpath. */
class DevModeLauncherTest {

  /** Builds a dev-mode config from the baseline flags plus {@code extraArgs}. */
  private static QuarkifierConfig devConfig(String... extraArgs) {
    var args =
        new ArrayList<>(
            List.of(
                "--application-classpath", "app.jar",
                "--application-model", "model.json",
                "--output-dir", "/tmp/output",
                "--mode", "dev",
                "--app-name", "my-app"));
    args.addAll(List.of(extraArgs));
    return TestQuarkifierConfig.parse(args.toArray(String[]::new));
  }

  private static final Path APP_CLASSES = Path.of("/bazel-bin/app/app.quarkus-classes");

  /** The dev context for {@code config} over a model holding the application alone. */
  private static DevModeContext context(QuarkifierConfig config) {
    return DevModeLauncher.buildDevModeContext(config, modelBuilder().build());
  }

  /** A model builder whose application resolves to {@link #APP_CLASSES}. */
  private static ApplicationModelBuilder modelBuilder() {
    var builder = new ApplicationModelBuilder();
    builder.setAppArtifact(
        ResolvedDependencyBuilder.newInstance()
            .setGroupId("bazel.workspace")
            .setArtifactId("app")
            .setVersion("1.0")
            .setType("jar")
            .setResolvedPath(APP_CLASSES)
            .setRuntimeCp()
            .setDeploymentCp());
    return builder;
  }

  /** A workspace dependency the model resolves to {@code classes}. */
  private static ResolvedDependencyBuilder workspaceDependency(
      String artifactId, Path moduleDir, Path... classes) {
    return ResolvedDependencyBuilder.newInstance()
        .setGroupId("bazel.workspace")
        .setArtifactId(artifactId)
        .setVersion("1.0-SNAPSHOT")
        .setType("jar")
        .setResolvedPaths(PathList.of(classes))
        .setWorkspaceModule(
            WorkspaceModule.builder()
                .setModuleId(WorkspaceModuleId.of("bazel.workspace", artifactId, "1.0-SNAPSHOT"))
                .setModuleDir(moduleDir)
                .setBuildDir(moduleDir.resolve("bazel-bin"))
                .build())
        .setRuntimeCp()
        .setDeploymentCp();
  }

  /** Adds {@code dependency} to {@code builder} as a reloadable workspace module. */
  private static void addReloadable(
      ApplicationModelBuilder builder, ResolvedDependencyBuilder dependency) {
    dependency.setReloadable();
    builder.addDependency(dependency);
    builder.addReloadableWorkspaceModule(dependency.getKey());
  }

  /** Bazel owns every compile: Quarkus is told of no sources, whatever the watcher observes. */
  @Test
  void buildDevModeContext_declaresNoSourcesToQuarkus() {
    var context = context(devConfig("--source-dirs", "src/main/java,lib/src/main/java"));

    assertTrue(context.getApplicationRoot().getMain().getSourcePaths().isEmpty());
  }

  @Test
  void buildDevModeContext_applicationModuleReadsTheModelsDirectoryInPlace() {
    var main = context(devConfig()).getApplicationRoot().getMain();

    assertEquals(APP_CLASSES.toAbsolutePath().toString(), main.getClassesPath());
    // The directory is the only resource root, so Quarkus scans it rather than copying into it.
    assertTrue(main.getResourcePaths().isEmpty());
    assertEquals(APP_CLASSES.toAbsolutePath().toString(), main.getResourcesOutputPath());
  }

  @Test
  void buildDevModeContext_eachReloadableWorkspaceDependencyIsAModuleOverItsOwnDirectory() {
    Path libDir = Path.of("/workspace/lib");
    Path libClasses = Path.of("/bazel-bin/lib/lib.quarkus-classes");
    var builder = modelBuilder();
    addReloadable(builder, workspaceDependency("lib", libDir, libClasses));
    // A workspace module the model does not mark reloadable, and an external jar, are not.
    builder.addDependency(
        workspaceDependency("fixed", Path.of("/workspace/fixed"), Path.of("/bazel-bin/fixed")));
    builder.addDependency(
        ResolvedDependencyBuilder.newInstance()
            .setGroupId("org.example")
            .setArtifactId("external")
            .setVersion("1.0")
            .setType("jar")
            .setResolvedPath(Path.of("/cache/external-1.0.jar"))
            .setRuntimeCp()
            .setDeploymentCp());

    var context = DevModeLauncher.buildDevModeContext(devConfig(), builder.build());

    assertEquals(1, context.getAdditionalModules().size(), context.getAdditionalModules()::toString);
    var module = context.getAdditionalModules().get(0);
    assertEquals("lib", module.getArtifactKey().getArtifactId());
    assertEquals(libDir.toAbsolutePath().toString(), module.getProjectDirectory());
    assertEquals(libClasses.toAbsolutePath().toString(), module.getMain().getClassesPath());
    assertTrue(module.getMain().getSourcePaths().isEmpty());
    assertTrue(module.getMain().getResourcePaths().isEmpty());
    assertEquals(
        libClasses.toAbsolutePath().toString(), module.getMain().getResourcesOutputPath());
  }

  /** Quarkus scans a module's resources as one directory, so a module of several paths fails. */
  @Test
  void buildDevModeContext_failsForAModuleOfSeveralPaths() {
    var builder = modelBuilder();
    addReloadable(
        builder,
        workspaceDependency(
            "split", Path.of("/workspace/split"), Path.of("/bazel-bin/a"), Path.of("/bazel-bin/b")));
    var model = builder.build();

    var error =
        assertThrows(
            IllegalStateException.class,
            () -> DevModeLauncher.buildDevModeContext(devConfig(), model));
    assertTrue(error.getMessage().contains("split"), error::getMessage);
  }

  @Test
  void buildDevModeContext_withoutSourceDirs_emptySourcePaths() {
    var context = context(devConfig());

    assertNotNull(context.getApplicationRoot());
    var sourcePaths = context.getApplicationRoot().getMain().getSourcePaths();
    assertTrue(
        sourcePaths == null || !sourcePaths.iterator().hasNext(),
        "Expected empty source paths but got: " + sourcePaths);
    assertEquals(QuarkusBootstrap.Mode.DEV, context.getMode());
    assertNotNull(context.getProjectDir());
  }

  @Test
  void buildDevModeContext_abortOnFailedStart_alwaysTrue() {
    assertTrue(context(devConfig()).isAbortOnFailedStart());
  }

  @Test
  void buildDevModeContext_localProjectDiscovery_alwaysFalse() {
    var context = context(devConfig("--source-dirs", "src/main/java"));

    assertFalse(context.isLocalProjectDiscovery());
  }

  @Test
  void buildDevModeContext_includesDeclaredBuildProperties(@TempDir Path tempDir) throws Exception {
    Path propertiesFile = tempDir.resolve("build.properties");
    Files.writeString(
        propertiesFile, "smoke.ext.prefix=Declared\nquarkus.package.jar.type=legacy-jar\n");

    var context =
        context(
            devConfig("--build-properties-file", propertiesFile.toString()));

    assertEquals("Declared", context.getBuildSystemProperties().get("smoke.ext.prefix"));
    assertEquals("fast-jar", context.getBuildSystemProperties().get("quarkus.package.jar.type"));
  }

  @Test
  void buildDevModeContext_mainClassFlagOverridesDeclaredProperty(@TempDir Path tempDir)
      throws Exception {
    Path propertiesFile = tempDir.resolve("build.properties");
    Files.writeString(propertiesFile, "quarkus.package.main-class=wrong.Main\n");

    var context =
        context(
            devConfig(
                "--build-properties-file",
                propertiesFile.toString(),
                "--main-class",
                "example.Main"));

    assertEquals(
        "example.Main", context.getBuildSystemProperties().get("quarkus.package.main-class"));
  }

  @Test
  void buildDevModeContext_fallsBackToOutputDir_whenWorkspaceDirNull() {
    var config = devConfig("--source-dirs", "src/main/java");

    var context = context(config);

    assertEquals("my-app", context.getBaseName());
    // Falls back to outputDir when workspaceDir is null
    assertEquals(config.outputDir().toAbsolutePath().toFile(), context.getProjectDir());
    assertEquals(
        config.outputDir().resolve("target").toAbsolutePath().toString(),
        context.getApplicationRoot().getTargetDir());
    assertEquals(
        config.outputDir().toAbsolutePath().toString(),
        context.getApplicationRoot().getProjectDirectory());
  }

  @Test
  void buildDevModeContext_setsProjectDir_fromWorkspaceDir() {
    var workspaceDir = Path.of("/home/user/project");
    var config =
        devConfig("--source-dirs", "src/main/java", "--workspace-dir", "/home/user/project");

    var context = context(config);

    assertEquals(workspaceDir.toAbsolutePath().toFile(), context.getProjectDir());
    assertEquals(
        workspaceDir.toAbsolutePath().toString(),
        context.getApplicationRoot().getProjectDirectory());
  }

  @Test
  void buildDevModeContext_setsTargetDir_asSubdirOfWorkspaceDir() {
    var workspaceDir = Path.of("/home/user/project");
    var config =
        devConfig("--source-dirs", "src/main/java", "--workspace-dir", "/home/user/project");

    var context = context(config);

    assertEquals(
        workspaceDir.resolve("target").toAbsolutePath().toString(),
        context.getApplicationRoot().getTargetDir());
  }

  @Test
  void buildDevModeContext_setsProjectDir_fromApplicationModule() {
    var config =
        devConfig(
            "--workspace-dir", "/home/user/project",
            "--source-dirs", "/home/user/project/app/src/main/java",
            "--resources", "/home/user/project/app/src/main/resources");

    var context = context(config);

    var moduleRoot = Path.of("/home/user/project/app");
    assertEquals(moduleRoot.toFile(), context.getProjectDir());
    assertEquals(moduleRoot.toString(), context.getApplicationRoot().getProjectDirectory());
    assertEquals(
        moduleRoot.resolve("target").toString(), context.getApplicationRoot().getTargetDir());
  }

  /** The application module is the first declared one: the rule emits them in dep order. */
  @Test
  void buildDevModeContext_setsProjectDir_fromFirstModule_whenSeveralContributeSources() {
    var config =
        devConfig(
            "--workspace-dir",
            "/home/user/project",
            "--source-dirs",
            "/home/user/project/app/src/main/java,/home/user/project/lib/src/main/java");

    var context = context(config);

    assertEquals(Path.of("/home/user/project/app").toFile(), context.getProjectDir());
  }

  @Test
  void buildDevModeContext_setsProjectDir_fromWorkspaceDir_whenModuleIsOutsideIt() {
    var config =
        devConfig(
            "--workspace-dir", "/home/user/project",
            "--source-dirs", "/elsewhere/app/src/main/java");

    var context = context(config);

    assertEquals(Path.of("/home/user/project").toFile(), context.getProjectDir());
  }

  @Test
  void devProcessCommand_loadsTheClassChangeAgentAsAJavaAgent(@TempDir Path tempDir)
      throws Exception {
    Path core = deploymentJar(tempDir, "quarkus-core-deployment");
    Path agentJar = deploymentJar(tempDir, "quarkus-class-change-agent");
    var config = devConfig("--core-deployment-classpath", core + ":" + agentJar);

    List<String> cmd =
        DevModeLauncher.devProcessCommand(
            config, Path.of("model.dat"), null, Path.of("dev.jar"), Path.of("/session"));

    String agent = "-javaagent:" + agentJar;
    assertTrue(cmd.contains(agent), cmd::toString);
    assertTrue(cmd.indexOf(agent) < cmd.indexOf("-jar"), "JVM options precede -jar: " + cmd);
  }

  @Test
  void devProcessCommand_withoutTheAgentInTheClosure_loadsNoAgent(@TempDir Path tempDir)
      throws Exception {
    Path core = deploymentJar(tempDir, "quarkus-core-deployment");
    var config = devConfig("--core-deployment-classpath", core.toString());

    List<String> cmd =
        DevModeLauncher.devProcessCommand(
            config, Path.of("model.dat"), null, Path.of("dev.jar"), Path.of("/session"));

    assertTrue(cmd.stream().noneMatch(arg -> arg.startsWith("-javaagent:")), cmd::toString);
  }

  @Test
  void devProcessCommand_namesTheDevSessionToItsHook(@TempDir Path tempDir) throws Exception {
    Path core = deploymentJar(tempDir, "quarkus-core-deployment");
    var config = devConfig("--core-deployment-classpath", core.toString());

    List<String> cmd =
        DevModeLauncher.devProcessCommand(
            config, Path.of("model.dat"), null, Path.of("dev.jar"), Path.of("/session"));

    String property = "-D" + GenerationSwitch.SESSION_PROPERTY + "=/session";
    assertTrue(cmd.contains(property), cmd::toString);
    assertTrue(cmd.indexOf(property) < cmd.indexOf("-jar"), "JVM options precede -jar: " + cmd);
  }

  @Test
  void buildManifestClassPath_leavesTheClassChangeAgentToTheJavaAgentFlag(@TempDir Path tempDir)
      throws Exception {
    Path core = deploymentJar(tempDir, "quarkus-core-deployment");
    Path agentJar = deploymentJar(tempDir, "quarkus-class-change-agent");
    var config = devConfig("--core-deployment-classpath", core + ":" + agentJar);
    var modelBuilder = new ApplicationModelBuilder();
    modelBuilder.setAppArtifact(
        ResolvedDependencyBuilder.newInstance()
            .setGroupId("com.example")
            .setArtifactId("app")
            .setVersion("1.0")
            .setType("jar")
            .setRuntimeCp()
            .setDeploymentCp());

    String classPath = DevModeLauncher.buildManifestClassPath(config, modelBuilder.build());

    assertEquals(core.toAbsolutePath().toUri().toString(), classPath);
  }

  /**
   * An {@code io.quarkus} jar as the generated repository holds it: flat under {@code
   * deployment/jars/}, where the path carries no groupId and the coordinates come from the jar's
   * own {@code pom.properties}.
   */
  private static Path deploymentJar(Path root, String artifactId) throws Exception {
    Path jar = root.resolve("deployment/jars/" + artifactId + "-3.39.5.jar");
    Files.createDirectories(jar.getParent());
    try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(
          new ZipEntry("META-INF/maven/io.quarkus/" + artifactId + "/pom.properties"));
      out.write(
          ("groupId=io.quarkus\nartifactId=" + artifactId + "\nversion=3.39.5\n")
              .getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    return jar;
  }

  /**
   * Regression: collectParentFirstRuntimeJars must return jars flagged CLASSLOADER_PARENT_FIRST
   * even though dep.getKey() has type="jar" (GACT strict equals).
   */
  @Test
  void collectParentFirstRuntimeJars_returnsParentFirstJar_despiteGactTypeMismatch() {
    // Jar path that MavenCoordinateParser can parse: uses a dot-segment as stop marker
    // so the parser identifies the groupId correctly
    Path parentFirstJar =
        Path.of("/cache/v1.repo/org/example/parent-first-lib/1.0/parent-first-lib-1.0.jar");
    Path regularJar = Path.of("/cache/v1.repo/org/example/regular-lib/2.0/regular-lib-2.0.jar");
    // Core classpath uses a different jar so parent-first isn't excluded
    Path coreJar = Path.of("/cache/v1.repo/io/quarkus/quarkus-core/3.27.4/quarkus-core-3.27.4.jar");

    var config =
        devConfig(
            "--application-classpath",
            regularJar.toString(),
            "--core-deployment-classpath",
            coreJar.toString());

    // Build model with one dep flagged CLASSLOADER_PARENT_FIRST (type="jar" as in production)
    var modelBuilder = new ApplicationModelBuilder();
    modelBuilder.setAppArtifact(
        ResolvedDependencyBuilder.newInstance()
            .setGroupId("com.example")
            .setArtifactId("app")
            .setVersion("1.0")
            .setType("jar")
            .setRuntimeCp()
            .setDeploymentCp());
    var parentFirstDep =
        ResolvedDependencyBuilder.newInstance()
            .setGroupId("org.example")
            .setArtifactId("parent-first-lib")
            .setVersion("1.0")
            .setType("jar")
            .setResolvedPath(parentFirstJar)
            .setFlags(DependencyFlags.CLASSLOADER_PARENT_FIRST)
            .setRuntimeCp()
            .setDeploymentCp();
    modelBuilder.addDependency(parentFirstDep);
    modelBuilder.addParentFirstArtifact(parentFirstDep.getKey());
    modelBuilder.addDependency(
        ResolvedDependencyBuilder.newInstance()
            .setGroupId("org.example")
            .setArtifactId("regular-lib")
            .setVersion("2.0")
            .setType("jar")
            .setResolvedPath(regularJar)
            .setRuntimeCp()
            .setDeploymentCp());
    ApplicationModel model = modelBuilder.build();

    List<Path> result = DevModeLauncher.collectParentFirstRuntimeJars(config, model);

    assertEquals(List.of(parentFirstJar), result);
  }

  // ---- continuous tests ----

  @Test
  void devProcessCommand_runsTheContinuousTestsWithTheirModelAndFlags(@TempDir Path tempDir)
      throws Exception {
    Path core = deploymentJar(tempDir, "quarkus-core-deployment");
    Path flags = Files.writeString(tempDir.resolve("flags.txt"), "-Xmx2g\n-Duser.timezone=UTC\n");
    var config =
        devConfig(
            "--core-deployment-classpath",
            core.toString(),
            "--continuous-test-model",
            "test-model.json",
            "--continuous-test-jvm-flags-file",
            flags.toString());

    List<String> cmd =
        DevModeLauncher.devProcessCommand(
            config,
            Path.of("model.dat"),
            Path.of("/tmp/test-model.dat"),
            Path.of("dev.jar"),
            Path.of("/session"));

    String testModel = "-D" + BootstrapConstants.SERIALIZED_TEST_APP_MODEL + "=/tmp/test-model.dat";
    assertTrue(cmd.contains(testModel), cmd::toString);
    assertTrue(cmd.contains("-Xmx2g"), cmd::toString);
    assertTrue(cmd.contains("-Duser.timezone=UTC"), cmd::toString);
    assertTrue(cmd.contains("-Dquarkus.test.only-test-application-module=true"), cmd::toString);
    assertTrue(cmd.indexOf("-Xmx2g") < cmd.indexOf("-jar"), "JVM options precede -jar: " + cmd);
  }

  @Test
  void buildDevModeContext_withContinuousTests_givesTheApplicationItsTestClasses() {
    var tests =
        new DevModeLauncher.ContinuousTests(
            Path.of("/session/test-trigger"),
            Path.of("/session/current/test-classes"),
            Path.of("/session/no-classes"),
            List.of());

    var context = DevModeLauncher.buildDevModeContext(devConfig(), modelBuilder().build(), tests);

    var test = context.getApplicationRoot().getTest().orElseThrow();
    assertEquals("/session/current/test-classes", test.getClassesPath());
    assertEquals("/session/current/test-classes", test.getResourcesOutputPath());
    assertEquals(1, test.getSourcePaths().size());
    assertTrue(test.getSourcePaths().contains(Path.of("/session/test-trigger")));
    assertTrue(test.getResourcePaths().isEmpty());
  }

  @Test
  void buildDevModeContext_aWorkspaceJarOnlyTheTestsReachHoldsTestClassesOnly() {
    Path fixtureClasses = Path.of("/session/current/002-fixture");
    var fixture = workspaceDependency("fixture", Path.of("/workspace/fixture"), fixtureClasses);
    var tests =
        new DevModeLauncher.ContinuousTests(
            Path.of("/session/test-trigger"),
            Path.of("/session/current/test-classes"),
            Path.of("/session/no-classes"),
            List.of(fixture.build()));

    var context = DevModeLauncher.buildDevModeContext(devConfig(), modelBuilder().build(), tests);

    var module = context.getAdditionalModules().get(0);
    assertEquals("/session/no-classes", module.getMain().getClassesPath());
    assertEquals("/session/no-classes", module.getMain().getResourcesOutputPath());
    assertEquals(fixtureClasses.toString(), module.getTest().orElseThrow().getClassesPath());
  }
}
