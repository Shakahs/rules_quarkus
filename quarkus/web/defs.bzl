"""Cached frontend build and standard Quarkus classpath resources.

Call in the npm workspace package so esbuild and Tailwind resolve the declared
node_modules tree. Quarkus never installs packages or invokes a frontend build.
"""

load("@bazel_lib//lib:copy_to_directory.bzl", "copy_to_directory")
load("@bazel_lib//lib:copy_file.bzl", "copy_file")
load("@aspect_rules_esbuild//esbuild:defs.bzl", "esbuild")
load("@aspect_rules_js//js:defs.bzl", "js_run_binary")
load("@bazel_skylib//rules:write_file.bzl", "write_file")
load("@rules_java//java:java_import.bzl", "java_import")
load("@rules_pkg//pkg:mappings.bzl", "REMOVE_BASE_DIRECTORY", "pkg_files", "strip_prefix")
load("@rules_pkg//pkg:zip.bzl", "pkg_zip")

_ASSET_LOADERS = {".wasm": "file", ".svg": "file", ".woff2": "file", ".png": "file"}

def _resource_library(name, bundles, html, assets, visibility, static_assets = []):
    """The shell and metadata of `bundles`, plus `assets` under `_static`, as a resource library."""
    resources = name + "_resources"
    js_run_binary(
        name = resources,
        tool = Label("//quarkus/web:assemble_resources"),
        copy_srcs_to_bin = False,
        srcs = bundles + [html],
        outs = [
            resources + "/META-INF/resources/index.html",
            resources + "/templates/spa.html",
            resources + "/META-INF/beans.xml",
        ],
        args = ["$(execpath " + html + ")", "$(RULEDIR)/" + resources] + ["$(execpath " + bundle + ")" for bundle in bundles],
        mnemonic = "QuarkusWebResources",
    )
    pkg_files(
        name = name + "_files",
        srcs = [":" + resources],
        strip_prefix = strip_prefix.from_pkg(resources),
    )
    if assets:
        pkg_files(
            name = name + "_assets",
            srcs = assets,
            prefix = "META-INF/resources/_static",
            renames = {asset: REMOVE_BASE_DIRECTORY for asset in assets},
        )
    if static_assets:
        pkg_files(
            name = name + "_static_assets",
            srcs = static_assets,
            prefix = "META-INF/resources/_static",
            strip_prefix = strip_prefix.from_pkg(),
        )
    pkg_zip(
        name = name + "_jar",
        srcs = [":" + name + "_files"] + ([":" + name + "_assets"] if assets else []) + ([":" + name + "_static_assets"] if static_assets else []),
        package_file_name = name + ".jar",
    )
    java_import(
        name = name,
        jars = [":" + name + "_jar"],
        visibility = visibility,
    )

