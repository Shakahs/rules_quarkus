"""The quarkus_test targets a dev target runs continuously, merged into what the dev JVM takes on.

Quarkus runs continuous tests inside the dev application's JVM, against a TEST model of their own:
the tests of every listed target are assembled into one model, whose application is the dev
target's, and their environment, data, JVM flags and test-augmentation properties become the dev
process's. Two targets that set one of them differently cannot run in one JVM, so that fails.
"""

load("@bazel_lib//lib:lists.bzl", "unique")
load("//quarkus/private:build_properties.bzl", "validate_build_property_keys")
load("//quarkus/private:model_assembly.bzl", "merge_application_model_parts")
load("//quarkus/private:quarkus_test_impl.bzl", "QuarkusContinuousTestInfo", "regex_escape_class_name")

_INCLUDE_PATTERN = "quarkus.test.include-pattern"

def _merge_settings(infos, field, what, fixed = {}):
    """Merges one dict field of the tests, failing for a key two of them set differently.

    Args:
        infos: The tests' QuarkusContinuousTestInfo.
        field: The dict field to merge.
        what: What the dict holds, for messages.
        fixed: Values the dev target sets itself, which a test may only repeat.

    Returns:
        The merged dict.
    """
    merged = {}
    owners = {}
    for info in infos:
        for key, value in getattr(info, field).items():
            if key in fixed and fixed[key] != value:
                fail("{} sets {} '{}' to '{}', but the dev target sets it to '{}'".format(info.label, what, key, value, fixed[key]))
            if key in merged and merged[key] != value:
                fail("{} and {} set {} '{}' differently: their tests cannot run in one dev JVM".format(owners[key], info.label, what, key))
            merged[key] = value
            owners[key] = info.label
    return merged

def _include_pattern(infos):
    """The class-name pattern the tests' selectors amount to, or None when one selects every class.

    A package selector selects its subpackages too, as JUnit's `--select-package` does. Quarkus
    matches the pattern against a test's whole class name.
    """
    selectors = []
    for info in infos:
        if not info.test_classes and not info.test_packages:
            return None
        selectors.extend([regex_escape_class_name(name) for name in info.test_classes])
        selectors.extend([regex_escape_class_name(name) + "\\..+" for name in info.test_packages])
    return "|".join(unique(selectors))

def merge_continuous_tests(ctx, tests, dev_build_properties):
    """Merges the tests a dev target runs continuously.

    Args:
        ctx: The dev target's rule context.
        tests: The quarkus_test targets.
        dev_build_properties: The dev target's own declared build properties.

    Returns:
        A struct of the merged `model_parts`, the dev JVM's additional `jvm_flags`, the shell
        `environment_exports` of the tests' environment, and the `runfiles` of their data.
    """
    infos = [test[QuarkusContinuousTestInfo] for test in tests]
    build_properties = _merge_settings(infos, "build_properties", "build property", dev_build_properties)
    validate_build_property_keys(build_properties)
    if _INCLUDE_PATTERN in build_properties:
        fail("{} is derived from the continuous tests' test_classes and test_packages".format(_INCLUDE_PATTERN))
    exports = _merge_settings(infos, "environment_exports", "environment variable")
    jvm_flags = unique([flag for info in infos for flag in info.jvm_flags])
    jvm_flags.extend([
        "-D{}={}".format(key, build_properties[key])
        for key in sorted(build_properties)
        if key not in dev_build_properties
    ])
    include_pattern = _include_pattern(infos)
    if include_pattern:
        jvm_flags.append("-D{}={}".format(_INCLUDE_PATTERN, include_pattern))
    for flag in jvm_flags:
        if "\n" in flag or "\r" in flag:
            fail("continuous test JVM flag '{}' holds a line break".format(flag))
    return struct(
        model_parts = merge_application_model_parts([info.model_parts for info in infos]),
        jvm_flags = jvm_flags,
        environment_exports = [exports[name] for name in sorted(exports)],
        runfiles = ctx.runfiles().merge_all([info.runfiles for info in infos]),
    )

include_pattern_for_test = _include_pattern
