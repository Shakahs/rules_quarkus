"""Bzlmod module extension for configuring the Quarkus toolchain.

Scans the exact runtime jars pinned by maven_install.json for Quarkus extension
descriptors and materializes their declared build-time artifacts from that
same lock with Bazel's checksum-verified Maven downloader.

Produces a single generated repository (@rules_quarkus) containing:
  - quarkus/defs.bzl: public API macros (quarkus_app, quarkus_test, quarkus_integration_test)
  - deployment/: Maven-locked deployment jars
"""

load("//quarkus/private:versions.bzl", "MAVEN_CENTRAL", "SUPPORTED_VERSIONS")

# ---- Version helpers ----

def _extract_minor_version(version):
    """Extracts the minor version (e.g. "3.27") from a full version string."""
    parts = version.split(".")
    if len(parts) < 2:
        fail("Invalid Quarkus version '{}': expected MAJOR.MINOR.PATCH format".format(version))
    return parts[0] + "." + parts[1]

def _validate_version(version):
    """Validates a full Quarkus version against SUPPORTED_VERSIONS.

    The quarkifier is bytecode-coupled to exact patch versions, so the full
    version must match the supported patch for its minor exactly.

    Returns:
        The minor version string (e.g. "3.27").
    """
    minor = _extract_minor_version(version)
    supported_list = ", ".join([SUPPORTED_VERSIONS[m] for m in sorted(SUPPORTED_VERSIONS)])
    expected = SUPPORTED_VERSIONS.get(minor)
    if not expected:
        fail("Unsupported Quarkus minor version '{}' (from version '{}'). Supported versions: {}".format(
            minor,
            version,
            supported_list,
        ))
    if version != expected:
        fail("Unsupported Quarkus version '{}': the quarkifier is coupled to exact patch versions. Supported versions: {}".format(
            version,
            supported_list,
        ))
    return minor

def _sanitize_version(minor_version):
    """Replaces dots with underscores for use in Bazel target and repo names."""
    return minor_version.replace(".", "_")

def _maven_target_name(coordinate_key):
    """Matches rules_jvm_external's versionless target-name escaping."""
    parts = coordinate_key.split(":")

    # rules_jvm_external omits the default `jar` packaging segment when it
    # constructs a classifier target (G:A:jar:C -> G_A_C).
    target_key = ":".join([parts[0], parts[1], parts[3]]) if len(parts) == 4 and parts[2] == "jar" else coordinate_key
    return target_key.replace(".", "_").replace("-", "_").replace(":", "_").replace("$", "_")

def _coordinate_fields(coordinate_key, version):
    """Converts a rules_jvm_external versionless coordinate to GACTV fields."""
    parts = coordinate_key.split(":")
    if len(parts) < 2 or len(parts) > 4:
        fail("Unsupported artifact coordinate '{}' in maven lock file".format(coordinate_key))
    return {
        "artifactId": parts[1],
        "classifier": parts[3] if len(parts) == 4 else "",
        "groupId": parts[0],
        "type": parts[2] if len(parts) >= 3 else "jar",
        "version": version,
    }

def _lock_coordinate_keys(lock_data, dependencies):
    """Returns every resolved coordinate identity present in a v3 lock."""
    coordinate_keys = {}
    for dependency_key, direct_dependencies in dependencies.items():
        coordinate_keys[dependency_key] = True
        if type(direct_dependencies) != "list":
            fail("Invalid dependency list for '{}' in maven lock file".format(dependency_key))
        for coordinate_key in direct_dependencies:
            coordinate_keys[coordinate_key] = True
    packages = lock_data.get("packages", {})
    if type(packages) != "dict":
        fail("Invalid rules_jvm_external v3 lock: 'packages' must be an object")
    for coordinate_key in packages:
        coordinate_keys[coordinate_key] = True
    return coordinate_keys

def _resolved_coordinate_keys(artifact_key, artifact, dependency_coordinate_keys):
    """Returns every concrete lock key represented by an artifact entry.

    rules_jvm_external v3 keys `artifacts` by G:A even when the same artifact
    resolves more than one file (for example default and `runtime` classifier
    JARs). The dependency graph retains the concrete identities, including
    leaf artifacts which appear only as dependency values, so it is
    authoritative whenever it contains matching entries.
    """
    if len(artifact_key.split(":")) > 2:
        prefix = artifact_key + ":"
        concrete_keys = [
            key
            for key in dependency_coordinate_keys
            if key == artifact_key or key.startswith(prefix)
        ]
        return sorted(concrete_keys) if concrete_keys else [artifact_key]

    prefix = artifact_key + ":"
    graph_keys = [
        key
        for key in dependency_coordinate_keys
        if key == artifact_key or key.startswith(prefix)
    ]
    if graph_keys:
        return sorted(graph_keys)

    shasums = artifact.get("shasums", {})
    if type(shasums) != "dict" or len(shasums) != 1:
        fail("Artifact '{}' has ambiguous resolved files but no concrete v3 dependency keys".format(artifact_key))
    file_kind = shasums.keys()[0]
    return [artifact_key if file_kind == "jar" else artifact_key + ":jar:" + file_kind]

def _runtime_catalog(lock_data):
    """Normalizes the graph selected by the authoritative Maven lock."""
    lock_version = str(lock_data.get("version", ""))
    if lock_version != "3":
        fail("Unsupported rules_jvm_external lock version '{}'; application-model fidelity requires v3".format(lock_version))

    artifacts = lock_data.get("artifacts", {})
    dependencies = lock_data.get("dependencies", {})
    if type(artifacts) != "dict" or type(dependencies) != "dict":
        fail("Invalid rules_jvm_external v3 lock: 'artifacts' and 'dependencies' must be objects")
    input_artifacts = lock_data.get("__INPUT_ARTIFACTS_HASH", {})
    if type(input_artifacts) != "dict":
        fail("Invalid rules_jvm_external v3 lock: '__INPUT_ARTIFACTS_HASH' must be an object")
    dependency_coordinate_keys = _lock_coordinate_keys(lock_data, dependencies)

    nodes_by_key = {}
    base_to_resolved = {}
    for artifact_key in sorted(artifacts):
        artifact = artifacts[artifact_key]
        if type(artifact) != "dict" or not artifact.get("version"):
            fail("Invalid artifact entry '{}' in maven lock file".format(artifact_key))
        coordinate_keys = _resolved_coordinate_keys(artifact_key, artifact, dependency_coordinate_keys)
        base_to_resolved[artifact_key] = artifact_key if artifact_key in coordinate_keys else coordinate_keys[0]
        for coordinate_key in coordinate_keys:
            base_to_resolved[coordinate_key] = coordinate_key
            nodes_by_key[coordinate_key] = {
                "coordinateKey": coordinate_key,
                "coordinates": _coordinate_fields(coordinate_key, artifact["version"]),
                "dependencies": [],
                "exclusions": [],
                "optional": False,
                "targetName": _maven_target_name(coordinate_key),
            }

    nodes = [nodes_by_key[key] for key in sorted(nodes_by_key)]

    for node in nodes:
        direct_dependencies = dependencies.get(node["coordinateKey"], [])
        if type(direct_dependencies) != "list":
            fail("Invalid dependency list for '{}' in maven lock file".format(node["coordinateKey"]))
        node["dependencies"] = sorted([base_to_resolved.get(dep, dep) for dep in direct_dependencies])

    conflicts = lock_data.get("conflict_resolution", {})
    if type(conflicts) != "dict":
        fail("Invalid rules_jvm_external v3 lock: 'conflict_resolution' must be an object")
    ordered_conflicts = {key: conflicts[key] for key in sorted(conflicts)}
    direct_artifacts = [
        base_to_resolved[key]
        for key in sorted(input_artifacts)
        # The input signature also contains repositories and BOMs. Neither is
        # a resolved runtime artifact; resolved artifacts always have an entry
        # in the v3 lock's `artifacts` object.
        if key in base_to_resolved
    ]
    return {
        "conflictResolution": ordered_conflicts,
        "directArtifacts": direct_artifacts,
        "nodes": nodes,
        "schemaVersion": "quarkus-bazel-runtime-catalog-v1",
    }

