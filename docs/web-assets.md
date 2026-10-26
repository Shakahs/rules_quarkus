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
inputs and compilation mode, unless dev mode takes the dev loop's variant below. Frontend inputs are declared: an edit rebuilds the
affected action, while unchanged test classes reuse the bundle artifact.

Consumers own their npm lock, Node toolchain, Tailwind version and esbuild
version. Tools must be Bazel-managed; no package manager or bundler is invoked
from the developer's PATH. The rule does not support Quarkus Web Bundler tags.

Frontend actions build in the consumer's configuration and vary only with
`compilation_mode`. Thus dev and tests share one development link and bundle,
while production remains optimized. JavaScript,
stylesheet and HTML inputs are staged separately: Tailwind scans the declared
JavaScript and HTML trees, and esbuild depends on the compiled CSS rather than
the HTML source. An HTML change that leaves CSS unchanged reuses the JS bundle.
The archive maps esbuild's output directory directly to
`META-INF/resources/_static`; HTML assembly writes only shells and metadata.
Esbuild's public path is `/_static/`, so imported file assets such as WASM resolve
to their packaged URLs from any application route.

## The dev loop's bundle

Rebuilding the whole bundle on every save costs a dev loop more than esbuild's own work. The
npm graph behind a typical application is thousands of modules, and rules_esbuild's sandbox
plugin answers each of their resolutions with a second one, a round trip between esbuild and
Node, so that graph dominates the build even when only the application's modules changed. A
packaged bundle on the classpath also changes the application's archive, and with it the
Qute shell naming its hashed files, so every save restarts the application too.

`quarkus_web_bundle` therefore also declares a variant for dev mode, which an application
names in `quarkus_app`'s `dev_deps`:

- `<name>_dev_vendor` bundles the npm specifiers the application's modules import
  (`vendor_modules.mjs` reads them off the staged sources, without the `external` ones) into
  `vendor.js`, which publishes each module on a page-global registry as an ES module record.
  Its entry changes only when that set of specifiers does, so the action is a cache hit
  otherwise.
- `<name>_dev_app` bundles the application's modules into `app.js` and `app.css`. Its esbuild
  configuration (`dev_esbuild_config.mjs`) resolves every npm specifier to a CommonJS shim
  reading the registry, so this build never walks node_modules. esbuild's interop then gives a
  namespace, named or default import exactly what the record holds, whether the package is
  CommonJS or an ES module.
- `<name>_dev` is a resource library holding only the HTML shell, which loads `vendor.js`
  before `app.js`. `<name>_dev_static` is the two bundles' files, for `dev_project_files`.

Neither dev bundle splits, and their entries keep stable names, so a rebuild leaves the shell,
and with it the classpath, unchanged. Quarkus's `quarkus.http.static-dir` registers the files of
a directory when the application starts and serves their current contents from then on: an
application mirrors `<name>_dev_static` into a directory of its own and serves it at
`/_static`, and a frontend edit reaches the browser on its next load, with no restart. An asset
whose name changes (a new hashed image, say) is served once the application next restarts.

```starlark
quarkus_app(
    name = "app",
    deps = [":lib", "//web:frontend"],
    dev_deps = [":lib", "//web:frontend_dev"],
    dev_project_files = {"//web:frontend_dev_static": "app/target/dev-static"},
)
```

```properties
%dev.quarkus.http.static-dir.path=app/target/dev-static
%dev.quarkus.http.static-dir.endpoint=/_static
```

The path is relative to the workspace root, the directory dev mode runs in. Browser tests and
production builds keep the packaged bundle.

Consumers can declare `quarkus_web_bundle_tests` from `quarkus/web/defs_test.bzl`
against a local bundle to verify the separate optimized production bundle, JavaScript input
isolation, and direct archive packaging using their actual frontend targets.
