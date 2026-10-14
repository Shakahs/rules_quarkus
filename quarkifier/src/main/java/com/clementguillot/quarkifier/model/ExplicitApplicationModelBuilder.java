package com.clementguillot.quarkifier.model;

import com.clementguillot.quarkifier.model.transport.BazelApplicationModel;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.ArtifactCoordinates;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyEdge;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.DependencyScope;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.Node;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.SourceSet;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel.WorkspaceModule;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModelValidator;
import com.clementguillot.quarkifier.model.transport.BazelArtifactCoordinates;
import com.clementguillot.quarkifier.model.transport.ExtensionDescriptorReader;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.model.CapabilityContract;
import io.quarkus.bootstrap.workspace.ArtifactSources;
import io.quarkus.bootstrap.workspace.DefaultArtifactSources;
import io.quarkus.bootstrap.workspace.LazySourceDir;
import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.DependencyBuilder;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.paths.PathList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** Quarkus adapter for the validated, version-independent Bazel model transport. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports",
  "PMD.GodClass",
  "PMD.TooManyMethods"
})
public final class ExplicitApplicationModelBuilder {

  private ExplicitApplicationModelBuilder() {}

  public static ApplicationModel build(BazelApplicationModel model) throws IOException {
    return build(model, Map.of());
  }

  /**
   * Builds the model with its artifacts read from where this invocation finds them.
   *
   * @param artifactPaths each model artifact path to the file holding it here; an artifact absent
   *     from the map is read at the path the model holds
   */
  public static ApplicationModel build(BazelApplicationModel model, Map<String, Path> artifactPaths)
      throws IOException {
    return build(model, artifactPaths, DevSessionPaths.NONE);
  }

  /**
   * Builds the dev model over a dev session's class directories.
   *
   * @param artifactPaths each model artifact path to the file holding it here
   * @param devSession where the dev session holds the dev modules, and its hook jar
   */
  public static ApplicationModel build(
      BazelApplicationModel model, Map<String, Path> artifactPaths, DevSessionPaths devSession)
      throws IOException {
    BazelApplicationModelValidator.validate(model);
    var adapter = new Adapter(model, artifactPaths, devSession);
    return adapter.build();
  }

  /**
   * What a dev session changes in the model it runs.
   *
   * <p>The dev modules are read from the session's class directories rather than from Bazel's
   * outputs, which a rebuild deletes. Those paths run through the session's {@code current} link,
   * which moves to each new build, so they are kept exactly as given and never resolved to the
   * directory behind the link.
   *
   * <p>The hook jar installs the session's {@code HotReplacementSetup}. It is a runtime dependency
   * of the dev model only, so that Quarkus loads it from the base runtime class loader, the only
   * one it discovers that service in. It belongs to the launcher, not to the application's graph:
   * nothing in the application depends on it, and no other lifecycle has it.
   *
   * @param classPaths each dev module's model path to the class path the application reads it from
   * @param hookJar the jar installing the session's scan step, or {@code null} for none
   */
  public record DevSessionPaths(Map<String, Path> classPaths, Path hookJar) {

    /** No dev session: every artifact is read where the model says. */
    public static final DevSessionPaths NONE = new DevSessionPaths(Map.of(), null);

    public DevSessionPaths {
      classPaths = Map.copyOf(classPaths);
    }
  }

  /** The coordinates of the dev session's hook jar in the dev model. */
  public static final ArtifactCoords DEV_SESSION_HOOK =
      ArtifactCoords.jar("com.clementguillot.rules_quarkus", "dev-session-hook", "1");

  private static final class Adapter {
    private final BazelApplicationModel source;
    private final Map<String, Path> artifactPaths;
    private final DevSessionPaths devSession;
    private final ApplicationModelBuilder builder = new ApplicationModelBuilder();
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, ResolvedDependencyBuilder> dependencies = new LinkedHashMap<>();
    private final Map<String, DependencyScope> effectiveScopes = new HashMap<>();
    private final Map<String, io.quarkus.bootstrap.workspace.WorkspaceModule> workspaces =
        new HashMap<>();
    private final Map<String, WorkspaceModule> workspaceInputs = new HashMap<>();
    private final Set<String> runtimeExtensions = new java.util.HashSet<>();
    private final Map<String, Properties> extensionDescriptors = new HashMap<>();

