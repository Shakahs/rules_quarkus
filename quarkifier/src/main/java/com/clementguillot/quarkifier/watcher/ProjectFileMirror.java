package com.clementguillot.quarkifier.watcher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Mirrors built outputs into the project directories the dev application reads them from.
 *
 * <p>Some extensions reload a file of the project directory in place but restart the application
 * for the same file on the classpath. The Web Bundler is the case this exists for: it symlinks the
 * local web directory's assets into its staging directory and watches them itself, re-bundling on a
 * modification without restarting Quarkus, while any change to a {@code web/} asset on the
 * classpath restarts the application. A module the build rewrites on every frontend edit therefore
 * has to reach the running application as a project file.
 *
 * <p>Each destination directory belongs to the dev session. After a mirror it holds exactly the
 * files of its outputs — a directory output contributes its entries at their relative paths, a file
 * output contributes itself under its own name — and anything else in it is deleted. That is what
 * makes a restarted session correct: files an earlier session left behind for an output that no
 * longer produces them are gone after the first mirror.
 *
 * <p>How a file is written decides what the dev application sees, because its watcher tells a
 * modification from an addition. Quarkus reports a rename onto an existing file as an addition, and
 * the Web Bundler restarts the application on any addition or removal, so a changed file is
 * rewritten in place, through its existing inode, which a watcher reports as a modification.
 *
 * <p>The rewrite is one write call carrying the whole new content, not truncation followed by a
 * stream of writes. Every write call is a modification event of its own, and the Web Bundler queues
 * a complete bundling for each modification it is told of: a linked module of tens of megabytes
 * written in the usual 8 KiB chunks is bundled once per batch of events the watcher happens to
 * drain, the early bundlings reading a partly written file. Written in one call, the file changes
 * from its old content to its new in a single modification. When the new content is shorter, the
 * file is cut to its length after the write, never emptied before it.
 *
 * <p>Files whose content is unchanged are not touched at all, and an output file whose modification
 * time and size are the same as at the previous mirror is not even read.
 */
public final class ProjectFileMirror {

  /** Destination directory to the outputs mirrored into it, in declaration order. */
  private final Map<Path, List<Path>> outputsByDirectory;

  /** Output file to its modification time and size when it was last mirrored. */
  private final Map<Path, Stamp> mirrored = new HashMap<>();

  private record Stamp(FileTime modified, long size) {}

  /**
   * Creates a mirror of the given outputs.
   *
   * @param directoriesByOutput each output (a file or a directory) to the directory it is mirrored
   *     into
   */
  public ProjectFileMirror(Map<Path, Path> directoriesByOutput) {
    Map<Path, List<Path>> grouped = new LinkedHashMap<>();
    directoriesByOutput.forEach(
        (output, directory) ->
            grouped
                .computeIfAbsent(directory.toAbsolutePath().normalize(), d -> new ArrayList<>())
                .add(output));
    this.outputsByDirectory = grouped;
  }

  /** Reports whether there is anything to mirror. */
  public boolean isEmpty() {
    return outputsByDirectory.isEmpty();
  }

  /**
   * Reports whether {@code path} lies in a destination directory, where every change is the
   * mirror's own writing rather than an edit a rebuild should follow.
   */
  public boolean owns(Path path) {
    Path absolute = path.toAbsolutePath().normalize();
    return outputsByDirectory.keySet().stream().anyMatch(absolute::startsWith);
  }

  /**
   * Brings every destination directory in line with its outputs.
   *
   * <p>Changed files are written before new ones are created and extraneous ones deleted, so a
   * watcher that batches its events sees an edit that only modified modules as modifications alone.
   *
   * @return the number of files written or deleted
   * @throws IOException if an output cannot be read or a destination cannot be written
   */
  public int mirror() throws IOException {
    int changes = 0;
    for (Map.Entry<Path, List<Path>> entry : outputsByDirectory.entrySet()) {
      changes += mirrorInto(entry.getKey(), entry.getValue());
    }
    return changes;
  }

  private int mirrorInto(Path directory, List<Path> outputs) throws IOException {
    Map<Path, Path> wanted = new TreeMap<>();
    for (Path output : outputs) {
      collectFiles(output, wanted);
    }
    Files.createDirectories(directory);

    List<Path> created = new ArrayList<>();
    int changes = 0;
    for (Map.Entry<Path, Path> file : wanted.entrySet()) {
      Path target = directory.resolve(file.getKey());
      if (Files.exists(target)) {
        changes += writeIfChanged(file.getValue(), target) ? 1 : 0;
      } else {
        created.add(file.getKey());
      }
    }
    for (Path relative : created) {
      writeIfChanged(wanted.get(relative), directory.resolve(relative));
      changes++;
    }
    return changes + deleteExtraneous(directory, wanted);
  }

  /** Adds the regular files of {@code output} to {@code wanted}, keyed by destination path. */
  private static void collectFiles(Path output, Map<Path, Path> wanted) throws IOException {
    if (!Files.exists(output)) {
      throw new IOException("Output to mirror does not exist: " + output);
    }
    if (!Files.isDirectory(output)) {
      wanted.put(output.getFileName(), output);
      return;
    }
    Files.walkFileTree(
        output,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            wanted.put(output.relativize(file), file);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /**
   * Rewrites {@code target} in place with the content of {@code source} unless it already holds it.
   *
   * @return whether {@code target} was written
   */
  private boolean writeIfChanged(Path source, Path target) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
    Stamp stamp = new Stamp(attributes.lastModifiedTime(), attributes.size());
    if (stamp.equals(mirrored.get(source)) && Files.exists(target)) {
      return false;
    }
    byte[] content = Files.readAllBytes(source);
    boolean write =
        !Files.exists(target)
            || Files.size(target) != content.length
            || !Arrays.equals(Files.readAllBytes(target), content);
    if (write) {
      Files.createDirectories(target.getParent());
      rewriteInPlace(target, content);
    }
    mirrored.put(source, stamp);
    return write;
  }

  /**
   * Replaces the content of {@code target} with {@code content} in one write call through its
   * existing inode, creating it if absent, then cuts it to that length if it was longer.
   */
  private static void rewriteInPlace(Path target, byte[] content) throws IOException {
    // A direct buffer is handed to the kernel as it is; a heap buffer would first be copied into
    // a temporary native one.
    ByteBuffer buffer = ByteBuffer.allocateDirect(content.length).put(content).flip();
    try (FileChannel channel =
        FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      while (buffer.hasRemaining()) {
        channel.write(buffer, buffer.position());
      }
      if (channel.size() > content.length) {
        channel.truncate(content.length);
      }
    }
  }

  /** Deletes what {@code directory} holds beyond {@code wanted}, then its emptied directories. */
  private int deleteExtraneous(Path directory, Map<Path, Path> wanted) throws IOException {
    int[] deleted = {0};
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (!wanted.containsKey(directory.relativize(file))) {
              Files.delete(file);
              deleted[0]++;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException failure)
              throws IOException {
            if (failure != null) {
              throw failure;
            }
            if (!dir.equals(directory)) {
              try (var entries = Files.list(dir)) {
                if (entries.findAny().isEmpty()) {
                  Files.delete(dir);
                }
              }
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return deleted[0];
  }
}
