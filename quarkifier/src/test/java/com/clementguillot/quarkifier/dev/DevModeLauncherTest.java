package com.clementguillot.quarkifier.dev;

import static org.junit.jupiter.api.Assertions.*;

import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.TestQuarkifierConfig;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
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

  @Test
  void buildDevModeContext_withSourceDirs_setsSourcePaths() {
    var sourceDirs = List.of(Path.of("src/main/java"), Path.of("lib/src/main/java"));
    var config = devConfig("--source-dirs", "src/main/java,lib/src/main/java");

    var context = DevModeLauncher.buildDevModeContext(config);

    assertNotNull(context.getApplicationRoot());
    var actualPaths =
        context.getApplicationRoot().getMain().getSourcePaths().stream()
            .collect(Collectors.toSet());
    var expectedPaths = sourceDirs.stream().map(Path::toAbsolutePath).collect(Collectors.toSet());
    assertEquals(expectedPaths.size(), actualPaths.size());
    for (Path expected : expectedPaths) {
      assertTrue(
          actualPaths.stream().anyMatch(p -> p.toAbsolutePath().equals(expected)),
          "Expected source path " + expected + " not found in " + actualPaths);
    }
  }

  @Test
  void buildDevModeContext_withoutSourceDirs_emptySourcePaths() {
    var context = DevModeLauncher.buildDevModeContext(devConfig());

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
    assertTrue(DevModeLauncher.buildDevModeContext(devConfig()).isAbortOnFailedStart());
  }

  @Test
  void buildDevModeContext_localProjectDiscovery_alwaysFalse() {
    var context = DevModeLauncher.buildDevModeContext(devConfig("--source-dirs", "src/main/java"));

    assertFalse(context.isLocalProjectDiscovery());
  }

  @Test
  void buildDevModeContext_includesDeclaredBuildProperties(@TempDir Path tempDir) throws Exception {
    Path propertiesFile = tempDir.resolve("build.properties");
    Files.writeString(
        propertiesFile, "smoke.ext.prefix=Declared\nquarkus.package.jar.type=legacy-jar\n");

    var context =
        DevModeLauncher.buildDevModeContext(
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
        DevModeLauncher.buildDevModeContext(
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

    var context = DevModeLauncher.buildDevModeContext(config);

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

    var context = DevModeLauncher.buildDevModeContext(config);

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

    var context = DevModeLauncher.buildDevModeContext(config);

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

    var context = DevModeLauncher.buildDevModeContext(config);

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

    var context = DevModeLauncher.buildDevModeContext(config);

    assertEquals(Path.of("/home/user/project/app").toFile(), context.getProjectDir());
  }

  @Test
  void buildDevModeContext_setsProjectDir_fromWorkspaceDir_whenModuleIsOutsideIt() {
    var config =
        devConfig(
            "--workspace-dir", "/home/user/project",
            "--source-dirs", "/elsewhere/app/src/main/java");

    var context = DevModeLauncher.buildDevModeContext(config);

    assertEquals(Path.of("/home/user/project").toFile(), context.getProjectDir());
  }

  @Test
  void buildDevModeContext_withClassesDir_usesClassesDirForClassesPath() {
    var classesDir = Path.of("/tmp/mutable-classes");
    var config =
        devConfig(
            "--source-dirs", "src/main/java",
            "--classes-dir", "/tmp/mutable-classes",
            "--bazel-targets", "//pkg:lib");

    var context = DevModeLauncher.buildDevModeContext(config);

    assertEquals(
        classesDir.toAbsolutePath().toString(),
        context.getApplicationRoot().getMain().getClassesPath());
  }

  @Test
  void buildDevModeContext_withoutClassesDir_usesAppJarForClassesPath() {
    var context = DevModeLauncher.buildDevModeContext(devConfig("--source-dirs", "src/main/java"));

    assertEquals(
        Path.of("app.jar").toAbsolutePath().toString(),
        context.getApplicationRoot().getMain().getClassesPath());
  }

  @Test
  void devProcessCommand_loadsTheClassChangeAgentAsAJavaAgent(@TempDir Path tempDir)
      throws Exception {
    Path core = deploymentJar(tempDir, "quarkus-core-deployment");
    Path agentJar = deploymentJar(tempDir, "quarkus-class-change-agent");
    var config = devConfig("--core-deployment-classpath", core + ":" + agentJar);

    List<String> cmd =
        DevModeLauncher.devProcessCommand(config, Path.of("model.dat"), Path.of("dev.jar"));

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
        DevModeLauncher.devProcessCommand(config, Path.of("model.dat"), Path.of("dev.jar"));

    assertTrue(cmd.stream().noneMatch(arg -> arg.startsWith("-javaagent:")), cmd::toString);
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
    Path jar = root.resolve("deployment/jars/" + artifactId + "-3.39.4.jar");
    Files.createDirectories(jar.getParent());
    try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(
          new ZipEntry("META-INF/maven/io.quarkus/" + artifactId + "/pom.properties"));
      out.write(
          ("groupId=io.quarkus\nartifactId=" + artifactId + "\nversion=3.39.4\n")
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
}
