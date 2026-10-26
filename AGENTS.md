# AGENTS.md

This file gives coding agents the repository-specific context needed to make
safe changes. It applies to the entire repository.

## Project at a glance

`rules_quarkus` provides Bazel-native rules for building and running Quarkus
applications. The Starlark rules invoke the Quarkus build API through the Java
`Quarkifier`; they do not wrap Maven or Gradle.

- Supported Quarkus versions are exactly `3.39.5` and `3.40.1.1`.
- A Bazel workspace selects one Quarkus version; mixed versions in one
  workspace are not supported.
- Supported Bazel versions are 7, 8, and 9, with Bzlmod only.
- Java 17 or newer is required.
- The generated `@rules_quarkus` repository contains the public macros, model
  catalogs, and the deployment artifacts, downloaded from the workspace's lock.
  The version-specific Quarkifier is a target of this module, built in the
  application's own graph.

## Read the relevant design documentation first

Start with these documents for any non-trivial change:

- `docs/architecture.md`: component boundaries and the build pipeline.
- `docs/developer-guide.md`: repository layout, build commands, tests, and the
  process for adding a Quarkus version.
- `docs/adr/0001-explicit-application-model.md`: authoritative dependency-model
  ownership, validation policy, and public-API invariants.

Then read the feature document matching the area being changed:

- `docs/quarkifier.md` for CLI, model adaptation, augmentation, or Fast JAR
  post-processing.
- `docs/dev-mode.md` for hot reload, Dev UI, classloaders, the dev session's
  generations, incremental Scala.js modules, or watcher behavior.
- `docs/web-assets.md` for `quarkus_web_bundle` and the frontend assets it
  packages.
- `docs/code-generation.md` for `quarkus_codegen` or
  `quarkus_java_library`.
- `docs/extensions.md` for local extension runtime/deployment modules and
  publishing.
- `docs/native-image.md` for host or container native compilation.
- `docs/getting-started.md` for the public API and consumer-facing behavior.

If documentation and implementation disagree, do not silently preserve both
stories. Verify the current behavior, update the implementation and relevant
documentation together, and call out intentional compatibility changes.

## Architectural invariants

### Keep the application model explicit and fail closed

Every normal, dev, test, code-generation, and native lifecycle uses the
versioned `quarkus-bazel-model-v1` contract. Preserve these ownership rules:

- Bazel aspects own local targets, direct edges, sources/resources, outputs,
  build files, and workspace identity.
- The rules_jvm_external v3 lock owns selected external versions, target
  names, artifact files, explicit exclusions, and the Maven runtime and
  deployment graph: an artifact's closure is what the lock records, and an
  artifact the lock does not hold is never resolved.
- Quarkus extension descriptors provide exact deployment coordinates. The
  descriptors are scanned across the lock's whole runtime closure, not only
  its direct artifacts.
- The version-specific Java adapter owns Quarkus flags, platforms,
  capabilities, classloading metadata, and descriptor semantics.

Do not reconstruct lost graph facts from flattened classpaths, parse embedded
POMs as a fallback, guess `-deployment` artifact names, or attach orphan
artifacts to the application root. Unknown fields, missing coordinates,
duplicate identities, dangling edges, missing artifacts, and lifecycle/version
mismatches must remain actionable failures. Model serialization must remain
deterministic and contain only declared action inputs.

Keep the public developer experience unchanged unless the task explicitly
changes it: in BUILD files, applications declare runtime extensions only. They
should not need deployment artifacts, model files, internal aspects/providers,
or test-launcher infrastructure there. The build-time artifacts that
descriptors name (deployment artifacts, conditional dependencies, and the
dev-mode bootstrap) belong in the workspace's `maven.install`;
`quarkus.toolchain(build_time_artifacts = ...)` may declare them, and
repository setup validates every declared entry against the scanned
descriptors.

### Preserve lifecycle semantics

Internal target suffixes such as `_dev` and `_native` do not change application
identity. Conditional dependencies are activated by a mode-aware graph
fixpoint: DEV includes ordinary and conditional-dev dependencies; other modes
include ordinary conditionals only. Do not replace descriptor-driven behavior
with hardcoded extension lists or artifact-name heuristics.

