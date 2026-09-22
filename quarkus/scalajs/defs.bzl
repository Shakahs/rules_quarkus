"""An incrementally compiled and linked Scala.js module, for the dev loop.

`scala_js_dev_module` compiles a module's sources with zinc and links the result with its
dependencies' IR into one ES module. The action keeps its state — the analysis, the class and
IR files, the sources extracted from source jars — in a directory beside its output, so an
invocation after an edit recompiles what the edit touched and relinks from the IR already on
disk. The state directory is the contract; the action runs as a persistent worker when the
build asks for one (`--strategy=ScalaJSDevModule=worker`), which additionally keeps the
compiler's class loaders and the linker's IR cache warm.

The compiler, its zinc bridge, zinc and the linker are the consumer's: a
`scala_js_dev_toolchain` names them, at the versions matching the consumer's Scala and
Scala.js libraries, and is registered for `//quarkus/scalajs:toolchain_type`.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")

TOOLCHAIN_TYPE = Label("//quarkus/scalajs:toolchain_type")

_MAIN_CLASS = "com.clementguillot.scalajs.dev.ScalaJsDevMain"

def _scala_js_dev_toolchain_impl(ctx):
    bridge_jars = ctx.attr.bridge[JavaInfo].runtime_output_jars
    if len(bridge_jars) != 1:
        fail("scala_js_dev_toolchain: 'bridge' must be a single jar; {} provides {}".format(ctx.attr.bridge.label, len(bridge_jars)))
    compiler_jars = depset(
        transitive = [ctx.attr.bridge[JavaInfo].transitive_runtime_jars] +
                     [target[JavaInfo].transitive_runtime_jars for target in ctx.attr.compiler],
    )
    runtime_jars = depset(
        transitive = [target[JavaInfo].transitive_runtime_jars for target in ctx.attr.zinc + ctx.attr.linker],
    )
    return [platform_common.ToolchainInfo(
        scala_version = ctx.attr.scala_version,
        bridge = bridge_jars[0],
        compiler_jars = compiler_jars,
        runtime_jars = runtime_jars,
    )]

scala_js_dev_toolchain = rule(
    implementation = _scala_js_dev_toolchain_impl,
    doc = """\
The compiler, bridge, zinc and linker a `scala_js_dev_module` is built with. Register the
`toolchain()` wrapping it for `@rules_quarkus//quarkus/scalajs:toolchain_type`.
""",
    attrs = {
        "scala_version": attr.string(
            mandatory = True,
            doc = "The compiler's version, e.g. \"3.9.0\".",
        ),
        "bridge": attr.label(
            mandatory = True,
            providers = [JavaInfo],
            doc = """\