def _platform_bom(coordinate):
    """Parses the public G:A:V platform BOM notation into transport coordinates."""
    parts = coordinate.split(":")
    if len(parts) != 3 or not parts[0] or not parts[1] or not parts[2]:
        fail("Invalid platform BOM '{}': expected groupId:artifactId:version".format(coordinate))
    return {
        "artifactId": parts[1],
        "classifier": "",
        "groupId": parts[0],
        "type": "pom",
        "version": parts[2],
    }

def _write_platform_catalog(rctx):
    """Downloads exact Quarkus platform properties and writes their model catalog."""
    imports = []
    property_files = []
    for coordinate in rctx.attr.platform_boms:
        bom = _platform_bom(coordinate)
        properties_artifact = bom["artifactId"] + "-quarkus-platform-properties"
        relative_path = "{group}/{artifact}/{version}/{artifact}-{version}.properties".format(
            artifact = properties_artifact,
            group = bom["groupId"].replace(".", "/"),
            version = bom["version"],
        )
        repo_path = "model/platform-properties/" + relative_path
        rctx.report_progress("Resolving Quarkus platform properties for " + coordinate)
        rctx.download(
            url = [repository.removesuffix("/") + "/" + relative_path for repository in rctx.attr.artifact_repositories],
            output = repo_path,
        )
        imports.append(bom)
        property_files.append(repo_path)

    catalog = {
        "imports": imports,
        "properties": {key: rctx.attr.platform_properties[key] for key in sorted(rctx.attr.platform_properties)},
        "propertyFiles": property_files,
        "schemaVersion": "quarkus-bazel-platform-catalog-v1",
    }
    rctx.file("model/platform-catalog-v1.json", json.encode(catalog) + "\n")

# Pure helpers exported only for Starlark unit tests. Production consumers use
# the generated catalog file targets, never these implementation functions.
runtime_catalog_for_test = _runtime_catalog
maven_target_name_for_test = _maven_target_name

def _maven_relative_path(jar_path):
    """Returns the jar path relative to its Maven repository root.

    Preserving the Maven directory structure (group/artifact/version/file) in
    the copied deployment jars is required for Dev UI version extraction.
    Download paths may contain a "maven2" component; everything after it is
    the Maven-layout path. Falls back to the bare file name.
    """
    parts = jar_path.replace("\\", "/").split("/")
    if "maven2" in parts:
        maven2_idx = parts.index("maven2")
        if maven2_idx + 1 < len(parts):
            return "/".join(parts[maven2_idx + 1:])
    return parts[-1]

def _jar_target_name(relative_jar_path):
    """Derives the java_import target name from the Maven-relative jar path.

    Preserves the historical GAV target name for an ordinary jar and appends
    the concrete file suffix for classifiers so variants cannot collide.
    """
    parts = relative_jar_path.split("/")
    if len(parts) < 3:
        base = relative_jar_path.removesuffix(".jar")
    else:
        base = "/".join(parts[:-1])
        artifact_id = parts[-3]
        version = parts[-2]
        file_stem = parts[-1].removesuffix(".jar")
        default_stem = artifact_id + "-" + version
        if file_stem != default_stem:
            suffix = file_stem.removeprefix(default_stem + "-")
            base += "/" + suffix
    return base.replace("/", "_").replace(".", "_").replace("-", "_")

jar_target_name_for_test = _jar_target_name

def _copy_jars_into_repo(rctx, copies):
    """Copies resolved jars into the repository directory.

    The repository must own its files: symlinks into an external downloader
    cache can dangle when the cache is cleaned (with no repository
    invalidation to recover), and later cache mutations change action inputs
    underneath Bazel — a remote-cache poisoning vector. Copying snapshots the
    checksum-verified Maven download into the generated repository.

    Args:
        rctx: Repository context.
        copies: List of (source path, repo-relative destination) tuples.
    """
    if not copies:
        return
    rctx.report_progress("Copying deployment jars into the repository")

    # One mkdir for all parent directories (small argv), then one cp per jar
    # (batching all jars into a single argv could exceed the kernel limits).
    dest_dirs = {dest.rsplit("/", 1)[0]: True for _, dest in copies}
    result = rctx.execute(["mkdir", "-p"] + list(dest_dirs.keys()))
    if result.return_code != 0:
        fail("Failed to create deployment jar directories: " + result.stderr)
    for src, dest in copies:
        result = rctx.execute(["cp", src, dest])
        if result.return_code != 0:
            fail("Failed to copy deployment jar {} to {}: {}".format(src, dest, result.stderr))

