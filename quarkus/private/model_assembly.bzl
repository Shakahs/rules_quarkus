"Hermetic action that assembles the explicit Bazel application model."

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@bazel_lib//lib:lists.bzl", "unique")
load("//quarkus/private:application_model_aspect.bzl", "collect_all_model_artifacts", "collect_deployment_model_fragments", "collect_local_deployments", "collect_local_runtime_aliases", "collect_model_fragments", "model_root_ids", "unique_mappings", "write_model_roots_file")
load("//quarkus/private:versions.bzl", "RULES_VERSION")

_DEPLOYMENT_PATH_MARKERS = ["deployment/jars/", "deployment/artifacts/"]
_CONDITIONAL_PATH_MARKERS = ["conditional/jars/"]
_PLATFORM_PROPERTY_PATH_MARKERS = ["model/platform-properties/"]

def _write_path_lines(ctx, name, suffix, files):
    output = ctx.actions.declare_file(name + suffix)
    args = ctx.actions.args()
    args.set_param_file_format("multiline")
    args.add_all(files)
    ctx.actions.write(output = output, content = args)
    return output

def _write_marker_paths(ctx, name, files, markers, suffix, label, require_marker = False):
    """Writes a sorted tab-separated path manifest for files matching a marker prefix.

    Args:
        ctx: Rule context.
        name: The model's name, which prefixes the file's.
        files: Iterable of File objects to scan.
        markers: Path substrings that mark relevant files (e.g. ["deployment/jars/"]).
            The first marker present in a path wins.
        suffix: Output file name suffix (e.g. ".quarkus-deployment-paths-v1.txt").
        label: Human-readable label for error messages.
        require_marker: When True, files missing every marker cause a fail() instead of being skipped.

    Returns:
        The declared output file.
    """
    output = ctx.actions.declare_file(name + suffix)
    seen = {}
    for file in files:
        marker_index = -1
        for marker in markers:
            marker_index = file.path.find(marker)
            if marker_index >= 0:
                break
        if marker_index < 0:
            if require_marker:
                fail("{} input '{}' is outside the generated model repository".format(label, file.path))
            continue
        repo_path = file.path[marker_index:]
        if repo_path in seen and seen[repo_path] != file.path:
            fail("{} path '{}' maps to both '{}' and '{}'".format(label, repo_path, seen[repo_path], file.path))
        seen[repo_path] = file.path
    lines = [repo_path + "\t" + seen[repo_path] for repo_path in sorted(seen)]
    ctx.actions.write(output = output, content = "\n".join(lines) + ("\n" if lines else ""))
    return output

def _write_deployment_paths(ctx, name, deployment_classpath):
    files = deployment_classpath.to_list() + ctx.files.deployment_artifacts
    return _write_marker_paths(ctx, name, files, _DEPLOYMENT_PATH_MARKERS, ".quarkus-deployment-paths-v1.txt", "deployment catalog")

def _write_conditional_paths(ctx, name, conditional_classpath):
    return _write_marker_paths(ctx, name, conditional_classpath.to_list(), _CONDITIONAL_PATH_MARKERS, ".quarkus-conditional-paths-v1.txt", "conditional catalog")

def _write_platform_property_paths(ctx, name):
    return _write_marker_paths(ctx, name, ctx.files.platform_properties, _PLATFORM_PROPERTY_PATH_MARKERS, ".quarkus-platform-property-paths-v1.txt", "platform properties", require_marker = True)

def _write_local_deployments(ctx, name, local_deployments):
    output = ctx.actions.declare_file(name + ".quarkus-local-deployments-v1.txt")
    lines = [deployment["coordinate"] + "\t" + deployment["targetId"] for deployment in local_deployments]
    ctx.actions.write(output = output, content = "\n".join(lines) + ("\n" if lines else ""))
    return output