### Treat dev-mode classloaders as a correctness boundary

Dev mode runs in a child JVM with three distinct classpaths: application,
all deployment artifacts, and core deployment infrastructure. Extension
runtime and deployment jars belong in the augment classloader; the dev-jar
manifest contains only bootstrap/core infrastructure and descriptor-marked
parent-first runtime artifacts.

- Do not make the main `smallrye-config` jar parent-first; it contains CDI
  beans and can cause cross-classloader `VerifyError`s.
- When a dev-jar infrastructure jar is also on the application classpath, use
  the application classpath's file: the ApplicationModel references that file,
  and two copies of one jar break class identity across classloaders.
- Preserve Maven directory layout under generated deployment artifacts; Dev UI
  extracts resource versions from those paths.
- Preserve the version-specific application-model serialization: every
  supported minor serializes the model as JSON through its adapter.
- Dev mode builds in the caller's configuration; its deps only disable
  coverage. Hot reload rebuilds the dev target in that configuration and in the
  output base that built the application model, so configuration-affecting
  launch flags belong in `dev_build_args` too.

### Keep Bazel the only compiler in dev mode

Bazel builds every class directory dev mode runs, and Quarkus only reads them.
Dev modules declare no source or resource paths, so Quarkus never compiles
into, copies into, or deletes from a module. The application runs from the dev
session's `current` generation, hard links of one complete build, and
`GenerationSwitch` moves `current` only inside Quarkus's scan. Do not give
Quarkus workspace source paths, and do not point a module at Bazel's class
directories directly: Bazel deletes an action's outputs before rerunning it,
so a scan could read a half-written build. Continuous tests are held to the
same rule: their classes run from the generation's merged `test-classes`, and
`GenerationSwitch` tells Quarkus to look for changes by writing the
`test-trigger` file it watches, never by giving it test sources.

### Keep code generation hermetic

Quarkus code generation runs before Java compilation from declared inputs and
the explicit lifecycle-specific model. Inputs must be checked-in files beneath
declared, package-relative source roots; rule-generated inputs are not valid.
Outputs must remain deterministic. Java is the supported generated language;
Kotlin and Scala generation should fail clearly rather than being ignored.

### Preserve packaging and native-build guarantees

Fast JAR post-processing must keep boot/main classification, stable Maven-style
jar names, regenerated application metadata, and runner manifest classpaths in
sync. All JVM layouts use a stable `quarkus-run.jar` path.

Host native builds and container native builds are mutually exclusive.
Container builder images should be pinned by digest because mutable tags are
unsafe with Bazel action caching, and container builds always produce Linux
binaries.

## Build and validation

Run the narrowest checks that cover the change, then expand for cross-cutting
work. From the repository root:

```bash
bazel build //quarkifier:quarkifier_3_39_deploy.jar
bazel build //quarkifier:quarkifier_3_40_deploy.jar
bazel test //quarkifier:quarkifier_test_3_39
bazel test //quarkifier:quarkifier_test_3_40
bazel build //...
```

The external-consumer smoke suite is a separate workspace:

```bash
cd e2e/smoke
bazel test //...
```

Example workspaces also use `local_path_override` and are useful for manual
lifecycle checks. They build the Quarkifier from this checkout as part of the
application's graph, so run targets such as `//:helloworld`,
`//:helloworld_dev`, and the relevant tests from `examples/helloworld_3_39` or
`examples/helloworld_3_40` directly.

Changes to version-specific Quarkus APIs must cover every adapter unless the
behavior is intentionally version-gated. Changes to repository setup or public
rules should be checked from an external example or smoke workspace, not only
with unit tests in the root workspace. Dev-mode changes should be exercised by
starting the dev target and verifying startup plus hot reload; native changes
require the corresponding GraalVM or container environment.

## Adding a Quarkus minor version

Treat a new minor as a coordinated change: update `SUPPORTED_VERSIONS`, add and
pin its Maven repository and lock file, add versioned Quarkifier library/binary/
test/static-analysis targets, add version-specific adapter sources, build and
test the deploy jar, and add an example workspace. Follow the complete checklist
in `docs/developer-guide.md`.
