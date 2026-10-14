"""Regression checks against a consumer's real frontend targets."""

load("@bazel_skylib//lib:unittest.bzl", "analysistest", "asserts", "unittest")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("@rules_java//java:java_test.bzl", "java_test")
load("//quarkus/private:coverage_transition.bzl", "single_transitioned_target")

_LIFECYCLE = str(Label("//quarkus/private:codegen_lifecycle"))
_MODE = "//command_line_option:compilation_mode"

def _dev_config_impl(_settings, _attr):
    return {_LIFECYCLE: "dev", _MODE: "fastbuild"}

def _test_config_impl(_settings, _attr):
    return {_LIFECYCLE: "main", _MODE: "fastbuild"}

def _prod_config_impl(_settings, _attr):
    return {_LIFECYCLE: "main", _MODE: "opt"}

_dev_config = transition(implementation = _dev_config_impl, inputs = [], outputs = [_LIFECYCLE, _MODE])
_test_config = transition(implementation = _test_config_impl, inputs = [], outputs = [_LIFECYCLE, _MODE])
_prod_config = transition(implementation = _prod_config_impl, inputs = [], outputs = [_LIFECYCLE, _MODE])

def _configuration_test_impl(ctx):
    env = unittest.begin(ctx)
    dev = single_transitioned_target(ctx.attr.dev)[JavaInfo].runtime_output_jars
    testing = single_transitioned_target(ctx.attr.testing)[JavaInfo].runtime_output_jars
    production = single_transitioned_target(ctx.attr.production)[JavaInfo].runtime_output_jars
    asserts.equals(env, 1, len(dev))
    asserts.equals(env, dev, testing, "Dev and tests must share the same configured resource archive")
    asserts.false(env, dev == production, "Production must retain its separate optimized bundle")
    return unittest.end(env)

_configuration_test = unittest.make(
    _configuration_test_impl,
    attrs = {
        "dev": attr.label(cfg = _dev_config, providers = [JavaInfo]),
        "testing": attr.label(cfg = _test_config, providers = [JavaInfo]),
        "production": attr.label(cfg = _prod_config, providers = [JavaInfo]),
        "_allowlist_function_transition": attr.label(default = "@bazel_tools//tools/allowlists/function_transition_allowlist"),
    },
)

def _javascript_inputs_test_impl(ctx):
    env = analysistest.begin(ctx)
    inputs = [file for action in analysistest.target_actions(env) for file in action.inputs.to_list()]
    asserts.true(env, any([file.basename == "index.js" for file in inputs]), "Stage the real JavaScript entry")
    asserts.false(env, any([file.extension in ["html", "css"] for file in inputs]), "HTML and styles must not invalidate the JavaScript staging tree")
    return analysistest.end(env)

_javascript_inputs_test = analysistest.make(_javascript_inputs_test_impl)

def _archive_inputs_test_impl(ctx):
    env = analysistest.begin(ctx)
    actions = [action for action in analysistest.target_actions(env) if action.mnemonic == "PackageZip"]
    asserts.equals(env, 1, len(actions))
    if actions:
        directories = [file.basename for file in actions[0].inputs.to_list() if file.is_directory]
        asserts.true(env, ctx.attr.bundle_name + "_esbuild" in directories, "Archive esbuild output directly instead of copying assets into another tree")
        files = [file.basename for file in actions[0].inputs.to_list()]
        for required in ["index.html", "spa.html", "beans.xml"]:
            asserts.true(env, required in files, "Archive the individually declared HTML/metadata files")
    return analysistest.end(env)

_archive_inputs_test = analysistest.make(_archive_inputs_test_impl, attrs = {"bundle_name": attr.string()})

def _production_input_impl(ctx):
    source = single_transitioned_target(ctx.attr.source)
    return [source[DefaultInfo]]

_production_input = rule(
    implementation = _production_input_impl,
    attrs = {
        "source": attr.label(cfg = _prod_config, mandatory = True),
        "_allowlist_function_transition": attr.label(default = "@bazel_tools//tools/allowlists/function_transition_allowlist"),
    },
)

def quarkus_web_bundle_tests(name, bundle):
    """Test cache sharing, input isolation and direct packaging of a local bundle."""
    _configuration_test(name = name + "_configuration", dev = bundle, testing = bundle, production = bundle, tags = ["manual"])
    _javascript_inputs_test(name = name + "_javascript_inputs", target_under_test = bundle + "_sources")
    _archive_inputs_test(name = name + "_archive_inputs", target_under_test = bundle + "_jar", bundle_name = bundle.removeprefix(":"))
    _production_input(name = name + "_production_jar", source = bundle, tags = ["manual"], testonly = True)
    _production_input(name = name + "_production_assets", source = bundle + "_esbuild", tags = ["manual"], testonly = True)
    for mode, archive, assets, extra_tags in [
        ("development", bundle, bundle + "_esbuild", []),
        ("production", ":" + name + "_production_jar", ":" + name + "_production_assets", ["manual"]),
    ]:
        java_test(
            name = name + "_" + mode + "_archive",
            srcs = [Label("//quarkus/web:tests/FrontendArchiveTest.java")],
            main_class = "com.clementguillot.quarkus.web.testing.FrontendArchiveTest",
            use_testrunner = False,
            args = ["$(rlocationpath " + archive + ")", "$(rlocationpath " + assets + ")"],
            data = [archive, assets],
            deps = ["@rules_java//java/runfiles"],
            jvm_flags = ["-Xmx256m"],
            tags = extra_tags,
        )
    native.test_suite(
        name = name,
        tests = [":" + name + suffix for suffix in ["_javascript_inputs", "_archive_inputs", "_development_archive"]],
    )
