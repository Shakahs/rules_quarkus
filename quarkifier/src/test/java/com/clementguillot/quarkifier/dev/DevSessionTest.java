package com.clementguillot.quarkifier.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clementguillot.quarkifier.devhook.GenerationSwitch;
import com.clementguillot.quarkifier.model.transport.BazelApplicationModel;
import io.quarkus.dev.spi.HotReplacementSetup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link DevSession}. */
class DevSessionTest {

  @TempDir Path tempDir;

  /** A Bazel class directory holding {@code my/App.class} with {@code content}. */
  private Path bazelDirectory(String name, String content) throws Exception {
    Path directory = Files.createDirectories(tempDir.resolve("bazel-out").resolve(name));
    Files.createDirectories(directory.resolve("my"));
    Files.writeString(directory.resolve("my/App.class"), content);
    return directory;
  }

  /** Rebuilds a Bazel directory as Bazel does: deletes its outputs, then writes new files. */
  private static void rebuild(Path directory, String content) throws Exception {
    DevSession.deleteTree(directory);
    Files.createDirectories(directory.resolve("my"));
    Files.writeString(directory.resolve("my/App.class"), content);
  }

  private DevSession start(Path bazelDirectory) throws Exception {
    return DevSession.start(
        tempDir.resolve("session"),
        List.of(new DevSession.Module("bazel-out/app", "000-app", bazelDirectory)));
  }

