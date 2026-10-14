package com.clementguillot.quarkifier.dev;

import com.clementguillot.quarkifier.devhook.GenerationSwitch;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel;
import io.quarkus.dev.spi.HotReplacementSetup;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import org.jboss.logging.Logger;

/**
 * The class directories a dev session runs from, kept apart from Bazel's outputs.
 *
 * <p>Bazel deletes an action's outputs before it runs the action again, so a library's class
 * directory in {@code bazel-out} is missing or half written for as long as its rebuild takes. The
 * application never reads it there. Each successful build is instead linked into a generation of
 * this session, {@code gen-<n>/<module>}, with one hard link per file: the generation keeps the
 * files when Bazel later deletes its own names for them, and copies nothing. Unchanged files keep
 * Bazel's modification times, so Quarkus finds changed exactly the classes the build changed.
 *
 * <p>Every module's class path is {@code current/<module>}. {@value GenerationSwitch#READY} names
 * the newest complete generation and {@value GenerationSwitch#CURRENT} the one the application
 * runs; the watcher only moves the first, and {@link GenerationSwitch} moves the second inside
 * Quarkus's scan.
 */
public final class DevSession {

  private static final Logger LOGGER = Logger.getLogger(DevSession.class);
  private static final String GENERATION_PREFIX = "gen-";
  private static final String HOOK_JAR = "rules-quarkus-dev-session.jar";
  private static final String HOOK_SERVICE =
      "META-INF/services/" + HotReplacementSetup.class.getName();

  /**
   * One dev module: a reloadable workspace artifact of the model and the Bazel directory it is
   * built into.
   *
   * @param modelPath the artifact's path as the model holds it
   * @param name the module's directory name inside a generation
   * @param bazelDirectory the directory Bazel builds the artifact into
   */
  public record Module(String modelPath, String name, Path bazelDirectory) {}

  private final Path root;
  private final List<Module> modules;
  private int published;
  private boolean copyWarned;

  private DevSession(Path root, List<Module> modules) {
    this.root = root;
    this.modules = List.copyOf(modules);
  }

  /**
   * The dev modules of {@code model}: the application and every reloadable workspace dependency,
   * exactly the artifacts Quarkus loads into its reloadable class loader.
   *
   * @param model the dev application model
   * @param artifactPaths each model artifact path to the file holding it here
   * @throws IOException if a module's directory cannot be resolved
   * @throws IllegalStateException if a module does not have exactly one path
   */
  public static List<Module> modules(
      BazelApplicationModel model, Map<String, Path> artifactPaths) throws IOException {
    var result = new ArrayList<Module>();
    for (BazelApplicationModel.Node node : model.nodes()) {
      if (!isModule(model, node)) {
        continue;
      }
      if (node.paths().size() != 1) {
        throw new IllegalStateException(
            "Dev module "
                + node.id()
                + " must resolve to exactly one class directory, got "
                + node.paths());
      }
      String modelPath = node.paths().get(0);
      Path found = artifactPaths.getOrDefault(modelPath, Path.of(modelPath));
      String name =
          String.format(
              "%03d-%s",
              result.size(), node.coordinates().artifactId().replaceAll("[^A-Za-z0-9._-]", "_"));
      result.add(
          new Module(modelPath, name, found.toAbsolutePath().normalize().toRealPath()));
    }
    return result;
  }

  private static boolean isModule(BazelApplicationModel model, BazelApplicationModel.Node node) {
    if (model.applicationId().equals(node.id())) {
      return true;
    }
    var facts = node.classpath();
    return node.workspaceModuleId() != null
        && facts.runtimeClasspath()
        && facts.reloadable()
        && "jar".equals(node.coordinates().type());
  }

  /**
   * Starts a session in {@code root}, replacing anything there, with the modules' current Bazel
   * outputs as its first generation, both ready and current.
   *
   * @param root the session directory
   * @param modules the dev modules
   * @return the session
   * @throws IOException if the first generation cannot be linked
   */
  public static DevSession start(Path root, List<Module> modules) throws IOException {
    deleteTree(root);
    Files.createDirectories(root);
    var session = new DevSession(root.toAbsolutePath().normalize(), modules);
    Path first = session.link(0).getFileName();
    Files.createSymbolicLink(session.root.resolve(GenerationSwitch.CURRENT), first);
    Files.createSymbolicLink(session.root.resolve(GenerationSwitch.READY), first);
    return session;
  }

  /** The session directory. */
  public Path root() {
    return root;
  }

  /** The directories Bazel builds the modules into, which the session only reads. */
  public List<Path> bazelDirectories() {
    return modules.stream().map(Module::bazelDirectory).toList();
  }

