package com.clementguillot.scalajs.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Expands Bazel's {@code @file} parameter files, one argument per line. */
final class Arguments {

  private Arguments() {}

  static List<String> expand(String... args) {
    List<String> expanded = new ArrayList<>();
    for (String arg : args) {
      if (arg.startsWith("@") && arg.length() > 1) {
        expanded.addAll(readParameterFile(Path.of(arg.substring(1))));
      } else {
        expanded.add(arg);
      }
    }
    return expanded;
  }

  private static List<String> readParameterFile(Path file) {
    try {
      List<String> lines = new ArrayList<>();
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (!line.isEmpty()) {
          lines.add(line);
        }
      }
      return lines;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read parameter file " + file, e);
    }
  }
}
