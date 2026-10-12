package com.clementguillot.quarkifier;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Immutable configuration for a single augmentation invocation.
 *
 * @param applicationClasspath runtime jars
 * @param coreDeploymentClasspath dev process infrastructure — Quarkus bootstrap resolvers and
 *     quarkus-core-deployment transitive closures (dev mode only)
 * @param outputDir directory where the selected package is written
 * @param resources additional resource paths
 * @param mode NORMAL, TEST, DEV, or NATIVE
 * @param packageType Quarkus JVM package layout (used in NORMAL mode)
 * @param appName application name for Quarkus startup banner (may be {@code null})
 * @param mainClass fully-qualified custom main class name annotated with {@code @QuarkusMain} (may
 *     be {@code null})
 * @param nativeBuilderImage the native builder image for {@code
 *     platform.quarkus.native.builder-image} (may be {@code null})
 * @param sourceDirs the Java source roots of the application's graph: the hot-reload watcher
 *     observes them, and the application's module root is derived from them. Quarkus is not told
 *     about them, because Bazel owns every compile
 * @param watchDirs directories the hot-reload watcher observes — the source roots of every language
 *     Bazel compiles
 * @param bazelTargets Bazel targets to rebuild on source changes
 * @param workspaceDir Bazel workspace root directory for running bazel build (may be {@code null})
 * @param bazelBuildTimeoutSeconds timeout in seconds for bazel build process (default: 600)
 * @param bazelCommand bazel binary to invoke for hot-reload builds (default: {@code bazel})
 * @param bazelBuildArgs extra flags for the hot-reload {@code bazel build}
 * @param codegenInputDirs directories holding the declared CodeGenProvider inputs
 * @param projectFiles built outputs (files or directories) to the project directory each is
 *     mirrored into for the dev session
 * @param localAppJars local workspace jars to use as application roots
 * @param buildProperties declared hermetic build-time configuration
 * @param applicationModel explicit validated Bazel model JSON
 * @param artifactPaths each artifact path the model holds to the file holding that artifact where
 *     this invocation runs; an artifact absent from the map is read at the model's path
 */
public record QuarkifierConfig(
    List<Path> applicationClasspath,
    List<Path> coreDeploymentClasspath,
    Path outputDir,
    List<Path> resources,
    AugmentationMode mode,
    JarPackageType packageType,
    String appName,
    String mainClass,
    String nativeBuilderImage,
    List<Path> sourceDirs,
    List<Path> watchDirs,
    List<String> bazelTargets,
    Path workspaceDir,
    long bazelBuildTimeoutSeconds,
    String bazelCommand,
    List<String> bazelBuildArgs,
    List<Path> codegenInputDirs,
    Map<Path, Path> projectFiles,
    List<Path> localAppJars,
    Map<String, String> buildProperties,
    Path applicationModel,
    Map<String, Path> artifactPaths) {}