def _write_jar_build(rctx, subdir, all_jars, core_jar_set = None, extra_artifacts = []):
    """Copies resolved jars into subdir/ and writes a BUILD file with java_import targets.

    Args:
        rctx: Repository context.
        subdir: Destination subdirectory (e.g. "deployment", "conditional").
        all_jars: Iterable of absolute jar paths to materialize.
        core_jar_set: Optional dict of jar paths that form the "core" subset.
            When provided an extra java_library(name = "core") is emitted.
        extra_artifacts: Non-JAR resolved artifacts to copy and expose through
            the generated artifacts filegroup.

    Returns:
        Dict mapping original jar path → repo-relative path.
    """
    imports = []
    all_targets = []
    core_targets = []
    copies = []
    repo_paths = {}
    seen = {}
    for jar_path in all_jars:
        relative_jar_path = _maven_relative_path(jar_path)
        target_name = _jar_target_name(relative_jar_path)
        jar_repo_path = "jars/" + relative_jar_path

        if target_name in seen:
            seen[target_name] += 1
            n = seen[target_name]

            # buildifier: disable=print
            print(("WARNING: rules_quarkus: {} jars collide on target name '{}' " +
                   "({}); keeping both, this one as '{}_dup{}'.").format(
                subdir,
                target_name,
                jar_path,
                target_name,
                n,
            ))
            target_name = "{}_dup{}".format(target_name, n)
            jar_repo_path = "jars/dup{}/{}".format(n, relative_jar_path)
        else:
            seen[target_name] = 1

        copies.append((jar_path, subdir + "/" + jar_repo_path))
        repo_paths[jar_path] = subdir + "/" + jar_repo_path

        imports.append(
            'java_import(name = "{n}", jars = ["{j}"], visibility = ["//visibility:public"])'.format(n = target_name, j = jar_repo_path),
        )
        all_targets.append('":{}"'.format(target_name))
        if core_jar_set and jar_path in core_jar_set:
            core_targets.append('":{}"'.format(target_name))

    artifact_files = []
    seen_artifact_paths = {}
    for artifact_path in extra_artifacts:
        relative_path = _maven_relative_path(artifact_path)
        repo_path = "artifacts/" + relative_path
        if relative_path in seen_artifact_paths:
            seen_artifact_paths[relative_path] += 1
            n = seen_artifact_paths[relative_path]

            # buildifier: disable=print
            print(("WARNING: rules_quarkus: {} artifacts collide on repository path '{}' " +
                   "({}); keeping both, this one under 'dup{}'.").format(
                subdir,
                relative_path,
                artifact_path,
                n,
            ))
            repo_path = "artifacts/dup{}/{}".format(n, relative_path)
        else:
            seen_artifact_paths[relative_path] = 1
        copies.append((artifact_path, subdir + "/" + repo_path))
        repo_paths[artifact_path] = subdir + "/" + repo_path
        artifact_files.append('":{}"'.format(repo_path))

    _copy_jars_into_repo(rctx, copies)

    libraries = [
        'java_library(name = "all", exports = [{}])'.format(", ".join(all_targets)),
        'filegroup(name = "artifacts", srcs = [{}])'.format(", ".join(artifact_files)),
    ]
    if core_jar_set != None:
        libraries.insert(0, 'java_library(name = "core", exports = [{}])'.format(", ".join(core_targets)))

    rctx.file(subdir + "/BUILD.bazel", content = """\
load("@rules_java//java:java_import.bzl", "java_import")
load("@rules_java//java:java_library.bzl", "java_library")
package(default_visibility = ["//visibility:public"])
{imports}
{libraries}
""".format(
        imports = "\n".join(imports),
        libraries = "\n".join(libraries),
    ))
    return repo_paths

def _write_deployment_build(rctx, all_jars, core_jar_set, extra_artifacts = []):
    """Copies resolved jars into deployment/ and writes its BUILD file."""
    return _write_jar_build(rctx, "deployment", all_jars, core_jar_set, extra_artifacts)

def _write_conditional_build(rctx, all_jars):
    """Materializes conditional candidates without placing them on the public runtime graph."""
    return _write_jar_build(rctx, "conditional", all_jars)

# ---- Generated @rules_quarkus//quarkus:defs.bzl ----

