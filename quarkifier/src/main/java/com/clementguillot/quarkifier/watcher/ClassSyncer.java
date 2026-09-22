package com.clementguillot.quarkifier.watcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Utility for copying an application's built output between bazel-bin output paths and a mutable
 * classes directory that {@code RuntimeUpdatesProcessor} monitors.
 *
 * <p>Supports both directories and jar files as input sources. Bazel's {@code java_library} rule
 * produces class jars (e.g., {@code liblib-class.jar}), so jar extraction is the primary mode.
 *
 * <p>Classes are not the whole payload. An application archive also carries the resources Quarkus
 * and its extensions read from the classpath — configuration, templates, and the Web Bundler's web
 * root among them — and an extension watches those by classpath location, so a rebuilt resource
 * reaches the running application the same way a rebuilt class does. Only jar packaging metadata is
 * left behind, since the mutable directory is a class tree and not a jar.
 *
 * <p>Entries whose content is already present are not rewritten. The dev loop syncs thousands of
 * files where a rebuild changed a handful, and Quarkus decides what to reload from what changed on
 * disk: rewriting every file would make each reload look like a change to the whole application.
 */
public final class ClassSyncer {

  private ClassSyncer() {}

  /**
   * Returns the class output paths that belong to the reloadable application, dropping
   * locally-built Quarkus extension jars.
   *
   * <p>A jar carrying {@code META-INF/quarkus-extension.properties} is an extension: a dependency,
   * not part of the reloadable application. Syncing its classes into the mutable classes directory
   * would expose them to both the application and augment classloaders, breaking build-time
   * config-mapping lookup ({@code SRCFG00027}). Best-effort: paths that cannot be inspected are
   * kept.
   *
   * @param classesOutputPaths bazel-bin output paths (directories or jar files)
   * @return the input paths with extension jars removed
   */
  public static List<Path> excludeExtensionJars(List<Path> classesOutputPaths) {
    List<Path> reloadable = new ArrayList<>(classesOutputPaths.size());
    for (Path path : classesOutputPaths) {
      boolean isExtension = false;
      if (path.toString().endsWith(".jar") && Files.isRegularFile(path)) {
        try (JarFile jar = new JarFile(path.toFile())) {
          isExtension = jar.getEntry("META-INF/quarkus-extension.properties") != null;
        } catch (IOException e) {
          isExtension = false;
        }
      }
      if (!isExtension) {
        reloadable.add(path);
      }
    }
    return reloadable;
  }

  /**
   * Initial population: copy every application file from the bazel-bin output paths to {@code
   * classesDir}, preserving directory structure.
   *
   * <p>Each output path can be either a directory (walked recursively) or a jar file (entries
   * extracted).
   *
   * @param classesOutputPaths bazel-bin output paths (directories or jar files)
   * @param classesDir mutable target directory
   * @throws IOException if a file operation fails
   */
  public static void populateClassesDir(List<Path> classesOutputPaths, Path classesDir)
      throws IOException {
    copyOutputs(classesOutputPaths, classesDir, null);
  }

  /**
   * Incremental sync: copy the application files from the bazel-bin output paths, tracking the
   * relative paths synced, then walk {@code classesDir} and delete the ones the latest build no
   * longer produces.
   *
   * @param classesOutputPaths bazel-bin output paths (directories or jar files)
   * @param classesDir mutable target directory
   * @throws IOException if a file operation fails
   */
  public static void syncClasses(List<Path> classesOutputPaths, Path classesDir)
      throws IOException {
    Set<Path> synced = new HashSet<>();
    copyOutputs(classesOutputPaths, classesDir, synced);

    // Remove files the latest build output no longer carries. Only paths a build produced
    // are candidates: Quarkus copies the declared resource directories into this same tree,
    // and deleting those would take the application's configuration with them.
    if (Files.isDirectory(classesDir)) {
      Files.walkFileTree(
          classesDir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Path relative = classesDir.relativize(file);
              if (file.toString().endsWith(".class") && !synced.contains(relative)) {
                Files.delete(file);
              }
              return FileVisitResult.CONTINUE;
            }
          });
    }
  }

  private static void copyOutputs(List<Path> classesOutputPaths, Path classesDir, Set<Path> synced)
      throws IOException {
    for (Path outputPath : classesOutputPaths) {
      if (!Files.exists(outputPath)) {
        continue;
      }
      if (Files.isDirectory(outputPath)) {
        copyClassesFromDirectory(outputPath, classesDir, synced);
      } else if (outputPath.toString().endsWith(".jar")) {
        extractClassesFromJar(outputPath, classesDir, synced);
      }
    }
  }

  /**
   * Reports whether a jar entry belongs in a class tree.
   *
   * <p>Everything an application archive carries does, except the packaging metadata that describes
   * it as a jar: a manifest or a signature file in the mutable directory describes nothing that is
   * there, and a signature no longer matches once the tree is assembled from several jars.
   */
  private static boolean isApplicationEntry(String name) {
    return !name.equals("META-INF/MANIFEST.MF")
        && !(name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA")))
        && !name.startsWith("META-INF/maven/");
  }

  /** Reports whether {@code target} already holds exactly {@code size} bytes of {@code source}. */
  private static boolean isUnchanged(Path target, long size, byte[] source) throws IOException {
    return Files.exists(target)
        && Files.size(target) == size
        && Arrays.equals(Files.readAllBytes(target), source);
  }

  // ---- internal helpers ----

  private static void copyClassesFromDirectory(Path outputDir, Path classesDir, Set<Path> synced)
      throws IOException {
    Files.walkFileTree(
        outputDir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Path relative = outputDir.relativize(file);
            if (isApplicationEntry(relative.toString())) {
              Path target = classesDir.resolve(relative);
              byte[] content = Files.readAllBytes(file);
              if (!isUnchanged(target, content.length, content)) {
                Files.createDirectories(target.getParent());
                Files.write(target, content);
              }
              if (synced != null) {
                synced.add(relative);
              }
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void extractClassesFromJar(Path jarPath, Path classesDir, Set<Path> synced)
      throws IOException {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.isDirectory() || !isApplicationEntry(entry.getName())) {
          continue;
        }
        Path relative = Path.of(entry.getName());
        Path target = classesDir.resolve(relative).normalize();
        if (!target.startsWith(classesDir)) {
          throw new IOException("Zip entry escapes target directory: " + entry.getName());
        }
        byte[] content;
        try (InputStream is = jar.getInputStream(entry)) {
          content = is.readAllBytes();
        }
        if (!isUnchanged(target, content.length, content)) {
          Files.createDirectories(target.getParent());
          Files.write(target, content);
        }
        if (synced != null) {
          synced.add(relative);
        }
      }
    }
  }
}
