"""Cached frontend build and standard Quarkus classpath resources.

Call in the npm workspace package so esbuild and Tailwind resolve the declared
node_modules tree. Quarkus never installs packages or invokes a frontend build.
"""

load("@aspect_bazel_lib//lib:copy_to_directory.bzl", "copy_to_directory")
load("@aspect_bazel_lib//lib:copy_file.bzl", "copy_file")
load("@aspect_rules_esbuild//esbuild:defs.bzl", "esbuild")
load("@aspect_rules_js//js:defs.bzl", "js_run_binary")
load("@bazel_skylib//rules:write_file.bzl", "write_file")
load("@rules_java//java:java_import.bzl", "java_import")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("@rules_pkg//pkg:mappings.bzl", "REMOVE_BASE_DIRECTORY", "pkg_files", "strip_prefix")
load("@rules_pkg//pkg:zip.bzl", "pkg_zip")

_LIFECYCLE = str(Label("//quarkus/private:codegen_lifecycle"))

def _frontend_lifecycle_impl(_settings, _attr):
    return {_LIFECYCLE: "main"}

_frontend_lifecycle = transition(
    implementation = _frontend_lifecycle_impl,
    inputs = [],
    outputs = [_LIFECYCLE],
)

def _frontend_library_impl(ctx):
    library = ctx.attr.library
    return [library[JavaInfo], library[DefaultInfo]]

_frontend_library = rule(
    implementation = _frontend_library_impl,
    cfg = _frontend_lifecycle,
    attrs = {
        "library": attr.label(mandatory = True, providers = [JavaInfo]),
        "_allowlist_function_transition": attr.label(
            default = "@bazel_tools//tools/allowlists/function_transition_allowlist",
        ),
    },
)

def quarkus_web_bundle(name, srcs, source_root, stylesheet, linked_module, linked_module_roots, node_modules, tailwind, html, external = [], minify = None, visibility = None):
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
            "loader": {".wasm": "file", ".svg": "file", ".woff2": "file", ".png": "file"},
        },
    )
    resources = name + "_resources"
    js_run_binary(
        name = resources,
        tool = Label("//quarkus/web:assemble_resources"),
        copy_srcs_to_bin = False,
        srcs = [":" + name + "_esbuild", html],
        outs = [
            resources + "/META-INF/resources/index.html",
            resources + "/templates/spa.html",
            resources + "/META-INF/beans.xml",
        ],
        args = ["$(execpath :" + name + "_esbuild)", "$(execpath " + html + ")", "$(RULEDIR)/" + resources],
        mnemonic = "QuarkusWebResources",
    )
    pkg_files(
        name = name + "_files",
        srcs = [":" + resources],
        strip_prefix = strip_prefix.from_pkg(resources),
    )
    pkg_files(
        name = name + "_assets",
        srcs = [":" + name + "_esbuild"],
        prefix = "META-INF/resources/_static",
        renames = {":" + name + "_esbuild": REMOVE_BASE_DIRECTORY},
    )
    pkg_zip(
        name = name + "_jar",
        srcs = [":" + name + "_files", ":" + name + "_assets"],
        package_file_name = name + ".jar",
    )
    java_import(
        name = name + "_library",
        jars = [":" + name + "_jar"],
    )
    _frontend_library(
        name = name,
        library = ":" + name + "_library",
        visibility = visibility,
    )