    private Adapter(
        BazelApplicationModel source, Map<String, Path> artifactPaths, DevSessionPaths devSession) {
      this.source = source;
      this.artifactPaths = Map.copyOf(artifactPaths);
      this.devSession = devSession;
      for (Node node : source.nodes()) {
        nodes.put(node.id(), node);
      }
      for (WorkspaceModule workspace : source.workspaceModules()) {
        workspaceInputs.put(workspace.id(), workspace);
      }
    }

    private ApplicationModel build() throws IOException {
      calculateEffectiveScopes();
      createDependencyBuilders();
      registerExtensionMetadata();
      attachWorkspaceModules();
      attachDependencyLinks();
      configurePlatforms();
      ResolvedDependencyBuilder application = dependencies.get(source.applicationId());
      builder.setAppArtifact(application);
      for (Map.Entry<String, ResolvedDependencyBuilder> dependency : dependencies.entrySet()) {
        if (!source.applicationId().equals(dependency.getKey())) {
          builder.addDependency(dependency.getValue());
        }
      }
      if (devSession.hookJar() != null) {
        // A runtime dependency is on the deployment class path too: Quarkus's getDependencies(),
        // which CuratedApplication builds the base runtime class loader from, returns only those.
        builder.addDependency(
            ResolvedDependencyBuilder.newInstance()
                .setCoords(DEV_SESSION_HOOK)
                .setScope("runtime")
                .setRuntimeCp()
                .setDeploymentCp()
                .setResolvedPath(devSession.hookJar()));
      }
      ApplicationModel result = builder.build();
      validateBuiltModel(result);
      return result;
    }

    private void validateBuiltModel(ApplicationModel result) throws IOException {
      if (result.getAppArtifact() == null || !result.getAppArtifact().isResolved()) {
        throw invariant("application artifact is absent or unresolved");
      }
      var modelCoordinates = new java.util.HashSet<String>();
      modelCoordinates.add(result.getAppArtifact().toGACTVString());
      for (var dependency :
          result.getDependenciesWithAnyFlag(
              DependencyFlags.RUNTIME_CP
                  | DependencyFlags.DEPLOYMENT_CP
                  | DependencyFlags.COMPILE_ONLY)) {
        if (!dependency.isResolved()
            && !QuarkusModelVersionAdapter.isMissingFromApplication(dependency)) {
          throw invariant("dependency has no resolved path: " + dependency.toGACTVString());
        }
        modelCoordinates.add(dependency.toGACTVString());
        if (dependency.isReloadable() && dependency.getWorkspaceModule() == null) {
          throw invariant("reloadable dependency is not a workspace module: " + dependency);
        }
        if (dependency.isReloadable()
            && dependency.isFlagSet(DependencyFlags.RUNTIME_EXTENSION_ARTIFACT)) {
          throw invariant("runtime extension is incorrectly reloadable: " + dependency);
        }
      }
      for (var dependency : result.getDependencies()) {
        for (ArtifactCoords child : dependency.getDependencies()) {
          if (!modelCoordinates.contains(child.toGACTVString())) {
            throw invariant(
                "dependency link from " + dependency + " points outside the model: " + child);
          }
        }
      }
      for (String extensionId : runtimeExtensions) {
        Node extension = nodes.get(extensionId);
        String deployment =
            extensionDescriptors.get(extensionId).getProperty("deployment-artifact");
        ArtifactCoordinates coordinates = BazelArtifactCoordinates.parse(deployment);
        String expected =
            ArtifactCoords.of(
                    coordinates.groupId(),
                    coordinates.artifactId(),
                    coordinates.classifier(),
                    coordinates.type(),
                    coordinates.version())
                .toGACTVString();
        if (!modelCoordinates.contains(expected)) {
          throw invariant(
              "runtime extension "
                  + extension.coordinates()
                  + " has no deployment artifact "
                  + expected);
        }
      }
      if (result.getPlatforms().getImportedPlatformBoms().size()
          != source.platform().imports().size()) {
        throw invariant("platform BOM imports changed while building the Quarkus model");
      }
      if (result.getPlatforms().getPlatformReleaseInfo().size()
          != source.platform().releases().size()) {
        throw invariant("platform release metadata changed while building the Quarkus model");
      }
    }

    private static IllegalStateException invariant(String message) {
      return new IllegalStateException("Explicit ApplicationModel invariant failed: " + message);
    }

