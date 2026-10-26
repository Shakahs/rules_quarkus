"""Unit tests for continuous_tests.bzl."""

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(":continuous_tests.bzl", "include_pattern_for_test")

def _selection(test_classes = [], test_packages = []):
    return struct(test_classes = test_classes, test_packages = test_packages)

def _include_pattern_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(
        env,
        "com\\.example\\.Outer\\$InnerTest|com\\.example\\.billing\\..+|com\\.example\\.RouteTest",
        include_pattern_for_test([
            _selection(test_classes = ["com.example.Outer$InnerTest"], test_packages = ["com.example.billing"]),
            _selection(test_classes = ["com.example.RouteTest", "com.example.Outer$InnerTest"]),
        ]),
    )
    return unittest.end(env)

_include_pattern_test = unittest.make(_include_pattern_test_impl)

def _unselective_test_impl(ctx):
    env = unittest.begin(ctx)

    # A test that selects no class runs every class it holds, so the union selects every class.
    asserts.equals(
        env,
        None,
        include_pattern_for_test([
            _selection(test_classes = ["com.example.RouteTest"]),
            _selection(),
        ]),
    )
    return unittest.end(env)

_unselective_test = unittest.make(_unselective_test_impl)

def continuous_tests_test_suite(name = "continuous_tests_tests"):
    unittest.suite(
        name,
        _include_pattern_test,
        _unselective_test,
    )