_DEFS_BZL_TEMPLATE = """\
\"\"\"Public API — load Quarkus application, test, and extension macros from here.

    load(
        "@rules_quarkus//quarkus:defs.bzl",
        "quarkus_app",
        "quarkus_codegen",
        "quarkus_extension_runtime",
        "quarkus_integration_test",
        "quarkus_java_library",
        "quarkus_test",
    )

quarkus_app() automatically creates a <name>_dev target for Quarkus dev mode
with hot-reload support. Use dev=False to opt out.
Use native=True to create a <name>_native target for GraalVM native image compilation.

quarkus_extension_runtime() wraps a local Quarkus extension runtime module;
depend on that runtime target from application code and the deployment side is
added to Quarkus augmentation automatically.
\"\"\"
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_app_impl.bzl", "quarkus_app_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_codegen_impl.bzl", "quarkus_codegen_root_rule", "quarkus_codegen_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_dev_impl.bzl", "quarkus_dev_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_extension_impl.bzl", "quarkus_extension_runtime_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_native_app_impl.bzl", "quarkus_native_app_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_native_container_app_impl.bzl", "quarkus_native_container_app_rule")
load("@com_clementguillot_rules_quarkus//quarkus/private:quarkus_test_impl.bzl", _quarkus_integration_test = "quarkus_integration_test", _quarkus_test = "quarkus_test")
load("@com_clementguillot_rules_quarkus//quarkus/private:versions.bzl", "DEFAULT_NATIVE_BUILDER_IMAGE")
load("@rules_java//java:java_library.bzl", "java_library")

_QUARKUS_VERSION = "{version}"
_QUARKIFIER_TOOL = "@com_clementguillot_rules_quarkus//quarkifier:quarkifier_{minor}_deploy.jar"
_DEPLOYMENT_DEPS = "@rules_quarkus//deployment:all"
_DEPLOYMENT_ARTIFACTS = "@rules_quarkus//deployment:artifacts"
_CORE_DEPLOYMENT_DEPS = "@rules_quarkus//deployment:core"
_CONDITIONAL_DEPS = "@rules_quarkus//conditional:all"
_CONDITIONAL_CATALOG = "@rules_quarkus//model:conditional-catalog-v1.json"
_DEPLOYMENT_CATALOG = "@rules_quarkus//model:deployment-catalog-v1.json"
_RUNTIME_CATALOG = "@rules_quarkus//model:runtime-catalog-v1.json"
_PLATFORM_CATALOG = "@rules_quarkus//model:platform-catalog-v1.json"
_PLATFORM_PROPERTIES = "@rules_quarkus//model:platform_properties"
_TEST_INFRASTRUCTURE_DEPS = [
    "@maven//:org_hamcrest_hamcrest",
    "@maven//:org_junit_jupiter_junit_jupiter",
    "@maven//:org_junit_jupiter_junit_jupiter_api",
    "@maven//:org_junit_platform_junit_platform_console_standalone",
    "@maven//:org_junit_platform_junit_platform_launcher",
]

# Digest-pinned so the native-image toolchain is part of the action key
# (a mutable tag lets the same cache key cover different GraalVM versions).
_DEFAULT_BUILDER_IMAGE = DEFAULT_NATIVE_BUILDER_IMAGE

def _declare_quarkus_codegen(name, srcs, deps, exports, runtime_deps, resources, source_roots, mode,
                             build_properties, resource_strip_prefix, application_name,
                             target_kwargs):
    if mode not in ("main", "test"):
        fail("quarkus_codegen mode must be 'main' or 'test', got '{{}}'".format(mode))
    roots = source_roots
    if roots == None:
        roots = ["src/test"] if mode == "test" else ["src/main"]
    target_kwargs = dict(target_kwargs)
    if "testonly" not in target_kwargs:
        target_kwargs["testonly"] = mode == "test"

    # Common attributes that must govern the hidden targets exactly as they
    # govern the library: otherwise `bazel build //...` still runs the
    # generation action on a platform the author excluded, or on a target they
    # marked manual.
    root_kwargs = {{
        key: target_kwargs[key]
        for key in ("exec_compatible_with", "exec_properties", "tags", "target_compatible_with", "testonly")
        if key in target_kwargs
    }}

    root_name = name + "_application_root"
    quarkus_codegen_root_rule(
        name = root_name,
        srcs = srcs,
        resources = resources,
        resource_strip_prefix = resource_strip_prefix,
        deps = deps,
        exports = exports,
        runtime_deps = runtime_deps,
        **root_kwargs
    )
    quarkus_codegen_rule(
        name = name,
        application_name = application_name,
        build_properties = build_properties,
        conditional_deps = _CONDITIONAL_DEPS,
        conditional_catalog = _CONDITIONAL_CATALOG,
        deployment_catalog = _DEPLOYMENT_CATALOG,
        deployment_artifacts = _DEPLOYMENT_ARTIFACTS,
        deployment_deps = _DEPLOYMENT_DEPS,
        deps = [":" + root_name],
        mode = mode,
        platform_catalog = _PLATFORM_CATALOG,
        platform_properties = _PLATFORM_PROPERTIES,
        quarkifier_tool = _QUARKIFIER_TOOL,
        quarkus_version = _QUARKUS_VERSION,
        resources = resources,
        runtime_catalog = _RUNTIME_CATALOG,
        source_roots = roots,
        srcs = srcs,
        **target_kwargs
    )

def quarkus_codegen(name, srcs, deps, resources = [], source_roots = None, mode = "main",
                    build_properties = {{}}, resource_strip_prefix = "", exports = [],
                    runtime_deps = [], **kwargs):
    \"\"\"Runs extension-provided Quarkus code generators and emits a Java source jar.

    The generated target can be listed directly in java_library.srcs.
    \"\"\"
    _declare_quarkus_codegen(
        name = name,
        application_name = name,
        build_properties = build_properties,
        deps = deps,
        exports = exports,
        mode = mode,
        resource_strip_prefix = resource_strip_prefix,
        resources = resources,
        source_roots = source_roots,
        srcs = srcs,
        runtime_deps = runtime_deps,
        target_kwargs = kwargs,
    )

def quarkus_java_library(name, srcs = [], resources = [], deps = [], codegen_srcs = None,
                         codegen_source_roots = None, codegen_mode = "main",
                         codegen_build_properties = {{}}, resource_strip_prefix = "",
                         exports = [], runtime_deps = [], codegen = False, **kwargs):
    \"\"\"Creates a java_library with optional Quarkus-generated Java sources.\"\"\"

    # java_library rejects `deps` without `srcs` ("deps not allowed without
    # srcs; move to runtime_deps?") instead of silently dropping them. An
    # explicitly empty codegen_srcs is the same class of mistake: it is what a
    # glob produces once its directory is renamed, and Bazel 7 still defaults
    # glob() to allow_empty = True. Without this the target would quietly
    # downgrade to a plain java_library and fail much later, in compilation of
    # the handwritten sources that expected generated types.
    if codegen_srcs != None and not codegen_srcs and not codegen:
        fail("codegen_srcs is empty; use codegen = True for dependency-only generation?")
    codegen_srcs = codegen_srcs or []
    all_srcs = list(srcs)
    java_kwargs = dict(kwargs)
    has_codegen = codegen or bool(codegen_srcs)
    if has_codegen and codegen_mode == "test" and "testonly" not in java_kwargs:
        java_kwargs["testonly"] = True
    if resource_strip_prefix:
        java_kwargs["resource_strip_prefix"] = resource_strip_prefix
    if has_codegen:
        codegen_name = name + "_quarkus_codegen"
        codegen_kwargs = {{"testonly": java_kwargs.get("testonly", False)}}
        for attribute in ("exec_compatible_with", "exec_properties", "tags", "target_compatible_with"):
            if attribute in java_kwargs:
                codegen_kwargs[attribute] = java_kwargs[attribute]
        _declare_quarkus_codegen(
            name = codegen_name,
            # The model's application artifact must name the library being
            # built, not the generated helper target.
            application_name = name,
            build_properties = codegen_build_properties,
            deps = deps,
            exports = exports,
            mode = codegen_mode,
            resource_strip_prefix = resource_strip_prefix,
            resources = resources,
            source_roots = codegen_source_roots,
            srcs = codegen_srcs,
            runtime_deps = runtime_deps,
            target_kwargs = codegen_kwargs,
        )
        all_srcs.append(":" + codegen_name)
    java_library(
        name = name,
        srcs = all_srcs,
        resources = resources,
        deps = deps,
        exports = exports,
        runtime_deps = runtime_deps,
        **java_kwargs
    )

def quarkus_app(name, dev = True, dev_build_args = [], native = False, native_container_build = False,
                native_container_runtime = "auto", native_builder_image = _DEFAULT_BUILDER_IMAGE,
                package_type = "fast-jar", build_properties = {{}}, **kwargs):
    \"\"\"Builds a Quarkus application with optional dev-mode and native targets.

    Creates:
      - <name>: production JVM package (bazel run //pkg:<name>)
      - <name>_dev: dev mode with hot-reload (bazel run //pkg:<name>_dev), unless dev=False
      - <name>_native: native binary (bazel run //pkg:<name>_native), if native=True or native_container_build=True

    Args:
        name: Target name.
        dev: If True (default), also creates a <name>_dev target for dev mode.
        dev_build_args: Extra flags for the hot-reload `bazel build` (e.g. ["--config=dev"]).
            Must match the flags you pass to `bazel run` for the dev target, otherwise
            rebuilt classes land in a different output tree and hot-reload syncs stale files.
        native: If True, creates a <name>_native target using rules_graalvm (host compilation).
        native_container_build: If True, creates a <name>_native target using Docker/Podman (container compilation).
        native_container_runtime: Container runtime: 'auto' (default), 'docker', or 'podman'.
        native_builder_image: Builder image for container native compilation.
        package_type: JVM output: fast-jar, uber-jar, mutable-jar, legacy-jar, or aot-jar.
            aot-jar requires Quarkus 3.33 or newer.
        build_properties: Declared build-time properties shared by the JVM, dev, and native targets.
        **kwargs: Passed to the underlying quarkus_app_rule (deps, version, jvm_flags, etc.).
    \"\"\"
    if native and native_container_build:
        fail("Cannot set both 'native' and 'native_container_build'. " +
             "Use 'native' for host-based compilation (rules_graalvm) or " +
             "'native_container_build' for container-based compilation (Docker/Podman).")

    quarkus_app_rule(
        name = name,
        build_properties = build_properties,
        package_type = package_type,
        quarkus_version = _QUARKUS_VERSION,
        quarkifier_tool = _QUARKIFIER_TOOL,
        deployment_deps = _DEPLOYMENT_DEPS,
        deployment_artifacts = _DEPLOYMENT_ARTIFACTS,
        conditional_deps = _CONDITIONAL_DEPS,
        conditional_catalog = _CONDITIONAL_CATALOG,
        deployment_catalog = _DEPLOYMENT_CATALOG,
        platform_catalog = _PLATFORM_CATALOG,
        platform_properties = _PLATFORM_PROPERTIES,
        runtime_catalog = _RUNTIME_CATALOG,
        **kwargs
    )

    # Attrs shared by the secondary (_dev / _native) targets.
    main_class = kwargs.get("main_class", "")
    common = dict(
        build_properties = build_properties,
        main_class = main_class,
        quarkus_version = _QUARKUS_VERSION,
        quarkifier_tool = _QUARKIFIER_TOOL,
        deployment_deps = _DEPLOYMENT_DEPS,
        conditional_deps = _CONDITIONAL_DEPS,
        conditional_catalog = _CONDITIONAL_CATALOG,
        deployment_catalog = _DEPLOYMENT_CATALOG,
        deployment_artifacts = _DEPLOYMENT_ARTIFACTS,
        deps = kwargs.get("deps", []),
        platform_catalog = _PLATFORM_CATALOG,
        platform_properties = _PLATFORM_PROPERTIES,
        runtime_catalog = _RUNTIME_CATALOG,
        version = kwargs.get("version", ""),
    )
    if dev:
        quarkus_dev_rule(
            name = name + "_dev",
            core_deployment_deps = _CORE_DEPLOYMENT_DEPS,
            dev_build_args = dev_build_args,
            **common
        )
    if native:
        quarkus_native_app_rule(
            name = name + "_native",
            **common
        )
    if native_container_build:
        quarkus_native_container_app_rule(
            name = name + "_native",
            container_runtime = native_container_runtime,
            builder_image = native_builder_image,
            native_arch = select({{
                "@platforms//cpu:aarch64": "aarch64",
                "@platforms//cpu:x86_64": "amd64",
                "//conditions:default": "unsupported",
            }}),
            **common
        )

def _prepare_test_target(name, srcs, deps, test_packages, test_classes, jvm_flags, build_properties, kwargs):
    test_deps = deps or []
    if srcs:
        compile_deps = []
        seen_compile_deps = {{}}
        for dep in test_deps + _TEST_INFRASTRUCTURE_DEPS:
            if dep not in seen_compile_deps:
                seen_compile_deps[dep] = True
                compile_deps.append(dep)
        java_library(
            name = name + "_lib",
            srcs = srcs,
            deps = compile_deps,
            testonly = True,
        )
        test_deps = [":" + name + "_lib"]

    test_kwargs = {{}}
    if test_packages:
        test_kwargs["test_packages"] = test_packages
    if test_classes:
        test_kwargs["test_classes"] = test_classes
    if jvm_flags:
        test_kwargs["jvm_flags"] = jvm_flags
    if build_properties != None:
        test_kwargs["build_properties"] = build_properties
    test_kwargs.update(kwargs)

    return struct(
        deps = test_deps,
        kwargs = test_kwargs,
    )

def quarkus_test(name, srcs = None, deps = None, test_packages = None, test_classes = None,
                 jvm_flags = None, build_properties = None, **kwargs):
    \"\"\"Runs @QuarkusTest-annotated JUnit 5 tests with full Quarkus augmentation.

    If srcs is provided, a java_library is created internally to compile the
    test sources. If srcs is omitted, deps must include a pre-compiled
    java_library containing the test classes.
    \"\"\"
    prepared = _prepare_test_target(
        name,
        srcs,
        deps,
        test_packages,
        test_classes,
        jvm_flags,
        build_properties,
        kwargs,
    )

    _quarkus_test(
        name = name,
        quarkus_version = _QUARKUS_VERSION,
        quarkifier_tool = _QUARKIFIER_TOOL,
        deployment_deps = _DEPLOYMENT_DEPS,
        conditional_deps = _CONDITIONAL_DEPS,
        conditional_catalog = _CONDITIONAL_CATALOG,
        deployment_catalog = _DEPLOYMENT_CATALOG,
        deployment_artifacts = _DEPLOYMENT_ARTIFACTS,
        deps = prepared.deps,
        model_private_deps = _TEST_INFRASTRUCTURE_DEPS,
        platform_catalog = _PLATFORM_CATALOG,
        platform_properties = _PLATFORM_PROPERTIES,
        runtime_catalog = _RUNTIME_CATALOG,
        **prepared.kwargs
    )

def quarkus_integration_test(name, app, srcs = None, deps = None, test_packages = None,
                             test_classes = None, jvm_flags = None, **kwargs):
    \"\"\"Runs @QuarkusIntegrationTest tests against a packaged application.

    The app must be a quarkus_app target or its <name>_native target. Test deps
    must include the application library directly or transitively so Quarkus
    can construct the TEST-mode ApplicationModel used by test resources and
    Dev Services.

    If srcs is provided, a java_library is created internally to compile the
    test sources. If srcs is omitted, deps must include a pre-compiled
    java_library containing the integration-test classes.
    \"\"\"
    if "build_properties" in kwargs:
        fail("quarkus_integration_test does not accept build_properties; declare build-time configuration on its app target")

    prepared = _prepare_test_target(
        name,
        srcs,
        deps,
        test_packages,
        test_classes,
        jvm_flags,
        None,
        kwargs,
    )

    _quarkus_integration_test(
        name = name,
        app = app,
        quarkus_version = _QUARKUS_VERSION,
        quarkifier_tool = _QUARKIFIER_TOOL,
        deployment_deps = _DEPLOYMENT_DEPS,
        conditional_deps = _CONDITIONAL_DEPS,
        conditional_catalog = _CONDITIONAL_CATALOG,
        deployment_catalog = _DEPLOYMENT_CATALOG,
        deployment_artifacts = _DEPLOYMENT_ARTIFACTS,
        deps = prepared.deps,
        model_private_deps = _TEST_INFRASTRUCTURE_DEPS,
        platform_catalog = _PLATFORM_CATALOG,
        platform_properties = _PLATFORM_PROPERTIES,
        runtime_catalog = _RUNTIME_CATALOG,
        **prepared.kwargs
    )

def quarkus_extension_runtime(name, group_id, version, runtime_target, deployment_target,
                              artifact_id):
    \"\"\"Builds a Quarkus extension runtime target from java_library targets.

    Mirrors the Maven/Gradle extension layout: a runtime module that application
    code depends on, and a deployment module that runs at augmentation time.
    Bundles a generated META-INF/quarkus-extension.properties into the runtime jar;
    the runtime module's own META-INF/quarkus-extension.yaml resource (read by the
    Dev UI) is carried through, enriched with the Quarkus core version and the
    extension's extension-dependencies (discovered from the compile classpath).

    Creates:
      - <name>: the runtime library. Add it to your application's java_library deps;
        the deployment side is wired into augmentation automatically.

    Args:
        name: Runtime library target name (the extension's public name).
        group_id: Maven groupId for the generated deployment-artifact descriptor.
        version: Maven version for the generated deployment-artifact descriptor.
        runtime_target: java_library target for the runtime module.
        deployment_target: java_library target for the deployment module.
        artifact_id: Extension artifactId (deployment artifact is artifact_id + "-deployment").
    \"\"\"
    quarkus_extension_runtime_rule(
        name = name,
        runtime = runtime_target,
        deployment = deployment_target,
        group_id = group_id,
        artifact_id = artifact_id,
        version = version,
        quarkus_version = _QUARKUS_VERSION,
        quarkifier_tool = _QUARKIFIER_TOOL,
    )
"""

