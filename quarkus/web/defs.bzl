"""Cached frontend build and standard Quarkus classpath resources.

Call in the npm workspace package so esbuild and Tailwind resolve the declared
node_modules tree. Quarkus never installs packages or invokes a frontend build.
"""

load("@aspect_bazel_lib//lib:copy_to_directory.bzl", "copy_to_directory")
load("@aspect_rules_esbuild//esbuild:defs.bzl", "esbuild")
load("@aspect_rules_js//js:defs.bzl", "js_run_binary")
load("@bazel_skylib//rules:write_file.bzl", "write_file")
load("@rules_java//java:java_import.bzl", "java_import")
load("@rules_pkg//pkg:mappings.bzl", "pkg_files", "strip_prefix")
load("@rules_pkg//pkg:zip.bzl", "pkg_zip")

def quarkus_web_bundle(name, srcs, source_root, linked_module, linked_module_roots, node_modules, tailwind, html, external = [], visibility = None):
    """Build Tailwind/ES modules once and export a JavaInfo resource archive.

    Args:
        name: Java resource library target.
        srcs: Frontend sources, including index.js and style.css.
        source_root: Workspace-relative directory containing these sources.
        linked_module: Directory artifact with main.js and any linked chunks.
        linked_module_roots: Workspace-relative possible directory artifact paths.
        node_modules: rules_js linked npm dependency target.
        tailwind: Hermetic js_binary for the Tailwind CLI.
        html: HTML shell containing <!-- bazel:web-assets -->.
        external: Optional runtime imports left external, such as guarded Node-only fallbacks.
        visibility: Visibility of the resulting resource library.
    """
    production = Label("//quarkus/web:production")
    stage = name + "_sources"
    copy_to_directory(
        name = stage,
        srcs = srcs + [linked_module, html],
        out = stage,
        root_paths = [source_root],
        replace_prefixes = {root: "scalajs" for root in linked_module_roots},
        include_external_repositories = ["*"],
    )
    css = name + "_tailwind.css"
    js_run_binary(
        name = name + "_tailwind",
        tool = tailwind,
        chdir = "../../../",
        srcs = [":" + stage, node_modules],
        outs = [css],
        args = [
            "--input", "$(execpath :" + stage + ")/style.css",
            "--output", "$(execpath :" + css + ")",
        ] + select({production: ["--minify"], "//conditions:default": []}),
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
        minify = select({production: True, "//conditions:default": False}),
        sourcemap = False,
        define = select({
            production: {"process.env.NODE_ENV": "\"production\""},
            "//conditions:default": {"process.env.NODE_ENV": "\"development\""},
        }),
        config = {
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
        out_dirs = [resources],
        args = ["$(execpath :" + name + "_esbuild)", "$(execpath " + html + ")", "$(RULEDIR)/" + resources],
        mnemonic = "QuarkusWebResources",
    )
    pkg_files(
        name = name + "_files",
        srcs = [":" + resources],
        strip_prefix = strip_prefix.from_pkg(resources),
    )
    pkg_zip(
        name = name + "_jar",
        srcs = [":" + name + "_files"],
        package_file_name = name + ".jar",
    )
    java_import(
        name = name,
        jars = [":" + name + "_jar"],
        visibility = visibility,
    )
