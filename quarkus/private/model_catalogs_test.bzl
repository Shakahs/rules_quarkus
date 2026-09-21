"Unit tests for Maven-lock model-catalog normalization."

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load("//quarkus:extensions.bzl", "jar_target_name_for_test", "maven_target_name_for_test", "runtime_catalog_for_test")

def _runtime_catalog_v3_test_impl(ctx):
    env = unittest.begin(ctx)
    lock = {
        "__INPUT_ARTIFACTS_HASH": {
            "m.group:multi:jar:runtime": 4,
            "platform:quarkus-bom": 3,
            "repositories": 1,
            "z.group:z-artifact": 2,
        },
        "artifacts": {
            "a.group:a-artifact:jar:tests": {"shasums": {"tests": "a"}, "version": "1.2.3"},
            "c.group:classified": {"shasums": {"classes": "c"}, "version": "3.0"},
            "m.group:multi": {"shasums": {"jar": "m", "runtime": "mr"}, "version": "4.0"},
            "z.group:z-artifact": {"shasums": {"jar": "z"}, "version": "9.8.7"},
        },
        "conflict_resolution": {"z.group:z-artifact:8.0": "z.group:z-artifact:9.8.7"},
        "dependencies": {
            "a.group:a-artifact:jar:tests": ["z.group:z-artifact"],
            "c.group:classified:jar:classes": [],
            "parent.group:parent": ["m.group:multi"],
            "z.group:z-artifact": [],
        },
        "packages": {
            "m.group:multi": ["m.group:multi"],
            "m.group:multi:jar:runtime": ["m.group:multi:jar:runtime"],
        },
        "version": "3",
    }
    catalog = runtime_catalog_for_test(lock)
    asserts.equals(env, "quarkus-bazel-runtime-catalog-v1", catalog["schemaVersion"])
    asserts.equals(env, ["m.group:multi:jar:runtime", "z.group:z-artifact"], catalog["directArtifacts"])
    asserts.equals(env, "a.group:a-artifact:jar:tests", catalog["nodes"][0]["coordinateKey"])
    asserts.equals(env, "a_group_a_artifact_tests", catalog["nodes"][0]["targetName"])
    asserts.equals(env, "tests", catalog["nodes"][0]["coordinates"]["classifier"])
    asserts.equals(env, "jar", catalog["nodes"][0]["coordinates"]["type"])
    asserts.equals(env, ["z.group:z-artifact"], catalog["nodes"][0]["dependencies"])
    asserts.equals(env, "c.group:classified:jar:classes", catalog["nodes"][1]["coordinateKey"])
    asserts.equals(env, "classes", catalog["nodes"][1]["coordinates"]["classifier"])
    asserts.equals(env, "m.group:multi", catalog["nodes"][2]["coordinateKey"])
    asserts.equals(env, "m.group:multi:jar:runtime", catalog["nodes"][3]["coordinateKey"])
    return unittest.end(env)

runtime_catalog_v3_test = unittest.make(_runtime_catalog_v3_test_impl)

def _runtime_catalog_ignores_non_artifact_inputs_test_impl(ctx):
    env = unittest.begin(ctx)
    catalog = runtime_catalog_for_test({
        "__INPUT_ARTIFACTS_HASH": {"collision.group:same-ga:pom:import": 1, "repositories": 2},
        "artifacts": {"collision.group:same-ga": {"shasums": {"jar": "runtime"}, "version": "1.0"}},
        "dependencies": {},
        "packages": {"collision.group:same-ga": ["collision.group:same-ga"]},
        "version": "3",
    })
    asserts.equals(env, [], catalog["directArtifacts"])
    asserts.equals(env, 1, len(catalog["nodes"]))
    return unittest.end(env)

runtime_catalog_ignores_non_artifact_inputs_test = unittest.make(_runtime_catalog_ignores_non_artifact_inputs_test_impl)

def _maven_target_name_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(env, "com_example_my_artifact_tests", maven_target_name_for_test("com.example:my-artifact:jar:tests"))
    asserts.equals(env, "g_a_special", maven_target_name_for_test("g:a$special"))
    asserts.equals(env, "org_jacoco_org_jacoco_agent_0_8_14", jar_target_name_for_test("org/jacoco/org.jacoco.agent/0.8.14/org.jacoco.agent-0.8.14.jar"))
    asserts.equals(env, "org_jacoco_org_jacoco_agent_0_8_14_runtime", jar_target_name_for_test("org/jacoco/org.jacoco.agent/0.8.14/org.jacoco.agent-0.8.14-runtime.jar"))
    return unittest.end(env)

maven_target_name_test = unittest.make(_maven_target_name_test_impl)

def model_catalogs_test_suite():
    unittest.suite(
        "model_catalogs_tests",
        runtime_catalog_v3_test,
        runtime_catalog_ignores_non_artifact_inputs_test,
        maven_target_name_test,
    )