def quarkus_web_bundle(name, srcs, source_root, stylesheet, linked_module, linked_module_roots, node_modules, tailwind, html, external = [], minify = None, visibility = None, static_assets = []):
    """Build Tailwind/ES modules once and export a JavaInfo resource archive.

    Args:
        name: Java resource library target.
        srcs: JavaScript sources and their assets, including index.js.
        source_root: Workspace-relative directory containing these sources.
        stylesheet: Tailwind stylesheet, separate from JavaScript inputs.
        linked_module: Directory artifact with main.js and any linked chunks.
        linked_module_roots: Workspace-relative possible directory artifact paths.
        node_modules: rules_js linked npm dependency target.
        tailwind: Hermetic js_binary for the Tailwind CLI.
        html: HTML shell containing <!-- bazel:web-assets -->.
        external: Optional runtime imports left external, such as guarded Node-only fallbacks.
        minify: Whether esbuild and Tailwind minify their output in every compilation mode.
            Unset, they minify only in `opt`.
        visibility: Visibility of the resulting resource library.
        static_assets: Additional files served under `_static`, such as separately loaded npm workers.
    """
    production = Label("//quarkus/web:production")
    if minify == None:
        minify_js = select({production: True, "//conditions:default": False})
        minify_css = select({production: ["--minify"], "//conditions:default": []})
    else:
        minify_js = minify
        minify_css = ["--minify"] if minify else []
    stage = name + "_sources"
    copy_to_directory(
        name = stage,
        srcs = srcs + [linked_module],
        out = stage,
        root_paths = [source_root],
        replace_prefixes = {root: "scalajs" for root in linked_module_roots},
        include_external_repositories = ["*"],
    )
    # Keep HTML and CSS out of the JavaScript staging tree. Tailwind still scans
    # HTML and linked Scala.js markup; esbuild depends only on the resulting CSS.
    copy_file(
        name = name + "_stylesheet",
        src = stylesheet,
        out = name + "_styles/style.css",
    )
    copy_file(
        name = name + "_html",
        src = html,
        out = name + "_html/index.html",
    )
    css_entry = name + "_tailwind_entry.css"
    write_file(
        name = name + "_tailwind_entry",
        out = css_entry,
        content = [
            "@import './" + name + "_styles/style.css';",
            "@source './" + stage + "';",
            "@source './" + name + "_html';",
        ],
    )
    css = name + "_tailwind.css"
    js_run_binary(
        name = name + "_tailwind",
        tool = tailwind,
        chdir = "../../../",
        srcs = [":" + stage, ":" + name + "_stylesheet", ":" + name + "_html", ":" + css_entry, node_modules],
        outs = [css],
        args = [
            "--input", "$(execpath :" + css_entry + ")",
            "--output", "$(execpath :" + css + ")",
        ] + minify_css,
        mnemonic = "QuarkusTailwind",
    )
    entry = name + "_entry.js"
    write_file(
        name = name + "_entry",
        out = entry,
        content = [
            "import './" + stage + "/index.js';",
            "import './" + css + "';",
        ],
    )
    esbuild(
        name = name + "_esbuild",
        entry_point = ":" + entry,
        srcs = [":" + stage, ":" + css],
        deps = [node_modules],
        external = external,
        output_dir = True,
        splitting = True,
        format = "esm",
        target = ["es2022"],
        minify = minify_js,
        sourcemap = False,
        define = select({
            production: {"process.env.NODE_ENV": "\"production\""},
            "//conditions:default": {"process.env.NODE_ENV": "\"development\""},
        }),
        config = {
            "publicPath": "/_static/",
            "entryNames": "app-[hash]",
            "chunkNames": "chunks/[name]-[hash]",
            "assetNames": "assets/[name]-[hash]",
            "loader": _ASSET_LOADERS,
        },
    )
    _resource_library(name, [":" + name + "_esbuild"], html, [":" + name + "_esbuild"], visibility, static_assets)

    # The dev loop's variant: the npm graph and the application as two bundles, served from the
    # project directory rather than packaged. `<name>_dev` is a resource library holding only the
    # HTML shell, and `<name>_dev_static` the bundle files, which a dev application mirrors into
    # a directory its HTTP server serves (`dev_project_files`, `quarkus.http.static-dir`).
    #
    # The vendor bundle covers the npm specifiers the application's modules import, and is
    # rebuilt only when that set changes. The application bundle resolves each specifier to the
    # vendor bundle's registry instead of node_modules, so a save re-bundles only the
    # application's own modules. Neither bundle splits, and their entries keep stable names: the
    # files a running application serves are the ones it registered when it started, read
    # afresh on every request, so a rebuild reaches the browser without a restart.
    vendor_entry = name + "_dev_vendor_entry.js"
    js_run_binary(
        name = name + "_dev_vendor_entry",
        tool = Label("//quarkus/web:vendor_modules"),
        copy_srcs_to_bin = False,
        srcs = [":" + stage],
        outs = [vendor_entry],
        args = ["$(execpath :" + stage + ")", "$(RULEDIR)/" + vendor_entry] + external,
        mnemonic = "QuarkusWebVendorModules",
    )
    esbuild(
        name = name + "_dev_vendor",
        entry_point = ":" + vendor_entry,
        deps = [node_modules],
        external = external,
        output_dir = True,
        format = "esm",
        target = ["es2022"],
        minify = minify_js,
        sourcemap = False,
        define = {"process.env.NODE_ENV": "\"development\""},
        config = {
            "publicPath": "/_static/",
            "entryNames": "vendor",
            "assetNames": "assets/[name]-[hash]",
            "loader": _ASSET_LOADERS,
        },
    )
    # rules_esbuild copies its config into the output tree, which it refuses for a source file
    # of another repository: the configuration is this package's own copy.
    copy_file(
        name = name + "_dev_esbuild_config",
        src = Label("//quarkus/web:dev_esbuild_config.mjs"),
        out = name + "_dev_esbuild_config.mjs",
    )
    esbuild(
        name = name + "_dev_app",
        entry_point = ":" + entry,
        srcs = [":" + stage, ":" + css],
        external = external,
        output_dir = True,
        format = "esm",
        target = ["es2022"],
        minify = minify_js,
        sourcemap = False,
        define = {"process.env.NODE_ENV": "\"development\""},
        config = ":" + name + "_dev_esbuild_config",
    )
    dev_bundles = [":" + name + "_dev_vendor", ":" + name + "_dev_app"]
    _resource_library(name + "_dev", dev_bundles, html, [], visibility)
    native.filegroup(
        name = name + "_dev_static",
        srcs = dev_bundles + static_assets,
        visibility = visibility,
    )