    private void createDependencyBuilders() throws IOException {
      for (Node node : source.nodes()) {
        ArtifactCoordinates coordinates = node.coordinates();
        var dependency =
            ResolvedDependencyBuilder.newInstance()
                .setGroupId(coordinates.groupId())
                .setArtifactId(coordinates.artifactId())
                .setClassifier(coordinates.classifier())
                .setType(coordinates.type())
                .setVersion(coordinates.version())
                .setScope(scope(effectiveScopes.get(node.id())))
                .setResolvedPaths(PathList.from(resolveArtifactPaths(node)));
        applyClasspathFacts(node, dependency);
        dependencies.put(node.id(), dependency);
      }
    }

    /**
     * Resolves dependency artifacts to their stable backing files in dev and test modes.
     *
     * <p>Bazel exposes external repositories and action outputs through symlinks below the mutable
     * execution root. A nested dev-mode build may replace those symlinks while Quarkus still has a
     * deployment JAR mounted as a ZIP file system. Keeping the logical exec path in the serialized
     * model then makes shutdown/reload fail with {@link java.nio.file.NoSuchFileException}.
     * Workspace and source paths intentionally do not use this policy: they are logical project
     * locations and some output directories do not exist when the model is assembled.
     *
     * <p>The model holds each artifact's path in the execution root of the build that assembled it.
     * A test runs from its runfiles tree, which on the build machine links into that execution root
     * and on a remote executor holds the files themselves, with nothing at the execution-root
     * paths. The launcher therefore says where each artifact is found, and the artifact is read
     * there.
     */
    private List<Path> resolveArtifactPaths(Node node) throws IOException {
      var resolved = new ArrayList<Path>(node.paths().size());
      for (String rawPath : node.paths()) {
        Path sessionPath = devSession.classPaths().get(rawPath);
        if (sessionPath != null) {
          resolved.add(sessionPath);
          continue;
        }
        Path path = artifactPath(rawPath);
        if (source.mode() == BazelApplicationModel.Mode.DEV
            || source.mode() == BazelApplicationModel.Mode.TEST) {
          try {
            path = path.toAbsolutePath().normalize().toRealPath();
          } catch (IOException e) {
            throw new IOException(
                "Cannot resolve stable artifact path for "
                    + node.coordinates()
                    + ": "
                    + path.toAbsolutePath().normalize(),
                e);
          }
        }
        resolved.add(path);
      }
      return resolved;
    }

    /** Where the artifact the model names by {@code rawPath} is found by this invocation. */
    private Path artifactPath(String rawPath) {
      Path found = artifactPaths.get(rawPath);
      return found == null ? Path.of(rawPath) : found;
    }

    private static void applyClasspathFacts(Node node, ResolvedDependencyBuilder dependency) {
      var facts = node.classpath();
      dependency.setDirect(facts.directFromApplication());
      dependency.setOptional(facts.optional());
      if (facts.runtimeClasspath()) {
        dependency.setRuntimeCp();
      }
      if (facts.deploymentClasspath()) {
        dependency.setDeploymentCp();
      }
      if (facts.compileOnly()) {
        dependency.setFlags(DependencyFlags.COMPILE_ONLY);
      }
    }

    private void attachWorkspaceModules() {
      for (Node node : source.nodes()) {
        if (node.workspaceModuleId() == null) {
          continue;
        }
        var workspace = workspace(node.workspaceModuleId());
        ResolvedDependencyBuilder dependency = dependencies.get(node.id());
        dependency.setWorkspaceModule(workspace);
        if (source.applicationId().equals(node.id())) {
          builder.addReloadableWorkspaceModule(dependency.getKey());
        } else if (node.classpath().reloadable()) {
          dependency.setReloadable();
          builder.addReloadableWorkspaceModule(dependency.getKey());
        }
      }
    }