# ---- Lock-driven Maven repository materialization ----

def _canonical_coordinate(fields):
    """Returns Quarkus G:A[:C:T]:V notation for catalog coordinates."""
    group_id = fields["groupId"]
    artifact_id = fields["artifactId"]
    classifier = fields.get("classifier", "")
    artifact_type = fields.get("type", "jar")
    version = fields["version"]
    if not classifier and artifact_type == "jar":
        return "{}:{}:{}".format(group_id, artifact_id, version)
    if not classifier:
        return "{}:{}:{}:{}".format(group_id, artifact_id, artifact_type, version)
    return "{}:{}:{}:{}:{}".format(group_id, artifact_id, classifier, artifact_type, version)

def _normalize_coordinate(coordinate):
    """Normalizes descriptor coordinates to Quarkus G:A[:C:T]:V notation."""
    parts = coordinate.strip().split(":")
    if len(parts) == 3 and all(parts):
        return coordinate.strip()
    if len(parts) == 4 and all(parts):
        if parts[2] == "jar":
            return "{}:{}:{}".format(parts[0], parts[1], parts[3])
        return coordinate.strip()
    if len(parts) == 5 and parts[0] and parts[1] and parts[3] and parts[4]:
        if not parts[2] and parts[3] == "jar":
            return "{}:{}:{}".format(parts[0], parts[1], parts[4])
        if not parts[2]:
            return "{}:{}:{}:{}".format(parts[0], parts[1], parts[3], parts[4])
        return coordinate.strip()
    fail("Invalid Maven coordinate '{}': expected G:A:V, G:A:T:V, or G:A:C:T:V".format(coordinate))

