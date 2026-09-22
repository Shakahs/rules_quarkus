package com.clementguillot.scalajs.dev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The directory that carries a module's state from one invocation to the next.
 *
 * <p>It is keyed by the fingerprint of what produced it: a different compiler, or a different
 * layout of this tool, starts over rather than reading products it cannot trust. Everything
 * else — a changed source, a changed classpath, changed options — zinc detects itself from the
 * analysis it stored, and recompiles exactly what that requires.
 */
final class StateDirectory {

  /** Bumped whenever the layout below changes shape. */
  private static final String LAYOUT_VERSION = "1";

  private final Path directory;

  StateDirectory(Path directory) {
    this.directory = directory;
  }

  /** Class files, TASTy and Scala.js IR the compiler writes; the linker's first IR container. */
  Path classes() {
    return directory.resolve("classes");
  }

  /** The zinc analysis: what was compiled from what, and the stamps of every input. */
  Path analysis() {
    return directory.resolve("zinc").resolve("analysis.bin");
  }

  /** Where zinc's transactional class-file manager backs products up during a compile. */
  Path backup() {
    return directory.resolve("zinc").resolve("backup");
  }

  /** Zinc's own cache file. */
  Path zincCache() {
    return directory.resolve("zinc").resolve("cache");
  }

  /** Sources extracted from source jars, one directory per jar. */
  Path sources() {
    return directory.resolve("sources");
  }

  private Path fingerprint() {
    return directory.resolve("fingerprint");
  }

  /**
   * Makes the directory usable for a request from the given toolchain, wiping it when the
   * toolchain changed.
   */
  void prepare(String scalaVersion, Path bridgeJar) throws IOException {
    String expected =
        String.join(
            "\n", LAYOUT_VERSION, scalaVersion, bridgeJar.getFileName().toString(), "");
    if (Files.exists(fingerprint())
        && expected.equals(Files.readString(fingerprint(), StandardCharsets.UTF_8))) {
      Files.createDirectories(classes());
      return;
    }
    wipe();
    Files.createDirectories(classes());
    Files.writeString(fingerprint(), expected, StandardCharsets.UTF_8);
  }

  /** Discards the compiler's products, for when there is no analysis that describes them. */
  void resetClasses() throws IOException {
    deleteRecursively(classes());
    Files.createDirectories(classes());
  }

  private void wipe() throws IOException {
    deleteRecursively(directory);
    Files.createDirectories(directory);
  }

  static void deleteRecursively(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    try (Stream<Path> tree = Files.walk(path)) {
      for (Path entry : tree.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(entry);
      }
    }
  }
}
