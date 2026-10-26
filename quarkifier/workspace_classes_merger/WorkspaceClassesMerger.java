package com.clementguillot.quarkifier.workspace;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexWriter;

/**
 * Materializes the runtime output JARs of one Bazel workspace target into one canonical class
 * directory.
 *
 * <p>The action that invokes this program owns the output directory as a Bazel tree artifact. Input
 * archives and their entries are processed in lexical order. Duplicate paths are accepted only when
 * their bytes are identical, so language-specific outputs cannot silently overwrite each other. The
 * merged bean archive receives a complete Jandex index rather than retaining partial input indexes.
 * Unmarked archives remain unindexed, preserving Quarkus's archive discovery.
 */
public final class WorkspaceClassesMerger {

  private static final String JANDEX_INDEX = "META-INF/jandex.idx";

  private WorkspaceClassesMerger() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      throw new IllegalArgumentException(
          "usage: WorkspaceClassesMerger OUTPUT_DIRECTORY INPUT_JAR...");
    }
    Path output = Path.of(args[0]).toAbsolutePath().normalize();
    Files.createDirectories(output);
    List<Path> archives =
        Arrays.stream(args, 1, args.length)
            .map(Path::of)
            .sorted(Comparator.comparing(Path::toString))
            .toList();
    boolean inputIndexPresent = false;
    for (Path archive : archives) {
      if (extract(output, archive)) {
        inputIndexPresent = true;
      }
    }
    if (inputIndexPresent || Files.exists(output.resolve("META-INF/beans.xml"))) {
      writeIndex(output);
    }
  }

  private static void writeIndex(Path output) throws IOException {
    File[] classes;
    try (var paths = Files.walk(output)) {
      classes =
          paths
              .filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().endsWith(".class"))
              .sorted()
              .map(Path::toFile)
              .toArray(File[]::new);
    }
    Index index = Index.of(classes);
    Path indexFile = output.resolve(JANDEX_INDEX);
    Files.createDirectories(indexFile.getParent());
    try (OutputStream destination =
        Files.newOutputStream(indexFile, StandardOpenOption.CREATE_NEW)) {
      new IndexWriter(destination).write(index);
    }
  }

  private static boolean extract(Path output, Path archive) throws IOException {
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      List<? extends ZipEntry> entries =
          zip.stream()
              .filter(
                  entry ->
                      !entry.isDirectory()
                          && !JANDEX_INDEX.equals(entry.getName())
                          && !"META-INF/MANIFEST.MF".equalsIgnoreCase(entry.getName()))
              .sorted(Comparator.comparing(ZipEntry::getName))
              .toList();
      for (ZipEntry entry : entries) {
        Path destination = output.resolve(entry.getName()).normalize();
        if (!destination.startsWith(output)) {
          throw new IOException(
              "archive entry escapes the workspace class tree: " + archive + "!" + entry.getName());
        }
        byte[] content;
        try (InputStream input = zip.getInputStream(entry)) {
          content = input.readAllBytes();
        }
        if (Files.exists(destination)) {
          if (!Arrays.equals(Files.readAllBytes(destination), content)) {
            throw new IOException(
                "conflicting workspace class entry " + entry.getName() + " from " + archive);
          }
          continue;
        }
        Path parent = destination.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.write(destination, content, StandardOpenOption.CREATE_NEW);
      }
      return zip.getEntry(JANDEX_INDEX) != null;
    }
  }
}