    private io.quarkus.bootstrap.workspace.WorkspaceModule workspace(String id) {
      var existing = workspaces.get(id);
      if (existing != null) {
        return existing;
      }
      WorkspaceModule input = workspaceInputs.get(id);
      if (input == null) {
        throw new IllegalArgumentException("Unknown workspace module " + id);
      }
      Node node =
          nodes.values().stream()
              .filter(candidate -> id.equals(candidate.workspaceModuleId()))
              .findFirst()
              .orElseThrow();
      ArtifactCoordinates coordinates = node.coordinates();
      var mutable =
          io.quarkus.bootstrap.workspace.WorkspaceModule.builder()
              .setModuleId(
                  WorkspaceModuleId.of(
                      coordinates.groupId(), coordinates.artifactId(), coordinates.version()))
              .setModuleDir(workspacePath(input.moduleDir()))
              .setBuildDir(workspacePath(input.buildDir()))
              .setBuildFile(workspacePath(input.buildFile()))
              .setTestClasspathDependencyExclusions(
                  new ArrayList<>(
                      input.testClasspathExclusions().stream()
                          .map(key -> key.groupId() + ":" + key.artifactId())
                          .toList()))
              .setAdditionalTestClasspathElements(
                  new ArrayList<>(
                      input.additionalTestClasspathElements().stream()
                          .map(path -> workspacePath(path).toString())
                          .toList()));
      for (SourceSet sourceSet : input.sourceSets()) {
        mutable.addArtifactSources(artifactSources(sourceSet, workspacePath(input.buildDir())));
      }
      for (String dependencyId : input.directDependencyIds()) {
        Node target = nodes.get(dependencyId);
        if (target != null) {
          DependencyEdge edge =
              node.dependencies().stream()
                  .filter(candidate -> dependencyId.equals(candidate.targetId()))
                  .findFirst()
                  .orElseThrow(
                      () ->
                          invariant(
                              "workspace module "
                                  + id
                                  + " declares dependency "
                                  + dependencyId
                                  + " without a graph edge"));
          mutable.addDependency(workspaceDependency(target, edge));
        }
      }
      for (BazelApplicationModel.ArtifactKey constraint : input.dependencyConstraints()) {
        mutable.addDependencyConstraint(
            DependencyBuilder.newInstance()
                .setGroupId(constraint.groupId())
                .setArtifactId(constraint.artifactId())
                .build());
      }
      if (input.parentId() != null) {
        mutable.setParent(workspace(input.parentId()));
      }
      var result = mutable.build();
      workspaces.put(id, result);
      return result;
    }

    private DefaultArtifactSources artifactSources(SourceSet sourceSet, Path buildDir) {
      String classifier =
          sourceSet.classifier().isEmpty() ? ArtifactSources.MAIN : sourceSet.classifier();
      Path generatedSource = firstPath(sourceSet.generatedSourceDirectories());
      Path generatedResource = firstPath(sourceSet.generatedResourceDirectories());
      Path outputDir =
          sourceSet.outputDirectories().isEmpty()
              ? buildDir
              : workspacePath(sourceSet.outputDirectories().get(0));
      List<SourceDir> sources =
          new ArrayList<>(
              sourceSet.sourceDirectories().stream()
                  .<SourceDir>map(
                      path -> sourceDir(workspacePath(path), outputDir, generatedSource))
                  .toList());
      List<SourceDir> resources =
          new ArrayList<>(
              sourceSet.resourceDirectories().stream()
                  .<SourceDir>map(
                      path -> sourceDir(workspacePath(path), outputDir, generatedResource))
                  .toList());
      return new DefaultArtifactSources(classifier, sources, resources);
    }

    private static SourceDir sourceDir(Path source, Path output, Path generated) {
      return new LazySourceDir(source, output, generated, Collections.emptyMap());
    }

    private Path firstPath(List<String> paths) {
      return paths.isEmpty() ? null : workspacePath(paths.get(0));
    }

    /**
     * A workspace module's path: a logical project location, kept as the model holds it, made
     * absolute in dev and test. A path the invocation locates (an output directory it has in its
     * runfiles) is the file found there, resolved as the classpath is: Quarkus compares a test
     * class's classpath root with its module's output directory by path equality.
     */
    private Path workspacePath(String rawPath) {
      Path sessionPath = devSession.classPaths().get(rawPath);
      if (sessionPath != null) {
        return sessionPath;
      }
      Path found = artifactPaths.get(rawPath);
      if (found != null) {
        try {
          return found.toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
          return found.toAbsolutePath().normalize();
        }
      }
      Path path = Path.of(rawPath);
      return source.mode() == BazelApplicationModel.Mode.DEV
              || source.mode() == BazelApplicationModel.Mode.TEST
          ? path.toAbsolutePath().normalize()
          : path;
    }