  private static String read(Path file) throws Exception {
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  @Test
  void start_linksTheFirstGenerationAndMakesItCurrentAndReady() throws Exception {
    Path bazel = bazelDirectory("app", "v0");

    DevSession session = start(bazel);

    Path root = session.root();
    assertEquals(Path.of("gen-0"), Files.readSymbolicLink(root.resolve(GenerationSwitch.CURRENT)));
    assertEquals(Path.of("gen-0"), Files.readSymbolicLink(root.resolve(GenerationSwitch.READY)));
    Path linked = root.resolve("gen-0/000-app/my/App.class");
    assertTrue(Files.isSameFile(bazel.resolve("my/App.class"), linked), "a hard link, not a copy");
  }

  @Test
  void classPaths_runThroughTheCurrentLink() throws Exception {
    DevSession session = start(bazelDirectory("app", "v0"));

    Path classPath = session.classPaths().get("bazel-out/app");

    assertEquals(session.root().resolve("current/000-app"), classPath);
    assertEquals("v0", read(classPath.resolve("my/App.class")));
  }

  @Test
  void aGenerationKeepsItsFilesWhenBazelDeletesAndRewritesTheOutputs() throws Exception {
    Path bazel = bazelDirectory("app", "v0");
    DevSession session = start(bazel);

    rebuild(bazel, "v1");
    session.publish();

    assertEquals("v0", read(session.root().resolve("gen-0/000-app/my/App.class")));
    assertEquals("v1", read(session.root().resolve("gen-1/000-app/my/App.class")));
  }

  @Test
  void publish_marksTheGenerationReadyButLeavesTheApplicationOnItsCurrentOne() throws Exception {
    Path bazel = bazelDirectory("app", "v0");
    DevSession session = start(bazel);

    rebuild(bazel, "v1");
    session.publish();

    Path root = session.root();
    assertEquals(Path.of("gen-1"), Files.readSymbolicLink(root.resolve(GenerationSwitch.READY)));
    assertEquals(Path.of("gen-0"), Files.readSymbolicLink(root.resolve(GenerationSwitch.CURRENT)));
    assertEquals("v0", read(session.classPaths().get("bazel-out/app").resolve("my/App.class")));
    assertFalse(Files.exists(root.resolve(GenerationSwitch.READY + ".next"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void aLinkedFileKeepsBazelsModificationTime() throws Exception {
    Path bazel = bazelDirectory("app", "v0");
    FileTime built = FileTime.fromMillis(1_700_000_000_000L);
    Files.setLastModifiedTime(bazel.resolve("my/App.class"), built);

    DevSession session = start(bazel);

    assertEquals(
        built, Files.getLastModifiedTime(session.root().resolve("gen-0/000-app/my/App.class")));
  }

  @Test
  void aSymbolicLinkInTheClassDirectoryIsLinkedAsTheFileItNames() throws Exception {
    Path bazel = bazelDirectory("app", "v0");
    Path elsewhere = Files.writeString(tempDir.resolve("Other.class"), "other");
    Files.createSymbolicLink(bazel.resolve("my/Other.class"), elsewhere);

    DevSession session = start(bazel);

    Path linked = session.root().resolve("gen-0/000-app/my/Other.class");
    assertFalse(Files.isSymbolicLink(linked), "a link back into Bazel's outputs would not hold");
    assertTrue(Files.isSameFile(elsewhere, linked));
  }

  @Test
  void publish_keepsTheCurrentGenerationAndTheOneBeforeIt() throws Exception {
    Path bazel = bazelDirectory("app", "v0");
    DevSession session = start(bazel);
    Path root = session.root();
    session.publish();
    session.publish();
    GenerationSwitch.switchToReady(root); // the application runs gen-2

    session.publish();

    assertFalse(Files.exists(root.resolve("gen-0")));
    assertTrue(Files.exists(root.resolve("gen-1")));
    assertTrue(Files.exists(root.resolve("gen-2")));
    assertTrue(Files.exists(root.resolve("gen-3")));
  }

  @Test
  void publish_keepsGenerationsTheApplicationHasNotReachedYet() throws Exception {
    DevSession session = start(bazelDirectory("app", "v0"));
    Path root = session.root();

    session.publish();
    session.publish();
    session.publish();

    for (int number = 0; number <= 3; number++) {
      assertTrue(Files.exists(root.resolve("gen-" + number)), "gen-" + number);
    }
  }

  @Test
  void start_replacesAPreviousSession() throws Exception {
    Path stale = Files.createDirectories(tempDir.resolve("session/gen-7"));

    start(bazelDirectory("app", "v0"));

    assertFalse(Files.exists(stale));
  }

  @Test
  void writeHookJar_holdsTheSwitchAndTheServiceFileOnly() throws Exception {
    DevSession session = start(bazelDirectory("app", "v0"));

    Path jar = session.writeHookJar();

    try (var file = new JarFile(jar.toFile())) {
      var names = new ArrayList<String>();
      file.stream().forEach(entry -> names.add(entry.getName()));
      String service = "META-INF/services/" + HotReplacementSetup.class.getName();
      assertEquals(
          List.of(GenerationSwitch.class.getName().replace('.', '/') + ".class", service), names);
      assertEquals(
          GenerationSwitch.class.getName() + "\n",
          new String(file.getInputStream(file.getEntry(service)).readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void theSwitchIsOneClassSoTheHookJarIsComplete() {
    // The hook jar carries GenerationSwitch.class alone; a nested or anonymous class would be
    // missing from it at run time.
    assertEquals(0, GenerationSwitch.class.getDeclaredClasses().length);
    assertEquals(
        null,
        GenerationSwitch.class.getResource(GenerationSwitch.class.getSimpleName() + "$1.class"));
  }

  // ---- module selection ----

  private static BazelApplicationModel.Node node(
      String id, String type, boolean reloadable, String workspaceModuleId, List<String> paths) {
    return new BazelApplicationModel.Node(
        id,
        BazelApplicationModel.NodeKind.WORKSPACE,
        new BazelApplicationModel.ArtifactCoordinates("test", id, "", type, "1.0"),
        paths,
        List.of(),
        new BazelApplicationModel.ClasspathFacts(false, true, false, false, false, reloadable, false),
        workspaceModuleId,
        "//:" + id);
  }

  private static BazelApplicationModel model(List<BazelApplicationModel.Node> nodes) {
    return new BazelApplicationModel(
        BazelApplicationModel.SCHEMA_VERSION,
        new BazelApplicationModel.Producer("test", "1"),
        com.clementguillot.quarkifier.QuarkifierVersionProvider.targetedQuarkusVersion(),
        BazelApplicationModel.Mode.DEV,
        nodes.get(0).id(),
        nodes,
        List.of(),
        new BazelApplicationModel.Platform(List.of(), Map.of(), List.of()));
  }

  @Test
  void modules_areTheApplicationAndItsReloadableWorkspaceJars() throws Exception {
    Path app = bazelDirectory("app", "a");
    Path lib = bazelDirectory("lib", "b");
    Path external = bazelDirectory("external", "c");
    Path fixed = bazelDirectory("fixed", "d");

    var modules =
        DevSession.modules(
            model(
                List.of(
                    node("app", "jar", false, "app", List.of(app.toString())),
                    node("lib", "jar", true, "lib", List.of(lib.toString())),
                    node("maven", "jar", false, null, List.of(external.toString())),
                    node("fixed", "jar", false, "fixed", List.of(fixed.toString())))),
            Map.of());

    assertEquals(
        List.of(
            new DevSession.Module(app.toString(), "000-app", app.toRealPath()),
            new DevSession.Module(lib.toString(), "001-lib", lib.toRealPath())),
        modules);
  }

  @Test
  void modules_readEachDirectoryWhereTheInvocationFindsIt() throws Exception {
    Path found = bazelDirectory("found", "a");

    var modules =
        DevSession.modules(
            model(List.of(node("app", "jar", false, "app", List.of("bazel-out/app")))),
            Map.of("bazel-out/app", found));

    assertEquals(found.toRealPath(), modules.get(0).bazelDirectory());
    assertEquals("bazel-out/app", modules.get(0).modelPath());
  }

  @Test
  void modules_failForAModuleOfSeveralPaths() throws Exception {
    Path first = bazelDirectory("first", "a");
    Path second = bazelDirectory("second", "b");
    var model =
        model(
            List.of(
                node("app", "jar", false, "app", List.of(first.toString(), second.toString()))));

    assertThrows(IllegalStateException.class, () -> DevSession.modules(model, Map.of()));
  }
}