The zinc compiler bridge for that compiler (`org.scala-lang:scala3-sbt-bridge` at the
compiler's version). Its dependency closure is the compiler, so `compiler` is needed only
for jars the bridge does not depend on.
""",
        ),
        "compiler": attr.label_list(
            providers = [JavaInfo],
            doc = "Jars to load with the compiler beyond the bridge's closure.",
        ),
        "zinc": attr.label_list(
            mandatory = True,
            providers = [JavaInfo],
            doc = "zinc (`org.scala-sbt:zinc_2.13`), at a version the bridge was built against.",
        ),
        "linker": attr.label_list(
            mandatory = True,
            providers = [JavaInfo],
            doc = """\
The Scala.js linker (`org.scala-js:scalajs-linker_2.13` and `scalajs-logging_2.13`), at a
version no older than the Scala.js libraries whose IR it links.
""",
        ),
    },
)

def _scala_js_dev_module_impl(ctx):
    toolchain = ctx.toolchains[TOOLCHAIN_TYPE]
    java_runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]
    tool_jar = ctx.file._tool

    # The linker names its own output files, so the output is a directory. The state lives
    # beside it: undeclared, so Bazel neither clears it before the action nor expects it
    # after, and gone with `bazel clean`.
    output = ctx.actions.declare_directory(ctx.label.name + ".js")
    state_dir = output.dirname + "/" + ctx.label.name + ".state"

    sources = [f for f in ctx.files.srcs if f.extension in ("scala", "java")]
    source_jars = [f for f in ctx.files.srcs if f.extension in ("srcjar", "jar")]
    unexpected = [f for f in ctx.files.srcs if f not in sources and f not in source_jars]
    if unexpected:
        fail("scala_js_dev_module: srcs must be Scala sources or source jars; got {}".format(unexpected))

    # The compile classpath is what rules_scala compiles against in its transitive dependency
    # mode; the link classpath is the runtime closure, whose jars carry the IR.
    compile_jars = depset(
        order = "preorder",
        transitive = [dep[JavaInfo].transitive_compile_time_jars for dep in ctx.attr.deps],
    )
    link_jars = depset(transitive = [dep[JavaInfo].transitive_runtime_jars for dep in ctx.attr.deps])

    jvm_args = ctx.actions.args()
    jvm_args.add_all(ctx.attr.jvm_flags)
    jvm_args.add("-cp")
    jvm_args.add_joined(depset([tool_jar], transitive = [toolchain.runtime_jars]), join_with = ctx.configuration.host_path_separator)
    jvm_args.add(_MAIN_CLASS)

    tool_args = ctx.actions.args()
    tool_args.set_param_file_format("multiline")
    tool_args.use_param_file("@%s", use_always = True)
    tool_args.add("--state-dir", state_dir)
    tool_args.add("--output-dir", output.path)
    tool_args.add("--main-class", ctx.attr.main_class)
    tool_args.add("--scala-version", toolchain.scala_version)
    tool_args.add("--bridge-jar", toolchain.bridge)
    tool_args.add_all(toolchain.compiler_jars, before_each = "--compiler-jar")
    tool_args.add_all(sources, before_each = "--source")
    tool_args.add_all(source_jars, before_each = "--source-jar")
    tool_args.add_all(compile_jars, before_each = "--classpath-jar")
    tool_args.add_all(link_jars, before_each = "--link-jar")
    tool_args.add_all(ctx.attr.scalacopts, before_each = "--scalac-option")

    ctx.actions.run(
        executable = java_runtime.java_executable_exec_path,
        arguments = [jvm_args, tool_args],
        inputs = depset(
            sources + source_jars + [toolchain.bridge],
            transitive = [compile_jars, link_jars, toolchain.compiler_jars],
        ),
        tools = depset([tool_jar], transitive = [java_runtime.files, toolchain.runtime_jars]),
        outputs = [output],
        mnemonic = "ScalaJSDevModule",
        progress_message = "Compiling and linking Scala.js module %{label}",
        # The state directory is local by nature: no sandbox may hide it from the action and
        # no remote executor has it.
        execution_requirements = {
            "no-remote": "1",
            "no-sandbox": "1",
            "requires-worker-protocol": "proto",
            "supports-workers": "1",
        },
    )

    return [DefaultInfo(files = depset([output]))]

scala_js_dev_module = rule(
    implementation = _scala_js_dev_module_impl,
    doc = """\
A Scala.js module compiled incrementally and linked into one ES module, for the dev loop.

The output is a directory holding the linked module (`main.js`). Point `dev_project_files`
at it to have dev mode mirror it into the application's project directory.
""",
    attrs = {
        "srcs": attr.label_list(
            allow_files = [".scala", ".java", ".srcjar", ".jar"],
            mandatory = True,
            doc = "The module's Scala sources and source jars.",
        ),
        "deps": attr.label_list(
            providers = [JavaInfo],
            doc = "Scala.js libraries the module compiles against and links with.",
        ),
        "main_class": attr.string(
            mandatory = True,
            doc = "The object whose no-argument `main` the module calls when loaded.",
        ),
        "scalacopts": attr.string_list(
            doc = "Compiler options beyond `-scalajs`.",
        ),
        "jvm_flags": attr.string_list(
            doc = "Flags for the JVM running the compiler and the linker, e.g. [\"-Xmx8g\"].",
        ),
        "_java_runtime": attr.label(
            default = "@bazel_tools//tools/jdk:current_java_runtime",
            cfg = "exec",
        ),
        "_tool": attr.label(
            default = Label("//scalajs:scalajs_dev_deploy.jar"),
            allow_single_file = True,
            cfg = "exec",
        ),
    },
    toolchains = [TOOLCHAIN_TYPE],
)
