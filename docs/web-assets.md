# Bazel frontend assets

`quarkus_web_bundle` in `quarkus/web/defs.bzl` runs the frontend build as ordinary,
cacheable Bazel actions. It uses Aspect rules_js for npm inputs and the Tailwind
CLI, and rules_esbuild for the hermetic esbuild toolchain. Quarkus augmentation
never installs frontend dependencies or bundles JavaScript.

Declare the bundle in the npm workspace package containing the linked
`node_modules` tree. Supply the frontend source directory, linked browser module,
Tailwind CLI `js_binary`, stylesheet, and HTML shell. The shell must contain exactly one
`<!-- bazel:web-assets -->` marker. The build replaces it with references to the
hashed JS and CSS entry files and packages the output as standard Quarkus
`META-INF/resources` assets, with an equivalent `templates/spa.html` shell for a
Qute SPA fallback resource.

The result is a normal `JavaInfo` dependency for application and browser-test
rules. All lifecycles consume the same resource layout and bundling pipeline.
Bundling is selected by Bazel's `compilation_mode`: `opt` uses production
`NODE_ENV`, minification, and the consumer's optimized browser link; `fastbuild`
and `dbg` use development flags and the development link. `minify = True` minifies
the JS and CSS in every mode, without changing `NODE_ENV` or the link. Production Quarkus
packaging rules enforce `opt` through an incoming configuration transition.
Dev and tests share the development bundle, with one cached action per set of
inputs and compilation mode. Frontend inputs are declared: an edit rebuilds the
affected action, while unchanged test classes reuse the bundle artifact.

Consumers own their npm lock, Node toolchain, Tailwind version and esbuild
version. Tools must be Bazel-managed; no package manager or bundler is invoked
from the developer's PATH. The rule does not support Quarkus Web Bundler tags.

The resource-library boundary removes the Quarkus code-generation lifecycle from
frontend actions while preserving `compilation_mode`. Thus dev and tests share
one development link and bundle, while production remains optimized. JavaScript,
stylesheet and HTML inputs are staged separately: Tailwind scans the declared
JavaScript and HTML trees, and esbuild depends on the compiled CSS rather than
the HTML source. An HTML change that leaves CSS unchanged reuses the JS bundle.
The archive maps esbuild's output directory directly to
`META-INF/resources/_static`; HTML assembly writes only shells and metadata.
Esbuild's public path is `/_static/`, so imported file assets such as WASM resolve
to their packaged URLs from any application route.

Consumers can declare `quarkus_web_bundle_tests` from `quarkus/web/defs_test.bzl`
against a local bundle to verify lifecycle cache sharing, JavaScript input
isolation, and direct archive packaging using their actual frontend targets.