    private void attachDependencyLinks() {
      for (Node node : source.nodes()) {
        ResolvedDependencyBuilder dependency = dependencies.get(node.id());
        List<ArtifactCoords> plain = new ArrayList<>();
        List<io.quarkus.maven.dependency.Dependency> direct = new ArrayList<>();
        for (DependencyEdge edge : node.dependencies()) {
          Node target = nodes.get(edge.targetId());
          plain.add(toQuarkusCoordinates(target.coordinates()));
          direct.add(directDependency(target, edge));
        }
        dependency.setDependencies(plain);
        QuarkusModelVersionAdapter.setDirectDependencies(dependency, direct);
      }
    }

    private io.quarkus.maven.dependency.Dependency directDependency(
        Node target, DependencyEdge edge) {
      ArtifactCoordinates coordinates = target.coordinates();
      var direct =
          DependencyBuilder.newInstance()
              .setGroupId(coordinates.groupId())
              .setArtifactId(coordinates.artifactId())
              .setClassifier(coordinates.classifier())
              .setType(coordinates.type())
              .setVersion(coordinates.version())
              .setScope(scope(edge.scope()))
              .setFlags(flags(target) | DependencyFlags.DIRECT)
              .setOptional(edge.optional());
      for (BazelApplicationModel.ArtifactKey exclusion : edge.exclusions()) {
        direct.addExclusion(exclusion.groupId(), exclusion.artifactId());
      }
      return direct.build();
    }

    private static io.quarkus.maven.dependency.Dependency workspaceDependency(
        Node target, DependencyEdge edge) {
      ArtifactCoordinates coordinates = target.coordinates();
      var dependency =
          DependencyBuilder.newInstance()
              .setGroupId(coordinates.groupId())
              .setArtifactId(coordinates.artifactId())
              .setClassifier(coordinates.classifier())
              .setType(coordinates.type())
              .setVersion(coordinates.version())
              .setScope(scope(edge.scope()))
              .setOptional(edge.optional());
      for (BazelApplicationModel.ArtifactKey exclusion : edge.exclusions()) {
        dependency.addExclusion(exclusion.groupId(), exclusion.artifactId());
      }
      return dependency.build();
    }

    private static String scope(DependencyScope scope) {
      return switch (scope) {
        case COMPILE -> "compile";
        case RUNTIME -> "runtime";
        case PROVIDED -> "provided";
        case TEST -> "test";
      };
    }

    private void calculateEffectiveScopes() {
      effectiveScopes.put(source.applicationId(), DependencyScope.COMPILE);
      calculateRuntimeGraphScopes();
      calculateAugmentedGraphScopes();
      if (effectiveScopes.size() != nodes.size()) {
        var missing = new java.util.TreeSet<>(nodes.keySet());
        missing.removeAll(effectiveScopes.keySet());
        throw invariant("effective scope calculation did not reach " + missing);
      }
    }

    /**
     * Resolves the application runtime graph before deployment injection, matching Quarkus' Maven
     * resolver phase ordering. rules_jvm_external exposes the union of resolved runtime paths, so a
     * compile-visible path must dominate test/runtime-only paths to the same artifact.
     */
    private void calculateRuntimeGraphScopes() {
      Deque<String> pending = new ArrayDeque<>();
      pending.add(source.applicationId());
      while (!pending.isEmpty()) {
        String parentId = pending.removeFirst();
        DependencyScope parentScope = effectiveScopes.get(parentId);
        for (DependencyEdge edge : nodes.get(parentId).dependencies()) {
          Node target = nodes.get(edge.targetId());
          if (!target.classpath().runtimeClasspath()) {
            continue;
          }
          DependencyScope candidate = inheritedScope(parentScope, edge.scope());
          DependencyScope existing = effectiveScopes.get(edge.targetId());
          if (existing == null || scopeRank(candidate) < scopeRank(existing)) {
            effectiveScopes.put(edge.targetId(), candidate);
            pending.addLast(edge.targetId());
          }
        }
      }
    }

    /** Adds deployment/compile-only nodes without changing scopes fixed by the runtime phase. */
    private void calculateAugmentedGraphScopes() {
      Deque<String> pending = new ArrayDeque<>();
      Set<String> visited = new java.util.HashSet<>();
      pending.add(source.applicationId());
      while (!pending.isEmpty()) {
        String parentId = pending.removeFirst();
        if (!visited.add(parentId)) {
          continue;
        }
        DependencyScope parentScope = effectiveScopes.get(parentId);
        for (DependencyEdge edge : nodes.get(parentId).dependencies()) {
          effectiveScopes.putIfAbsent(edge.targetId(), inheritedScope(parentScope, edge.scope()));
          pending.addLast(edge.targetId());
        }
      }
    }