def _property_lines(content):
    """Joins Java-properties continuation lines used by Quarkus descriptors."""
    logical = []
    pending = ""
    for physical in content.split("\n"):
        line = physical.rstrip("\r")
        slash_count = 0
        for char in reversed(line.elems()):
            if char != "\\":
                break
            slash_count += 1
        continued = slash_count % 2 == 1
        if continued:
            line = line[:-1]
        pending += line.lstrip() if pending else line
        if not continued:
            logical.append(pending)
            pending = ""
    if pending:
        logical.append(pending)
    return logical

def _properties(content):
    """Parses the descriptor properties needed by rules_quarkus."""
    result = {}
    for raw_line in _property_lines(content):
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        separator = -1
        escaped = False
        for index in range(len(line)):
            char = line[index]
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char in ["=", ":", " ", "\t"]:
                separator = index
                break
        if separator < 0:
            key = line
            value = ""
        else:
            key = line[:separator]
            value = line[separator:]
            value = value.lstrip("=: \t")
        result[key.replace("\\:", ":").replace("\\=", "=")] = value.replace("\\:", ":").replace("\\=", "=")
    return result

def _words(value):
    return [word for word in value.replace("\t", " ").split(" ") if word]

def _host_maven_classifier(os, available):
    os_name = "osx" if "mac" in os.name.lower() else "linux" if "linux" in os.name.lower() else "windows" if "windows" in os.name.lower() else ""
    arch = os.arch.lower()
    cpu = "aarch_64" if arch in ["aarch64", "arm64"] else "x86_64" if arch in ["x86_64", "amd64"] else ""
    candidate = os_name + "-" + cpu
    return candidate if candidate in available else None

def _locked_artifact_download(rctx, lock_data, node, output_root):
    """Downloads one exact Maven-lock artifact through Bazel's verified downloader."""
    fields = node["coordinates"]
    coordinate_key = node["coordinateKey"]
    artifacts = lock_data["artifacts"]
    artifact_key = coordinate_key if coordinate_key in artifacts else fields["groupId"] + ":" + fields["artifactId"]
    if artifact_key not in artifacts and fields["type"] != "jar":
        artifact_key += ":" + fields["type"]
    artifact = artifacts.get(artifact_key)
    if artifact == None:
        fail("Maven lock has no artifact file entry for '{}'".format(coordinate_key))
    shasums = artifact.get("shasums", {})
    classifier = fields["classifier"]
    sha_key = classifier or ("jar" if fields["type"] in ["jar", "bundle", "test-jar"] else fields["type"])
    if sha_key not in shasums:
        if len(shasums) == 1:
            sha_key = shasums.keys()[0]
            classifier = "" if sha_key == "jar" else sha_key
        else:
            sha_key = _host_maven_classifier(rctx.os, shasums)
            if sha_key == None:
                fail("Maven artifact '{}' has no file for host {} {}".format(coordinate_key, rctx.os.name, rctx.os.arch))
            classifier = sha_key
    extension = "jar" if fields["type"] in ["jar", "bundle", "test-jar"] else fields["type"]
    suffix = ("-" + classifier) if classifier else ""
    filename = "{}-{}{}.{}".format(fields["artifactId"], fields["version"], suffix, extension)
    relative_path = "{}/{}/{}/{}".format(
        fields["groupId"].replace(".", "/"),
        fields["artifactId"],
        fields["version"],
        filename,
    )
    output = output_root + "/" + relative_path
    rctx.download(
        url = [repository.removesuffix("/") + "/" + relative_path for repository in rctx.attr.artifact_repositories],
        output = output,
        sha256 = shasums[sha_key],
    )
    return str(rctx.path(output))

def _scan_locked_descriptors(rctx, lock_data, indexes, direct_artifacts):
    """Reads Quarkus descriptors from every Maven-locked runtime artifact the direct ones reach.

    Quarkus's own resolver (`ApplicationDependencyResolver.resolveExtensionInfo`) opens
    `META-INF/quarkus-extension.properties` in every runtime node of the graph, direct or
    transitive, and takes its deployment artifact and `conditional-dependencies` from there.
    An extension that arrives only through another extension's POM — `quarkus-amazon-common`
    behind `quarkus-amazon-s3` — is therefore a full participant: its descriptor is the only
    place its conditional dependencies (the SDK transport extensions) are declared, so a scan
    limited to the direct artifacts never activates them and the async client they produce
    is missing at augmentation.

    The scan walks the lock graph's closure of the direct artifacts, so every jar Quarkus
    would open is opened here too. Each jar is discarded once its descriptor has been read:
    the closure is the whole runtime classpath, and keeping it extracted would cost gigabytes
    for a few properties files.
    """
    descriptors = {}
    candidates = sorted(_closure(direct_artifacts, indexes.by_key).keys())
    for index in range(len(candidates)):
        key = candidates[index]
        node = indexes.by_key[key]
        runtime_coordinate = _canonical_coordinate(node["coordinates"])
        if node["coordinates"]["type"] not in ["jar", "bundle", "test-jar"]:
            continue
        artifact = _locked_artifact_download(rctx, lock_data, node, "descriptor-artifacts")
        output = "descriptor-scan/{}".format(index)
        rctx.extract(artifact, output)
        descriptor_path = rctx.path(output + "/META-INF/quarkus-extension.properties")
        props = _properties(rctx.read(descriptor_path)) if descriptor_path.exists else None
        rctx.delete(output)
        rctx.delete(artifact)
        if props == None:
            continue
        deployment = props.get("deployment-artifact", "").strip()
        if not deployment:
            fail("Quarkus extension descriptor in {} has no deployment-artifact property".format(artifact))
        runtime = _normalize_coordinate(runtime_coordinate)
        descriptor = {
            "conditionalDependencies": [_normalize_coordinate(value) for value in _words(props.get("conditional-dependencies", ""))],
            "conditionalDevDependencies": [_normalize_coordinate(value) for value in _words(props.get("conditional-dev-dependencies", ""))],
            "dependencyConditions": _words(props.get("dependency-condition", "")),
            "deploymentArtifact": _normalize_coordinate(deployment),
            "runtimeArtifact": runtime,
        }
        previous = descriptors.get(runtime)
        if previous != None and previous != descriptor:
            fail("Runtime artifact '{}' has conflicting extension descriptors".format(runtime))
        descriptors[runtime] = descriptor
    return [descriptors[key] for key in sorted(descriptors)]

