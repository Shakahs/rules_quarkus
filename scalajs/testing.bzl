"""Support for the tool's test: the runfiles paths of a dependency closure, in a file."""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")

def _runfiles_path(file, workspace_name):
    # short_path is "../<repo>/..." for an external file and package-relative otherwise.
    if file.short_path.startswith("../"):
        return file.short_path[3:]
    return workspace_name + "/" + file.short_path

def _classpath_manifest_impl(ctx):
    jars = depset(transitive = [dep[JavaInfo].transitive_runtime_jars for dep in ctx.attr.deps])
    manifest = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(
        output = manifest,
        content = "".join([_runfiles_path(jar, ctx.workspace_name) + "\n" for jar in jars.to_list()]),
    )
    return [DefaultInfo(
        files = depset([manifest]),
        runfiles = ctx.runfiles(transitive_files = jars),
    )]

classpath_manifest = rule(
    implementation = _classpath_manifest_impl,
    doc = "Writes the runfiles path of every runtime jar of `deps`, one per line, and carries the jars.",
    attrs = {
        "deps": attr.label_list(mandatory = True, providers = [JavaInfo]),
    },
)