    private static DependencyScope inheritedScope(DependencyScope parent, DependencyScope edge) {
      if (parent == DependencyScope.TEST || edge == DependencyScope.TEST) {
        return DependencyScope.TEST;
      }
      if (parent == DependencyScope.PROVIDED || edge == DependencyScope.PROVIDED) {
        return DependencyScope.PROVIDED;
      }
      if (parent == DependencyScope.RUNTIME || edge == DependencyScope.RUNTIME) {
        return DependencyScope.RUNTIME;
      }
      return DependencyScope.COMPILE;
    }

    private static int scopeRank(DependencyScope scope) {
      return switch (scope) {
        case COMPILE -> 0;
        case PROVIDED -> 1;
        case RUNTIME -> 2;
        case TEST -> 3;
      };
    }

    private int flags(Node node) {
      int result = 0;
      var facts = node.classpath();
      if (facts.directFromApplication()) {
        result |= DependencyFlags.DIRECT;
      }
      if (facts.runtimeClasspath()) {
        result |= DependencyFlags.RUNTIME_CP;
      }
      if (facts.deploymentClasspath()) {
        result |= DependencyFlags.DEPLOYMENT_CP;
      }
      if (facts.compileOnly()) {
        result |= DependencyFlags.COMPILE_ONLY;
      }
      if (facts.optional()) {
        result |= DependencyFlags.OPTIONAL;
      }
      if (node.workspaceModuleId() != null) {
        result |= DependencyFlags.WORKSPACE_MODULE;
      }
      if (runtimeExtensions.contains(node.id())) {
        result |= DependencyFlags.RUNTIME_EXTENSION_ARTIFACT;
        if (facts.topLevelRuntimeExtension()) {
          result |= DependencyFlags.TOP_LEVEL_RUNTIME_EXTENSION_ARTIFACT;
        }
      }
      return result;
    }

    private void registerExtensionMetadata() throws IOException {
      for (Node node : source.nodes()) {
        Optional<Properties> descriptor = descriptor(node);
        if (descriptor.isEmpty()) {
          continue;
        }
        Properties props = descriptor.orElseThrow();
        ResolvedDependencyBuilder dependency = dependencies.get(node.id());
        runtimeExtensions.add(node.id());
        extensionDescriptors.put(node.id(), props);
        dependency.setRuntimeExtensionArtifact();
        if (node.classpath().topLevelRuntimeExtension()) {
          dependency.setFlags(DependencyFlags.TOP_LEVEL_RUNTIME_EXTENSION_ARTIFACT);
        }
        builder.handleExtensionProperties(props, dependency.getKey());
        registerCapabilities(props, dependency);
      }
    }

    private Optional<Properties> descriptor(Node node) throws IOException {
      for (String rawPath : node.paths()) {
        if (!rawPath.endsWith(".jar")) {
          continue;
        }
        Optional<Properties> result = ExtensionDescriptorReader.readFromJar(artifactPath(rawPath));
        if (result.isPresent()) {
          return result;
        }
      }
      return Optional.empty();
    }

    private void registerCapabilities(Properties descriptor, ResolvedDependencyBuilder dependency) {
      String provides = descriptor.getProperty("provides-capabilities");
      String requires = descriptor.getProperty("requires-capabilities");
      if (provides != null || requires != null) {
        builder.addExtensionCapabilities(
            CapabilityContract.of(compactCoordinates(dependency), provides, requires));
      }
    }

    private static String compactCoordinates(ResolvedDependencyBuilder dependency) {
      var result = new StringBuilder();
      result
          .append(dependency.getGroupId())
          .append(':')
          .append(dependency.getArtifactId())
          .append(':');
      if (!dependency.getClassifier().isEmpty()) {
        result.append(dependency.getClassifier()).append(':');
      }
      if (!"jar".equals(dependency.getType())) {
        result.append(dependency.getType()).append(':');
      }
      return result.append(dependency.getVersion()).toString();
    }

    private void configurePlatforms() throws IOException {
      builder.setPlatformImports(QuarkusModelVersionAdapter.platformImports(source.platform()));
    }

    private static ArtifactCoords toQuarkusCoordinates(ArtifactCoordinates coordinates) {
      return ArtifactCoords.of(
          coordinates.groupId(),
          coordinates.artifactId(),
          coordinates.classifier(),
          coordinates.type(),
          coordinates.version());
    }
  }
}