  /** Each module's model path to the class path the application reads it from. */
  public Map<String, Path> classPaths() {
    var result = new LinkedHashMap<String, Path>();
    for (Module module : modules) {
      result.put(
          module.modelPath(), root.resolve(GenerationSwitch.CURRENT).resolve(module.name()));
    }
    return result;
  }

  /**
   * Links the modules' Bazel outputs, as the last build left them, into a new generation and marks
   * it ready. Called only once a build has finished, so nothing is writing them.
   *
   * <p>Generations older than the one before the application's current one are deleted; the
   * current one, and any published since, stay until the application has moved past them.
   *
   * @throws IOException if the generation cannot be linked or published
   */
  public synchronized void publish() throws IOException {
    int next = published + 1;
    Path generation = link(next).getFileName();
    Path pending = root.resolve(GenerationSwitch.READY + ".next");
    Files.deleteIfExists(pending);
    Files.createSymbolicLink(pending, generation);
    Files.move(
        pending, root.resolve(GenerationSwitch.READY), StandardCopyOption.ATOMIC_MOVE);
    published = next;
    prune();
  }

  /**
   * Writes the jar that installs {@link GenerationSwitch} into the dev application: the class and
   * the service file Quarkus's {@link java.util.ServiceLoader} finds it by.
   *
   * @return the jar
   * @throws IOException if the jar cannot be written
   */
  public Path writeHookJar() throws IOException {
    Path jar = root.resolve(HOOK_JAR);
    String entry = GenerationSwitch.class.getName().replace('.', '/') + ".class";
    try (InputStream bytes = GenerationSwitch.class.getClassLoader().getResourceAsStream(entry);
        var out = new JarOutputStream(Files.newOutputStream(jar))) {
      if (bytes == null) {
        throw new IOException("Cannot read " + entry);
      }
      out.putNextEntry(new ZipEntry(entry));
      bytes.transferTo(out);
      out.closeEntry();
      out.putNextEntry(new ZipEntry(HOOK_SERVICE));
      out.write((GenerationSwitch.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    return jar;
  }

  /** Links every module's Bazel directory into generation {@code number}. */
  private Path link(int number) throws IOException {
    Path generation = root.resolve(GENERATION_PREFIX + number);
    deleteTree(generation);
    for (Module module : modules) {
      linkTree(module.bazelDirectory(), generation.resolve(module.name()));
    }
    return generation;
  }

  /**
   * Mirrors {@code source} into {@code target} with a hard link per file. A symbolic link in the
   * source is followed: linking the link itself would lead back into Bazel's outputs.
   */
  private void linkTree(Path source, Path target) throws IOException {
    Files.walkFileTree(
        source,
        EnumSet.of(FileVisitOption.FOLLOW_LINKS),
        Integer.MAX_VALUE,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(target.resolve(source.relativize(dir).toString()));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (!attrs.isRegularFile()) {
              throw new IOException("Not a regular file in a class directory: " + file);
            }
            Path existing = Files.isSymbolicLink(file) ? file.toRealPath() : file;
            linkOrCopy(existing, target.resolve(source.relativize(file).toString()));
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /** Hard-links {@code existing}, copying it with its attributes across file systems. */
  private void linkOrCopy(Path existing, Path link) throws IOException {
    try {
      Files.createLink(link, existing);
    } catch (FileSystemException | UnsupportedOperationException e) {
      if (!copyWarned) {
        copyWarned = true;
        LOGGER.warnf(
            "[hot-reload] Copying class files into %s, which cannot hard-link %s (%s). Put the"
                + " dev output directory on the file system of Bazel's output base to link them.",
            root,
            existing,
            e.getMessage());
      }
      Files.copy(existing, link, StandardCopyOption.COPY_ATTRIBUTES);
    }
  }

  /** Deletes the generations older than the one before the current one. */
  private void prune() throws IOException {
    OptionalInt current = generationNumber(Files.readSymbolicLink(root.resolve(GenerationSwitch.CURRENT)));
    if (current.isEmpty()) {
      return;
    }
    try (Stream<Path> entries = Files.list(root)) {
      for (Path entry : entries.toList()) {
        OptionalInt number = generationNumber(entry.getFileName());
        if (number.isPresent() && number.getAsInt() < current.getAsInt() - 1) {
          deleteTree(entry);
        }
      }
    }
  }

  private static OptionalInt generationNumber(Path name) {
    String value = name.toString();
    if (!value.startsWith(GENERATION_PREFIX)) {
      return OptionalInt.empty();
    }
    try {
      return OptionalInt.of(Integer.parseInt(value.substring(GENERATION_PREFIX.length())));
    } catch (NumberFormatException e) {
      return OptionalInt.empty();
    }
  }

  /** Deletes {@code path} and everything below it, without following symbolic links. */
  static void deleteTree(Path path) throws IOException {
    if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(path)) {
      for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(entry);
      }
    }
  }
}
