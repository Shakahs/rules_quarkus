package com.clementguillot.quarkifier.augmentation;

import com.clementguillot.quarkifier.AugmentationException;
import com.clementguillot.quarkifier.AugmentationMode;
import com.clementguillot.quarkifier.BuildProperties;
import com.clementguillot.quarkifier.JarPackageType;
import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.QuarkifierVersionProvider;
import com.clementguillot.quarkifier.dev.AppModelSerializerImpl;
import com.clementguillot.quarkifier.dev.AppModelSerializerStrategy;
import com.clementguillot.quarkifier.dev.DevModeLauncher;
import com.clementguillot.quarkifier.model.ExplicitApplicationModelBuilder;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModelReader;
import io.quarkus.bootstrap.app.AugmentAction;
import io.quarkus.bootstrap.app.AugmentResult;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.model.ApplicationModel;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;

/**
 * Orchestrates Quarkus augmentation: builds the ApplicationModel, invokes the Quarkus build API,
 * selects the requested output layout, and post-processes Fast JAR output when necessary.
 *
 * <p>For DEV mode, delegates entirely to {@link DevModeLauncher}.
 */
public final class AugmentationExecutor {

  private AugmentationExecutor() {}

  /**
   * Executes the full augmentation pipeline for the given configuration.
   *
   * @param config the parsed CLI configuration
   * @throws AugmentationException if augmentation fails
   */
  public static void execute(QuarkifierConfig config) throws AugmentationException {
    try {
      Path outputDir = config.outputDir().toAbsolutePath().normalize();
      Files.createDirectories(outputDir);

      if (config.applicationClasspath().isEmpty()) {
        throw new AugmentationException(
            "Application classpath is empty; at least one jar is required.");
      }

      ClasspathPartition partition = LocalExtensionAppJars.reclassify(partitionClasspath(config));
      ApplicationModel appModel = buildModel(config);
      List<Path> effectiveRuntimeJars = RuntimeJarSelector.select(partition, appModel);

      switch (config.mode()) {
          // DEV: delegate to DevModeLauncher which starts IsolatedDevModeMain
          // with full Dev UI and hot-reload support.
        case DEV -> DevModeLauncher.launch(config, appModel);
          // TEST: serialize the ApplicationModel for use by QuarkusTestExtension.
          // No augmentation is run — the test JVM handles that via QuarkusBootstrap.Mode.TEST.
        case TEST -> serializeTestModel(outputDir, appModel);
        case NATIVE -> {
          Path stagingDir = createAugmentationStagingDirectory();
          Path stagingTarget = stagingDir.resolve("target");
          runAugmentation(config, appModel, stagingTarget);
          Path nativeSources = NativeSourcesAssembler.assemble(stagingTarget, effectiveRuntimeJars);
          copyTree(nativeSources, outputDir.resolve("native-sources"));
        }
        case NORMAL -> {
          Path stagingDir = createAugmentationStagingDirectory();
          Path stagingTarget = stagingDir.resolve("target");
          runAugmentation(config, appModel, stagingTarget);
          if (config.packageType() == JarPackageType.FAST_JAR) {
            FastJarAssembler.assemble(
                stagingTarget,
                effectiveRuntimeJars,
                appModel,
                config.resources(),
                config.mainClass());
          }
          config.packageType().validateOutput(stagingTarget);
          publishJvmPackage(stagingTarget, outputDir, config.packageType());
        }
        default -> throw new AugmentationException("Unhandled mode: " + config.mode());
      }
    } catch (AugmentationException e) {
      throw e;
    } catch (Exception e) {
      throw new AugmentationException("Quarkus augmentation failed: " + e.getMessage(), e);
    }
  }

  private static Path createAugmentationStagingDirectory() throws java.io.IOException {
    Path stagingDir = Files.createTempDirectory("quarkifier-project-");
    Files.createDirectories(stagingDir.resolve("src/main"));
    Files.createDirectories(stagingDir.resolve("target"));
    return stagingDir;
  }