def _write_local_runtime_aliases(ctx, name, aliases):
    output = ctx.actions.declare_file(name + ".quarkus-local-runtime-aliases-v1.txt")
    lines = [alias["rawTargetId"] + "\t" + alias["targetId"] for alias in aliases]
    ctx.actions.write(output = output, content = "\n".join(lines) + ("\n" if lines else ""))
    return output

def application_model_parts(deps, runtime_classpath, deployment_classpath, model_private_targets = []):
    """What a model is assembled from, collected from its direct dependencies.

    Args:
        deps: Ordered direct dependencies carrying model aspect providers.
        runtime_classpath: Depset of runtime classpath artifacts.
        deployment_classpath: Depset of deployment classpath artifacts.
        model_private_targets: Labels of dependencies the model leaves out.

    Returns:
        A struct of the model's roots, fragments, local extension joins, classpaths and artifacts.
    """
    return struct(
        root_ids = model_root_ids(deps),
        fragments = collect_model_fragments(deps),
        deployment_fragments = collect_deployment_model_fragments(deps),
        local_deployments = collect_local_deployments(deps),
        local_runtime_aliases = collect_local_runtime_aliases(deps),
        model_artifacts = collect_all_model_artifacts(deps),
        runtime_classpath = runtime_classpath,
        deployment_classpath = deployment_classpath,
        model_private_targets = [str(target) for target in model_private_targets],
    )

def merge_application_model_parts(parts):
    """One model's parts from several: the tests of several test targets, assembled together.

    Args:
        parts: Structs from application_model_parts, in order.

    Returns:
        A struct as application_model_parts returns, holding every root of each in order.
    """
    return struct(
        root_ids = unique([root_id for part in parts for root_id in part.root_ids]),
        fragments = depset(transitive = [part.fragments for part in parts]),
        deployment_fragments = depset(transitive = [part.deployment_fragments for part in parts]),
        local_deployments = unique_mappings([part.local_deployments for part in parts], "coordinate", "targetId", "local deployment coordinate"),
        local_runtime_aliases = unique_mappings([part.local_runtime_aliases for part in parts], "rawTargetId", "targetId", "local runtime target"),
        model_artifacts = depset(transitive = [part.model_artifacts for part in parts]),
        runtime_classpath = depset(transitive = [part.runtime_classpath for part in parts]),
        deployment_classpath = depset(transitive = [part.deployment_classpath for part in parts]),
        model_private_targets = unique([target for part in parts for target in part.model_private_targets]),
    )