def _catalog_indexes(catalog):
    by_key = {node["coordinateKey"]: node for node in catalog["nodes"]}
    by_coordinate = {_canonical_coordinate(node["coordinates"]): node for node in catalog["nodes"]}
    return struct(by_coordinate = by_coordinate, by_key = by_key)

def _root_keys(coordinates, indexes, label):
    keys = []
    seen = {}
    for coordinate in coordinates:
        normalized = _normalize_coordinate(coordinate)
        node = indexes.by_coordinate.get(normalized)
        if node == None:
            fail("{} '{}' is absent from the Maven lock; add it to maven.install artifacts and repin".format(label, normalized))
        key = node["coordinateKey"]
        if key not in seen:
            seen[key] = True
            keys.append(key)
    return keys

def _closure(root_keys, nodes_by_key):
    selected = {key: True for key in root_keys}
    for _ in nodes_by_key:
        changed = False
        for key in sorted(selected.keys()):
            node = nodes_by_key.get(key)
            if node == None:
                fail("Maven lock graph references missing artifact '{}'".format(key))
            for dependency in node["dependencies"]:
                if dependency not in nodes_by_key:
                    fail("Maven lock graph contains an unresolved edge from '{}' to '{}'".format(key, dependency))
                if dependency not in selected:
                    selected[dependency] = True
                    changed = True
        if not changed:
            break
    return selected

def _catalog_nodes(selected, indexes, repo_paths):
    nodes = []
    for key in sorted(selected):
        node = indexes.by_key[key]
        coordinate = _canonical_coordinate(node["coordinates"])
        artifact_path = repo_paths.get(coordinate)
        if artifact_path == None:
            fail("Maven-locked artifact '{}' has no materialized file".format(coordinate))
        nodes.append({
            "coordinate": coordinate,
            "dependencies": [
                _canonical_coordinate(indexes.by_key[dependency]["coordinates"])
                for dependency in node["dependencies"]
            ],
            "exclusions": node["exclusions"],
            "repoPath": artifact_path,
        })
    return nodes

def _build_time_artifact_key(coordinate):
    """Maps a declared build-time artifact to the lock's coordinate key.

    Declarations are `groupId:artifactId` (the platform BOM supplies the version) or
    `groupId:artifactId:version`; the lock keys a plain jar by `groupId:artifactId`.
    """
    parts = coordinate.split(":")
    if len(parts) not in [2, 3] or not parts[0] or not parts[1]:
        fail("Invalid build-time artifact '{}': expected groupId:artifactId or groupId:artifactId:version".format(coordinate))
    return parts[0] + ":" + parts[1]

def _validate_build_time_artifacts(declared, indexes, build_roots):
    """Rejects a declared build-time artifact that no descriptor in the lock names.

    The workspace declares these artifacts in its Maven install for one reason only: a
    descriptor scanned from the lock's runtime closure names them (deployment artifacts,
    conditional dependencies) or rules_quarkus itself needs them (the dev-mode bootstrap).
    Maven cannot reach any of them from the application's own dependencies, so an entry
    nothing names would sit in the lock forever. The converse — a descriptor naming an
    artifact the lock lacks — fails where the roots are resolved.
    """
    unjustified = []
    for coordinate in declared:
        key = _build_time_artifact_key(coordinate)
        if key not in indexes.by_key:
            fail("Build-time artifact '{}' is absent from the Maven lock; repin after changing maven.install".format(coordinate))
        if key not in build_roots:
            unjustified.append(coordinate)
    if unjustified:
        fail(
            "Build-time artifacts that no Quarkus extension descriptor in the lock's runtime closure " +
            "names as a deployment artifact or conditional dependency, and that are not the dev-mode " +
            "bootstrap: {}. Remove them from build_time_artifacts and maven.install, then repin".format(
                ", ".join(unjustified),
            ),
        )

def _materialize_locked_maven_graph(rctx):
    """Builds every generated catalog from the rules_jvm_external Maven lock."""
    lock_data = json.decode(rctx.read(rctx.attr.lock_file))
    runtime_catalog = _runtime_catalog(lock_data)
    indexes = _catalog_indexes(runtime_catalog)
    descriptors = _scan_locked_descriptors(rctx, lock_data, indexes, runtime_catalog["directArtifacts"])

    deployment_coordinates = [descriptor["deploymentArtifact"] for descriptor in descriptors]
    deployment_coordinates.append("io.quarkus:quarkus-core-deployment:" + rctx.attr.quarkus_version)
    conditional_coordinates = []
    for descriptor in descriptors:
        conditional_coordinates.extend(descriptor["conditionalDependencies"])
        conditional_coordinates.extend(descriptor["conditionalDevDependencies"])

    deployment_roots = _root_keys(deployment_coordinates, indexes, "Descriptor-declared deployment artifact")
    conditional_roots = _root_keys(conditional_coordinates, indexes, "Descriptor-declared conditional artifact")
    core_roots = _root_keys([
        "io.quarkus:quarkus-bootstrap-maven-resolver:" + rctx.attr.quarkus_version,
        "io.quarkus:quarkus-core-deployment:" + rctx.attr.quarkus_version,
    ], indexes, "Dev-mode Maven infrastructure artifact")

    deployment_selected = _closure(deployment_roots + core_roots, indexes.by_key)
    conditional_selected = _closure(conditional_roots, indexes.by_key)
    core_selected = _closure(core_roots, indexes.by_key)

    build_roots = {key: True for key in deployment_roots + conditional_roots + core_roots}
    _validate_build_time_artifacts(rctx.attr.build_time_artifacts, indexes, build_roots)
    runtime_catalog["directArtifacts"] = [key for key in runtime_catalog["directArtifacts"] if key not in build_roots]

    files = {}
    selected_keys = dict(deployment_selected)
    selected_keys.update(conditional_selected)
    for key in sorted(selected_keys):
        node = indexes.by_key[key]
        coordinate = _canonical_coordinate(node["coordinates"])
        files[coordinate] = _locked_artifact_download(rctx, lock_data, node, "locked-artifacts")

    deployment_jars = []
    deployment_artifacts = []
    core_paths = {}
    for key in sorted(deployment_selected):
        coordinate = _canonical_coordinate(indexes.by_key[key]["coordinates"])
        path = files.get(coordinate)
        if path == None:
            fail("Maven lock artifact '{}' has no Bazel file label".format(coordinate))
        if path.endswith(".jar"):
            deployment_jars.append(path)
        else:
            deployment_artifacts.append(path)
        if key in core_selected:
            core_paths[path] = True

    conditional_jars = []
    for key in sorted(conditional_selected):
        coordinate = _canonical_coordinate(indexes.by_key[key]["coordinates"])
        path = files.get(coordinate)
        if path == None:
            fail("Maven lock artifact '{}' has no Bazel file label".format(coordinate))
        if path.endswith(".jar"):
            conditional_jars.append(path)

    conditional_repo_paths_by_file = _write_conditional_build(rctx, conditional_jars)
    deployment_repo_paths_by_file = _write_deployment_build(rctx, deployment_jars, core_paths, deployment_artifacts)
    deployment_repo_paths = {
        coordinate: deployment_repo_paths_by_file[path]
        for coordinate, path in files.items()
        if path in deployment_repo_paths_by_file
    }
    conditional_repo_paths = {
        coordinate: conditional_repo_paths_by_file[path]
        for coordinate, path in files.items()
        if path in conditional_repo_paths_by_file
    }

    deployment_catalog = {
        "conflictResolution": {},
        "droppedRoots": [],
        "nodes": _catalog_nodes(deployment_selected, indexes, deployment_repo_paths),
        "resolver": "maven",
        "resolverReportVersion": str(lock_data.get("version", "")),
        "roots": sorted([_canonical_coordinate(indexes.by_key[key]["coordinates"]) for key in deployment_roots]),
        "schemaVersion": "quarkus-bazel-deployment-catalog-v1",
    }
    conditional_catalog = {
        "conflictResolution": {},
        "extensions": descriptors,
        "nodes": _catalog_nodes(conditional_selected, indexes, conditional_repo_paths),
        "resolver": "maven",
        "resolverReportVersion": str(lock_data.get("version", "")),
        "roots": sorted([_canonical_coordinate(indexes.by_key[key]["coordinates"]) for key in conditional_roots]),
        "schemaVersion": "quarkus-bazel-conditional-catalog-v1",
    }
    rctx.file("model/runtime-catalog-v1.json", json.encode(runtime_catalog) + "\n")
    rctx.file("model/deployment-catalog-v1.json", json.encode(deployment_catalog) + "\n")
    rctx.file("model/conditional-catalog-v1.json", json.encode(conditional_catalog) + "\n")

