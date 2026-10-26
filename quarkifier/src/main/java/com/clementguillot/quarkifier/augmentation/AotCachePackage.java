package com.clementguillot.quarkifier.augmentation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

/** Preserves the JAR modification times HotSpot records in a build-phase AOT cache. */
public final class AotCachePackage {
  static final String FILE_NAME = "app.aot.jar-timestamps";

  private AotCachePackage() {}

  static void recordJarTimestamps(Path packageDirectory) throws IOException {
    if (!Files.isRegularFile(packageDirectory.resolve("app.aot"))) {
      return;
    }
    // Remote artifact transport preserves bytes, not filesystem timestamps. Record these
    // before publishing copies of the trained package. NUL delimiters preserve path names.
    try (var output = Files.newOutputStream(packageDirectory.resolve(FILE_NAME));
        var paths = Files.walk(packageDirectory)) {
      for (Path jar :
          paths.filter(Files::isRegularFile)
              .filter(path -> path.toString().endsWith(".jar"))
              .sorted()
              .toList()) {
        long seconds = Files.getLastModifiedTime(jar).toInstant().getEpochSecond();
        String record = seconds + " " + packageDirectory.relativize(jar) + "\0";
        output.write(record.getBytes(StandardCharsets.UTF_8));
      }
    }
  }

  /** Stages an isolated, writable package after Bazel artifact transport. */
  static void stage(Path packageDirectory, Path destination) throws IOException {
    String timestamps = Files.readString(packageDirectory.resolve(FILE_NAME), StandardCharsets.UTF_8);
    try (var paths = Files.walk(packageDirectory)) {
      for (Path source : paths.sorted().toList()) {
        Path target = destination.resolve(packageDirectory.relativize(source));
        if (Files.isDirectory(source)) {
          Files.createDirectories(target);
        } else {
          Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    for (String record : timestamps.split("\0")) {
      int separator = record.indexOf(' ');
      long seconds = Long.parseLong(record.substring(0, separator));
      Path jar = destination.resolve(record.substring(separator + 1));
      Files.setLastModifiedTime(jar, FileTime.from(Instant.ofEpochSecond(seconds)));
    }
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      throw new IllegalArgumentException("Expected AOT package directory and test project directory");
    }
    stage(Path.of(args[0]), Path.of(args[1]));
  }
}