def assemble_model_from_parts(ctx, name, parts, conditional_classpath, mode, application_name = None, test_application = None):
    """Assembles one lifecycle-specific model from its parts.

    Args:
        ctx: Rule context for the target owning the model.
        name: The model's name, which prefixes every file the assembly writes.
        parts: A struct from application_model_parts or merge_application_model_parts.
        conditional_classpath: Depset of conditional dependency candidates.
        mode: Quarkus launch mode represented by the assembled model.
        application_name: Optional application artifact name; defaults to the target name.
        test_application: For a TEST model holding several test targets' tests, the root id of
            the application they test.

    Returns:
        The declared canonical application-model JSON file.
    """
    roots = write_model_roots_file(ctx, name, parts.root_ids)
    all_fragments = depset(transitive = [parts.fragments, parts.deployment_fragments])
    fragment_manifest = _write_path_lines(
        ctx,
        name,
        ".quarkus-target-fragments-v1.txt",
        all_fragments.to_list(),
    )
    runtime_classpath = parts.runtime_classpath
    deployment_classpath = parts.deployment_classpath
    deployment_paths = _write_deployment_paths(ctx, name, deployment_classpath)
    conditional_paths = _write_conditional_paths(ctx, name, conditional_classpath)
    platform_property_paths = _write_platform_property_paths(ctx, name)
    runtime_classpath_paths = _write_path_lines(ctx, name, ".quarkus-runtime-classpath-paths-v1.txt", runtime_classpath.to_list())
    deployment_classpath_paths = _write_path_lines(ctx, name, ".quarkus-deployment-classpath-paths-v1.txt", deployment_classpath.to_list())
    model_private_targets = _write_path_lines(ctx, name, ".quarkus-model-private-targets-v1.txt", parts.model_private_targets)
    local_deployments_file = _write_local_deployments(ctx, name, parts.local_deployments)
    local_runtime_aliases_file = _write_local_runtime_aliases(ctx, name, parts.local_runtime_aliases)
    output = ctx.actions.declare_file(name + ".quarkus-bazel-model-v1.json")

    args = ctx.actions.args()
    args.add("-jar")
    args.add(ctx.file.quarkifier_tool)
    args.add("assemble-model")
    args.add("--roots", roots)
    args.add("--target-fragments-file", fragment_manifest)
    args.add("--runtime-catalog", ctx.file.runtime_catalog)
    args.add("--conditional-catalog", ctx.file.conditional_catalog)
    args.add("--deployment-catalog", ctx.file.deployment_catalog)
    args.add("--platform-catalog", ctx.file.platform_catalog)
    args.add("--deployment-paths-file", deployment_paths)
    args.add("--conditional-paths-file", conditional_paths)
    args.add("--platform-property-paths-file", platform_property_paths)
    args.add("--runtime-classpath-paths-file", runtime_classpath_paths)
    args.add("--deployment-classpath-paths-file", deployment_classpath_paths)
    args.add("--model-private-targets-file", model_private_targets)
    args.add("--local-deployments-file", local_deployments_file)
    args.add("--local-runtime-aliases-file", local_runtime_aliases_file)
    args.add("--quarkus-version", ctx.attr.quarkus_version)
    args.add("--mode", mode)
    args.add("--application-name", application_name or ctx.label.name)
    if hasattr(ctx.attr, "version") and ctx.attr.version:
        args.add("--application-version", ctx.attr.version)
    if test_application:
        # One argument: picocli reads an argument that starts with `@` as an argument file, and a
        # canonical label starts with `@@`.
        args.add("--test-application=" + test_application)
    args.add("--producer-version", RULES_VERSION)
    args.add("--output", output)

    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]
    ctx.actions.run(
        executable = java_runtime.java_executable_exec_path,
        arguments = [args],
        inputs = depset(
            direct = [
                ctx.file.quarkifier_tool,
                roots,
                fragment_manifest,
                ctx.file.runtime_catalog,
                ctx.file.conditional_catalog,
                ctx.file.deployment_catalog,
                ctx.file.platform_catalog,
                deployment_paths,
                conditional_paths,
                platform_property_paths,
                runtime_classpath_paths,
                deployment_classpath_paths,
                model_private_targets,
                local_deployments_file,
                local_runtime_aliases_file,
            ],
            transitive = [all_fragments, runtime_classpath, depset(ctx.files.platform_properties), java_runtime.files],
        ),
        outputs = [output],
        mnemonic = "QuarkusModelAssembly",
        progress_message = "Assembling explicit Quarkus application model for %{label}",
    )
    return output

def assemble_application_model(ctx, deps, runtime_classpath, conditional_classpath, deployment_classpath, mode, application_name = None):
    """Collects aspect outputs and assembles one lifecycle-specific model.

    Args:
        ctx: Rule context for the application target.
        deps: Direct application dependencies carrying model aspect providers.
        runtime_classpath: Depset of runtime classpath artifacts.
        conditional_classpath: Depset of conditional dependency candidates.
        deployment_classpath: Depset of deployment classpath artifacts.
        mode: Quarkus launch mode represented by the assembled model.
        application_name: Optional application artifact name; defaults to the target name.

    Returns:
        The declared canonical application-model JSON file.
    """
    parts = application_model_parts(
        deps,
        runtime_classpath,
        deployment_classpath,
        [dep.label for dep in ctx.attr.model_private_deps] if hasattr(ctx.attr, "model_private_deps") else [],
    )
    return assemble_model_from_parts(ctx, ctx.label.name, parts, conditional_classpath, mode, application_name)