# ---- Repository rule ----

def _rules_quarkus_repo_impl(rctx):
    """Creates the unified @rules_quarkus repository.

    Contains:
      - quarkus/defs.bzl: public macros
      - quarkifier/tool.jar: the quarkifier deploy jar
      - deployment/: java_library targets for deployment jars
    """
    _materialize_locked_maven_graph(rctx)
    _write_platform_catalog(rctx)

    rctx.file("BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
""")

    rctx.file("quarkus/BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
exports_files(["defs.bzl"])
""")

    rctx.file("model/BUILD.bazel", content = """\
package(default_visibility = ["//visibility:public"])
exports_files([
    "conditional-catalog-v1.json",
    "deployment-catalog-v1.json",
    "platform-catalog-v1.json",
    "runtime-catalog-v1.json",
])
filegroup(name = "conditional_catalog", srcs = ["conditional-catalog-v1.json"])
filegroup(name = "deployment_catalog", srcs = ["deployment-catalog-v1.json"])
filegroup(name = "platform_catalog", srcs = ["platform-catalog-v1.json"])
filegroup(name = "platform_properties", srcs = glob(["platform-properties/**/*.properties"]))
filegroup(name = "runtime_catalog", srcs = ["runtime-catalog-v1.json"])
""")

    rctx.file(
        "quarkus/defs.bzl",
        content = _DEFS_BZL_TEMPLATE.format(
            minor = _sanitize_version(_extract_minor_version(rctx.attr.quarkus_version)),
            version = rctx.attr.quarkus_version,
        ),
    )

_rules_quarkus_repo = repository_rule(
    implementation = _rules_quarkus_repo_impl,
    attrs = {
        "artifact_repositories": attr.string_list(
            default = [MAVEN_CENTRAL],
            doc = "Maven repository URLs used by Bazel's checksum-verified downloader.",
        ),
        "build_time_artifacts": attr.string_list(
            doc = "Workspace-declared descriptor-named artifacts, validated against the descriptor scan.",
        ),
        "lock_file": attr.label(doc = "rules_jvm_external v3 lock file used for the runtime catalog."),
        "platform_boms": attr.string_list(mandatory = True, doc = "Quarkus platform BOMs in G:A:V form."),
        "platform_properties": attr.string_dict(doc = "Explicit Quarkus platform property overrides."),
        "quarkus_version": attr.string(mandatory = True, doc = "Quarkus version."),
    },
)

# ---- Module extension ----

def _quarkus_impl(mctx):
    # Collect toolchain tags from every module, root module first: the root's
    # choice wins, but a single fixed @rules_quarkus repo means only one
    # Quarkus version per workspace — conflicting requests must fail loudly
    # instead of silently using whichever tag happens to come first.
    root_tags = []
    dep_tags = []
    for mod in mctx.modules:
        for tag in mod.tags.toolchain:
            if mod.is_root:
                root_tags.append(tag)
            else:
                dep_tags.append(tag)
    ordered_tags = root_tags + dep_tags
    if not ordered_tags:
        fail("quarkus.toolchain() must be called in MODULE.bazel")

    tc = ordered_tags[0]
    for tag in ordered_tags[1:]:
        if tag.quarkus_version != tc.quarkus_version:
            fail(("Conflicting quarkus.toolchain() versions requested: '{}' and '{}'. " +
                  "A workspace supports a single Quarkus version; align the " +
                  "quarkus_version attributes (the root module's choice wins ties).").format(
                tc.quarkus_version,
                tag.quarkus_version,
            ))

    version = tc.quarkus_version
    _validate_version(version)

    if not tc.lock_file:
        fail("quarkus.toolchain(lock_file = ...) is required for Maven-owned dependency resolution")
    lock_data = json.decode(mctx.read(tc.lock_file))

    repo_attrs = {
        "artifact_repositories": tc.artifact_repositories or [MAVEN_CENTRAL],
        "name": "rules_quarkus",
        "quarkus_version": version,
        "platform_boms": tc.platform_boms or ["io.quarkus.platform:quarkus-bom:" + version],
        "platform_properties": tc.platform_properties,
    }
    repo_attrs["lock_file"] = tc.lock_file
    repo_attrs["build_time_artifacts"] = tc.build_time_artifacts

    _rules_quarkus_repo(**repo_attrs)

_toolchain_tag = tag_class(
    attrs = {
        "artifact_repositories": attr.string_list(
            doc = "Maven repositories containing every artifact in lock_file.",
        ),
        "build_time_artifacts": attr.string_list(
            doc = "The artifacts the workspace added to its Maven install only because Quarkus " +
                  "descriptors name them: deployment artifacts, conditional dependencies, and " +
                  "the dev-mode bootstrap. Each is groupId:artifactId or groupId:artifactId:version. " +
                  "Repository setup fails when an entry is named by no descriptor in the lock's " +
                  "runtime closure, so the list cannot go stale.",
        ),
        "lock_file": attr.label(
            doc = "Path to a rules_jvm_external v3 maven_install.json for descriptor-driven extension discovery.",
        ),
        "platform_boms": attr.string_list(
            doc = "Quarkus platform BOM imports as groupId:artifactId:version. Defaults to the standard Quarkus platform BOM.",
        ),
        "platform_properties": attr.string_dict(
            doc = "Additional or overriding Quarkus platform properties. Custom platform release-info properties are supported.",
        ),
        "quarkus_version": attr.string(
            mandatory = True,
            doc = "The Quarkus version to use. Must be one of: " +
                  ", ".join([SUPPORTED_VERSIONS[m] for m in sorted(SUPPORTED_VERSIONS)]) + ".",
        ),
    },
)

quarkus = module_extension(
    implementation = _quarkus_impl,
    tag_classes = {"toolchain": _toolchain_tag},
    doc = "Configures Quarkus toolchain and auto-resolves deployment artifacts.",
)
