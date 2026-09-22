package com.clementguillot.scalajs.dev;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One module to compile and link: what the rule passes on the command line.
 *
 * @param stateDir where the incremental state lives, kept across invocations
 * @param outputDir the directory the linked module is written to, emptied by Bazel beforehand
 * @param mainClass the object whose no-argument {@code main} the module calls on load
 * @param scalaVersion the compiler's version, as zinc labels its instance
 * @param compilerJars the compiler and its dependencies, loaded in a class loader of their own
 * @param bridgeJar the zinc compiler bridge built for that compiler
 * @param sources the module's Scala sources
 * @param sourceJars source jars whose entries are compiled with the sources
 * @param classpathJars the compile classpath
 * @param linkJars the jars whose Scala.js IR is linked with the module's own
 * @param scalacOptions options for the compiler, beyond {@code -scalajs}
 */
record ModuleRequest(
    Path stateDir,
    Path outputDir,
    String mainClass,
    String scalaVersion,
    List<Path> compilerJars,
    Path bridgeJar,
    List<Path> sources,
    List<Path> sourceJars,
    List<Path> classpathJars,
    List<Path> linkJars,
    List<String> scalacOptions) {

  private static final String STATE_DIR = "--state-dir";
  private static final String OUTPUT_DIR = "--output-dir";
  private static final String MAIN_CLASS = "--main-class";
  private static final String SCALA_VERSION = "--scala-version";
  private static final String BRIDGE_JAR = "--bridge-jar";
  private static final String COMPILER_JAR = "--compiler-jar";
  private static final String SOURCE = "--source";
  private static final String SOURCE_JAR = "--source-jar";
  private static final String CLASSPATH_JAR = "--classpath-jar";
  private static final String LINK_JAR = "--link-jar";
  private static final String SCALAC_OPTION = "--scalac-option";

  /** Every flag, and whether it may be given more than once. */
  private static final Map<String, Boolean> REPEATABLE =
      Map.ofEntries(
          Map.entry(STATE_DIR, false),
          Map.entry(OUTPUT_DIR, false),
          Map.entry(MAIN_CLASS, false),
          Map.entry(SCALA_VERSION, false),
          Map.entry(BRIDGE_JAR, false),
          Map.entry(COMPILER_JAR, true),
          Map.entry(SOURCE, true),
          Map.entry(SOURCE_JAR, true),
          Map.entry(CLASSPATH_JAR, true),
          Map.entry(LINK_JAR, true),
          Map.entry(SCALAC_OPTION, true));

  static ModuleRequest parse(List<String> args) {
    Map<String, List<String>> values = new HashMap<>();
    for (int i = 0; i < args.size(); i += 2) {
      String flag = args.get(i);
      Boolean repeatable = REPEATABLE.get(flag);
      if (repeatable == null) {
        throw new IllegalArgumentException("Unknown argument: " + flag);
      }
      if (i + 1 >= args.size()) {
        throw new IllegalArgumentException("Missing value for " + flag);
      }
      List<String> collected = values.computeIfAbsent(flag, key -> new ArrayList<>());
      if (!collected.isEmpty() && !repeatable) {
        throw new IllegalArgumentException(flag + " given twice");
      }
      collected.add(args.get(i + 1));
    }
    return new ModuleRequest(
        path(single(values, STATE_DIR)),
        path(single(values, OUTPUT_DIR)),
        single(values, MAIN_CLASS),
        single(values, SCALA_VERSION),
        paths(values, COMPILER_JAR),
        path(single(values, BRIDGE_JAR)),
        paths(values, SOURCE),
        paths(values, SOURCE_JAR),
        paths(values, CLASSPATH_JAR),
        paths(values, LINK_JAR),
        List.copyOf(values.getOrDefault(SCALAC_OPTION, List.of())));
  }

  private static String single(Map<String, List<String>> values, String flag) {
    List<String> found = values.get(flag);
    if (found == null) {
      throw new IllegalArgumentException("Missing required argument " + flag);
    }
    return found.get(0);
  }

  private static Path path(String value) {
    return Path.of(value).toAbsolutePath();
  }

  private static List<Path> paths(Map<String, List<String>> values, String flag) {
    return values.getOrDefault(flag, List.of()).stream().map(ModuleRequest::path).toList();
  }
}