  private static void publishJvmPackage(
      Path stagingTarget, Path outputDir, JarPackageType packageType) throws java.io.IOException {
    switch (packageType) {
      case FAST_JAR, MUTABLE_JAR, AOT_JAR ->
          copyTree(stagingTarget.resolve("quarkus-app"), outputDir.resolve("quarkus-app"));
      case UBER_JAR ->
          copyFile(stagingTarget.resolve("quarkus-run.jar"), outputDir.resolve("quarkus-run.jar"));
      case LEGACY_JAR -> {
        copyFile(stagingTarget.resolve("quarkus-run.jar"), outputDir.resolve("quarkus-run.jar"));
        Path legacyLib = stagingTarget.resolve("lib");
        if (Files.isDirectory(legacyLib)) {
          copyTree(legacyLib, outputDir.resolve("lib"));
        }
      }
    }
    Path artifactProperties = stagingTarget.resolve("quarkus-artifact.properties");
    if (Files.isRegularFile(artifactProperties)) {
      copyFile(artifactProperties, outputDir.resolve(artifactProperties.getFileName()));
    }
  }

  private static void copyTree(Path source, Path target) throws java.io.IOException {
    if (!Files.isDirectory(source)) {
      throw new java.io.IOException("Expected package directory does not exist: " + source);
    }
    try (var paths = Files.walk(source)) {
      for (Path path : paths.sorted().toList()) {
        Path destination = target.resolve(source.relativize(path).toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination);
        } else {
          copyFile(path, destination);
        }
      }
    }
  }

  private static void copyFile(Path source, Path target) throws java.io.IOException {
    Path parent = target.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
  }

  private static ApplicationModel buildModel(QuarkifierConfig config) throws Exception {
    if (config.applicationModel() == null) {
      throw new AugmentationException("--application-model is required");
    }
    var explicitModel = BazelApplicationModelReader.read(config.applicationModel());
    validateModelCompatibility(config.mode(), explicitModel);
    config.packageType().validateCompatibility(config.mode(), explicitModel.quarkusVersion());
    return ExplicitApplicationModelBuilder.build(explicitModel);
  }

  static void validateModelCompatibility(AugmentationMode mode, BazelApplicationModel explicitModel)
      throws AugmentationException {
    if (!mode.name().equals(explicitModel.mode().name())) {
      throw new AugmentationException(
          "Explicit application model mode "
              + explicitModel.mode()
              + " does not match augmentation mode "
              + mode);
    }
    String targetedQuarkusVersion = QuarkifierVersionProvider.targetedQuarkusVersion();
    if ("unknown".equals(targetedQuarkusVersion)) {
      throw new AugmentationException(
          "Cannot determine the Quarkus version targeted by this quarkifier artifact");
    }
    if (!targetedQuarkusVersion.equals(explicitModel.quarkusVersion())) {
      throw new AugmentationException(
          "Explicit application model Quarkus version "
              + explicitModel.quarkusVersion()
              + " does not match quarkifier target version "
              + targetedQuarkusVersion);
    }
  }

  /** Writes the serialized test ApplicationModel to {@code <output-dir>/test-app-model.dat}. */
  private static void serializeTestModel(Path outputDir, ApplicationModel appModel)
      throws Exception {
    Path modelFile = outputDir.resolve("test-app-model.dat");
    AppModelSerializerStrategy serializer = new AppModelSerializerImpl();
    Path serializedModel = serializer.serialize(appModel);
    if (!serializedModel.equals(modelFile)) {
      Files.copy(serializedModel, modelFile, StandardCopyOption.REPLACE_EXISTING);
      Files.deleteIfExists(serializedModel);
    }
  }

  /** Runs the Quarkus bootstrap and augmentation for production/native modes. */
  private static void runAugmentation(
      QuarkifierConfig config, ApplicationModel appModel, Path outputDir)
      throws Exception {

    Properties buildProps =
        config.mode() == AugmentationMode.NATIVE
            ? BuildProperties.nativeSourcesOnly(
                config.buildProperties(), config.mainClass(), config.nativeBuilderImage())
            : BuildProperties.defaults(
                config.buildProperties(),
                config.mainClass(),
                config.nativeBuilderImage(),
                config.packageType());

    BuildProperties.withSystemProperties(
        buildProps,
        () -> {
          QuarkusBootstrap bootstrap =
              QuarkusBootstrap.builder()
                  .setExistingModel(appModel)
                  .setApplicationRoot(appModel.getAppArtifact().getResolvedPaths())
                  .setTargetDirectory(outputDir)
                  .setBaseName(
                      config.mode() == AugmentationMode.NATIVE && config.appName() != null
                          ? config.appName()
                          : "quarkus-run")
                  .setMode(mapMode(config.mode()))
                  // Workspace dependencies are present in both the augmentation and deployment
                  // layers. Isolation is required to keep reloadable runtime classes in exactly
                  // one layer; otherwise config mappings and build-step parameters can have
                  // distinct Class identities.
                  .setIsolateDeployment(true)
                  .setFlatClassPath(true)
                  .setLocalProjectDiscovery(false)
                  .setBuildSystemProperties(buildProps)
                  .build();

          try (CuratedApplication curatedApp = bootstrap.bootstrap()) {
            // Load AugmentActionImpl from the augment classloader to avoid classloader
            // conflicts. Set the TCCL so ASM's ClassWriter.getCommonSuperClass() can
            // resolve all types during bytecode generation.
            ClassLoader augmentCl = curatedApp.getOrCreateAugmentClassLoader();
            Class<?> augmentClass =
                augmentCl.loadClass("io.quarkus.runner.bootstrap.AugmentActionImpl");

            Constructor<?> ctor = findAugmentConstructor(augmentClass);

            ClassLoader originalTccl = Thread.currentThread().getContextClassLoader();
            try {
              Thread.currentThread().setContextClassLoader(augmentCl);

              AugmentAction action = (AugmentAction) ctor.newInstance(curatedApp);
              AugmentResult result = action.createProductionApplication();
              if (result == null) {
                throw new AugmentationException(
                    "Augmentation produced no result for output directory: " + outputDir);
              }
            } finally {
              Thread.currentThread().setContextClassLoader(originalTccl);
            }
          }
          return null;
        });
  }

  /**
   * Finds the single-arg AugmentActionImpl constructor. We match by class name string because the
   * constructor's parameter type is loaded by the augment classloader, not our system classloader.
   */
  private static Constructor<?> findAugmentConstructor(Class<?> augmentClass)
      throws AugmentationException {
    for (Constructor<?> c : augmentClass.getConstructors()) {
      Class<?>[] paramTypes = c.getParameterTypes();
      if (paramTypes.length == 1
          && "io.quarkus.bootstrap.app.CuratedApplication".equals(paramTypes[0].getName())) {
        return c;
      }
    }
    throw new AugmentationException(
        "Cannot find AugmentActionImpl(CuratedApplication) constructor");
  }

  private static QuarkusBootstrap.Mode mapMode(AugmentationMode mode) {
    return switch (mode) {
      case NORMAL, NATIVE -> QuarkusBootstrap.Mode.PROD;
      case TEST -> QuarkusBootstrap.Mode.TEST;
      case DEV -> QuarkusBootstrap.Mode.DEV;
    };
  }

  /**
   * Partitions the application classpath into local app jars (used as application roots for Jandex
   * indexing) and runtime jars (external Maven dependencies).
   *
   * <p>Package-private for testability.
   */
  static ClasspathPartition partitionClasspath(QuarkifierConfig config)
      throws AugmentationException {
    List<Path> appCp = config.applicationClasspath();

    // Determine local app jars: use --local-app-jars when provided, otherwise
    // fall back to the first element of the application classpath.
    List<Path> localAppJars =
        config.localAppJars().isEmpty() ? List.of(appCp.get(0)) : config.localAppJars();

    List<Path> unknownLocalAppJars =
        localAppJars.stream().filter(jar -> !appCp.contains(jar)).toList();
    if (!unknownLocalAppJars.isEmpty()) {
      throw new AugmentationException(
          "--local-app-jars must be a subset of the application classpath: " + unknownLocalAppJars);
    }

    // Runtime jars are everything in appCp that is NOT in localAppJars.
    List<Path> runtimeJars = appCp.stream().filter(jar -> !localAppJars.contains(jar)).toList();

    return new ClasspathPartition(localAppJars, runtimeJars);
  }

  /** Result of partitioning the application classpath. Package-private for testability. */
  record ClasspathPartition(List<Path> localAppJars, List<Path> runtimeJars) {}
}
