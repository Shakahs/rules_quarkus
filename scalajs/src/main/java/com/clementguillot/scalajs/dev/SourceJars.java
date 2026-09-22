package com.clementguillot.scalajs.dev;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts source jars into the state directory, under a directory named after each jar.
 *
 * <p>Extracted files keep stable paths across invocations, which zinc needs to recognize them as
 * the same sources, and they carry the jar's name in their path, which lets a {@code
 * -Wconf:src=} filter written for a source jar keep applying to what came out of it. A jar is
 * re-extracted only when its size or modification time changed; the directory of a jar that is
 * no longer given is removed.
 */
final class SourceJars {

  private static final String STAMP_SUFFIX = ".stamp";

  private SourceJars() {}

  /** Extracts the jars and returns every source file now under {@code sourcesDir}. */
  static List<Path> extract(List<Path> jars, Path sourcesDir) throws IOException {
    Files.createDirectories(sourcesDir);
    Set<String> expected = new HashSet<>();
    for (Path jar : jars) {
      String name = jar.getFileName().toString();
      expected.add(name);
      Path stamp = sourcesDir.resolve(name + STAMP_SUFFIX);
      String current = stampOf(jar);
      if (!Files.exists(stamp)
          || !current.equals(Files.readString(stamp, StandardCharsets.UTF_8))) {
        Path target = sourcesDir.resolve(name);
        StateDirectory.deleteRecursively(target);
        unzipSources(jar, target);
        Files.writeString(stamp, current, StandardCharsets.UTF_8);
      }
    }
    removeStale(sourcesDir, expected);
    return listSources(sourcesDir);
  }

  private static String stampOf(Path jar) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(jar, BasicFileAttributes.class);
    return attributes.size() + ":" + attributes.lastModifiedTime().toMillis();
  }

  private static void unzipSources(Path jar, Path target) throws IOException {
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.isDirectory() || !isSource(entry.getName())) {
          continue;
        }
        Path destination = target.resolve(entry.getName()).normalize();
        if (!destination.startsWith(target)) {
          throw new IOException("Entry escapes its jar: " + entry.getName() + " in " + jar);
        }
        Files.createDirectories(destination.getParent());
        try (InputStream in = zip.getInputStream(entry)) {
          Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static void removeStale(Path sourcesDir, Set<String> expected) throws IOException {
    try (Stream<Path> children = Files.list(sourcesDir)) {
      for (Path child : children.toList()) {
        String name = child.getFileName().toString();
        String jarName = name.endsWith(STAMP_SUFFIX)
            ? name.substring(0, name.length() - STAMP_SUFFIX.length())
            : name;
        if (!expected.contains(jarName)) {
          StateDirectory.deleteRecursively(child);
        }
      }
    }
  }

  private static List<Path> listSources(Path sourcesDir) throws IOException {
    List<Path> sources = new ArrayList<>();
    try (Stream<Path> tree = Files.walk(sourcesDir)) {
      for (Path file : tree.toList()) {
        if (Files.isRegularFile(file) && isSource(file.getFileName().toString())) {
          sources.add(file);
        }
      }
    }
    sources.sort(null);
    return sources;
  }

  private static boolean isSource(String name) {
    return name.endsWith(".scala") || name.endsWith(".java");
  }
}
