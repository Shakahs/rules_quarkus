package com.clementguillot.quarkifier.watcher;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ProjectFileMirror}.
 *
 * <p>The output tree has the shape of a Scala.js ES-module link split into small modules: an
 * entry module beside one module per application class, the way a {@code SmallModulesFor} dev
 * link lays them out in its tree artifact.
 */
class ProjectFileMirrorTest {

  @TempDir Path tempDir;

  private Path link;
  private Path destination;

  @BeforeEach
  void setUp() throws IOException {
    link = tempDir.resolve("execroot/bazel-out/k8-fastbuild/bin/web/js/main_dev.js");
    destination = tempDir.resolve("workspace/web/jvm/web/scalajs");
    write(link.resolve("main.js"), "import './my.app.-Main.js';\n");
    write(link.resolve("my.app.-Main.js"), "export function main() { render(); }\n");
    write(link.resolve("my.app.views.-TenantViews.js"), "export const title = 'Tenants';\n");
    write(link.resolve("internal-3f2a.js"), "export const $c = 1;\n");
  }

  @Test
  void mirror_copiesDirectoryOutputAtRelativePaths() throws IOException {
    write(link.resolve("chunks/shared.js"), "export const shared = 1;\n");

    int changes = mirrorOf(Map.of(link, destination)).mirror();

    assertEquals(5, changes);
    assertEquals(filesUnder(link), filesUnder(destination));
    assertEquals(
        "export const shared = 1;\n", Files.readString(destination.resolve("chunks/shared.js")));
  }

  @Test
  void mirror_copiesFileOutputUnderItsOwnName() throws IOException {
    Path stylesheet = tempDir.resolve("execroot/bazel-out/k8-fastbuild/bin/web/style.css");
    write(stylesheet, "body { margin: 0; }\n");

    mirrorOf(Map.of(stylesheet, destination)).mirror();

    assertEquals(Set.of(Path.of("style.css")), filesUnder(destination));
    assertEquals("body { margin: 0; }\n", Files.readString(destination.resolve("style.css")));
  }

  @Test
  void mirror_mergesOutputsSharingADirectory() throws IOException {
    Path stylesheet = tempDir.resolve("execroot/bazel-out/k8-fastbuild/bin/web/style.css");
    write(stylesheet, "body { margin: 0; }\n");
    Map<Path, Path> outputs = new LinkedHashMap<>();
    outputs.put(link, destination);
    outputs.put(stylesheet, tempDir.resolve("workspace/web/jvm/web/../web/scalajs"));

    mirrorOf(outputs).mirror();

    Set<Path> expected = new HashSet<>(filesUnder(link));
    expected.add(Path.of("style.css"));
    assertEquals(expected, filesUnder(destination));
  }

  @Test
  void mirror_rewritesAChangedFileThroughItsExistingInode() throws IOException {
    ProjectFileMirror mirror = mirrorOf(Map.of(link, destination));
    mirror.mirror();
    Path target = destination.resolve("my.app.views.-TenantViews.js");
    Object inodeBefore = fileKey(target);

    rebuild(link.resolve("my.app.views.-TenantViews.js"), "export const title = 'Tenant registry';\n");
    int changes = mirror.mirror();

    assertEquals(1, changes);
    assertEquals("export const title = 'Tenant registry';\n", Files.readString(target));
    assertNotNull(inodeBefore);
    assertEquals(inodeBefore, fileKey(target));
  }

  @Test
  void mirror_leavesFilesWithUnchangedContentUntouched() throws IOException {
    ProjectFileMirror mirror = mirrorOf(Map.of(link, destination));
    mirror.mirror();
    FileTime settled = FileTime.fromMillis(1_000_000_000_000L);
    try (Stream<Path> files = Files.walk(destination)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        Files.setLastModifiedTime(file, settled);
      }
    }

    // A relink rewrites every module file, but only one of them with different content.
    for (Path module : filesUnder(link)) {
      rebuild(link.resolve(module), Files.readString(link.resolve(module)));
    }
    rebuild(link.resolve("my.app.-Main.js"), "export function main() { renderAll(); }\n");
    int changes = mirror.mirror();

    assertEquals(1, changes);
    assertNotEquals(settled, Files.getLastModifiedTime(destination.resolve("my.app.-Main.js")));
    for (String untouched :
        List.of("main.js", "my.app.views.-TenantViews.js", "internal-3f2a.js")) {
      assertEquals(settled, Files.getLastModifiedTime(destination.resolve(untouched)), untouched);
    }
  }

  @Test
  void mirror_reportsAnEditAsAModificationOnly() throws IOException, InterruptedException {
    ProjectFileMirror mirror = mirrorOf(Map.of(link, destination));
    mirror.mirror();

    try (WatchService watcher = destination.getFileSystem().newWatchService()) {
      destination.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
      rebuild(link.resolve("my.app.views.-TenantViews.js"), "export const title = 'Registry';\n");
      mirror.mirror();

      List<String> events = drain(watcher);

      assertFalse(events.isEmpty(), "the rewrite produced no event");
      assertEquals(
          Set.of("ENTRY_MODIFY my.app.views.-TenantViews.js"), Set.copyOf(events), events::toString);
    }
  }

  @Test
  void mirror_rewritesALinkedModuleAsOneModification() throws IOException, InterruptedException {
    // An application's unoptimized dev link is one module of tens of megabytes.
    write(link.resolve("main.js"), "export const title = 'Tenants';\n".repeat(1_500_000));
    ProjectFileMirror mirror = mirrorOf(Map.of(link, destination));
    mirror.mirror();
    String relinked = "export const title = 'Tenant registry';\n".repeat(1_500_000);

    try (WatchService watcher = destination.getFileSystem().newWatchService()) {
      destination.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
      rebuild(link.resolve("main.js"), relinked);
      mirror.mirror();

      List<String> events = drainCounted(watcher);

      assertEquals(List.of("ENTRY_MODIFY main.js x1"), events);
      assertEquals(relinked, Files.readString(destination.resolve("main.js")));
    }
  }

  @Test
  void mirror_cutsAShorterRewriteToItsLength() throws IOException {
    ProjectFileMirror mirror = mirrorOf(Map.of(link, destination));
    mirror.mirror();
    Path target = destination.resolve("my.app.views.-TenantViews.js");
    Object inodeBefore = fileKey(target);

    rebuild(link.resolve("my.app.views.-TenantViews.js"), "export const t = 1;\n");
    mirror.mirror();

    assertEquals("export const t = 1;\n", Files.readString(target));
    assertEquals(inodeBefore, fileKey(target));
  }

  @Test
  void mirror_createsModulesANewLinkAdds() throws IOException {
    ProjectFileMirror mirror = mirrorOf(Map.of(link, destination));
    mirror.mirror();

    write(link.resolve("my.app.views.-ChainViews.js"), "export const title = 'Chains';\n");
    int changes = mirror.mirror();

    assertEquals(1, changes);
    assertEquals(
        "export const title = 'Chains';\n",
        Files.readString(destination.resolve("my.app.views.-ChainViews.js")));
  }

  @Test
  void mirror_deletesModulesALinkNoLongerProduces() throws IOException {
    write(link.resolve("chunks/shared.js"), "export const shared = 1;\n");
    ProjectFileMirror mirror = mirrorOf(Map.of(link, destination));
    mirror.mirror();

    Files.delete(link.resolve("internal-3f2a.js"));
    Files.delete(link.resolve("chunks/shared.js"));
    Files.delete(link.resolve("chunks"));
    int changes = mirror.mirror();

    assertEquals(2, changes);
    assertEquals(filesUnder(link), filesUnder(destination));
    assertFalse(Files.exists(destination.resolve("chunks")), "emptied directory left behind");
  }

  @Test
  void mirror_removesWhatAnEarlierSessionLeftBehind() throws IOException {
    write(destination.resolve("my.app.views.-RemovedViews.js"), "stale\n");
    write(destination.resolve("old/internal-9c1d.js"), "stale\n");
    write(destination.resolve("main.js"), "stale\n");

    mirrorOf(Map.of(link, destination)).mirror();

    assertEquals(filesUnder(link), filesUnder(destination));
    assertEquals("import './my.app.-Main.js';\n", Files.readString(destination.resolve("main.js")));
  }

  @Test
  void mirror_failsWhenAnOutputIsMissing() {
    Path missing = tempDir.resolve("execroot/bazel-out/k8-fastbuild/bin/web/js/absent.js");

    IOException failure =
        assertThrows(IOException.class, () -> mirrorOf(Map.of(missing, destination)).mirror());

    assertTrue(failure.getMessage().contains(missing.toString()), failure.getMessage());
  }

  @Test
  void owns_coversPathsBelowADestinationOnly() {
    ProjectFileMirror mirror = mirrorOf(Map.of(link, destination));

    assertTrue(mirror.owns(destination));
    assertTrue(mirror.owns(destination.resolve("chunks/shared.js")));
    assertTrue(mirror.owns(destination.resolve("../scalajs/main.js")));
    assertFalse(mirror.owns(destination.resolveSibling("scalajs2/main.js")));
    assertFalse(mirror.owns(destination.getParent().resolve("custom-elements.js")));
    assertFalse(mirror.owns(link.resolve("main.js")));
  }

  @Test
  void isEmpty_reflectsWhetherAnythingIsMirrored() {
    assertTrue(mirrorOf(Map.of()).isEmpty());
    assertFalse(mirrorOf(Map.of(link, destination)).isEmpty());
  }

  // ---- helpers ----

  private static ProjectFileMirror mirrorOf(Map<Path, Path> directoriesByOutput) {
    return new ProjectFileMirror(directoriesByOutput);
  }

  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  /**
   * Replaces {@code file} the way a Bazel action does: a new file with a later modification time,
   * never an in-place edit of the old one.
   */
  private static void rebuild(Path file, String content) throws IOException {
    FileTime previous = Files.getLastModifiedTime(file);
    Files.delete(file);
    Files.writeString(file, content);
    Files.setLastModifiedTime(file, FileTime.fromMillis(previous.toMillis() + 1_000));
  }

  private static Set<Path> filesUnder(Path root) throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(Files::isRegularFile).map(root::relativize).collect(Collectors.toSet());
    }
  }

  private static Object fileKey(Path file) throws IOException {
    return Files.readAttributes(file, BasicFileAttributes.class).fileKey();
  }

  /** Collects "KIND name" for every event until the watcher has been quiet for a while. */
  private static List<String> drain(WatchService watcher) throws InterruptedException {
    List<String> events = new ArrayList<>();
    WatchKey key = watcher.poll(5, TimeUnit.SECONDS);
    while (key != null) {
      for (WatchEvent<?> event : key.pollEvents()) {
        events.add(event.kind().name() + " " + event.context());
      }
      key.reset();
      key = watcher.poll(500, TimeUnit.MILLISECONDS);
    }
    return events;
  }

  /**
   * Collects "KIND name xCOUNT" for every event until the watcher has been quiet for a while. The
   * count is how many times the file system raised the event before it was drained, which a
   * watcher that reads events in batches folds into one.
   */
  private static List<String> drainCounted(WatchService watcher) throws InterruptedException {
    List<String> events = new ArrayList<>();
    WatchKey key = watcher.poll(5, TimeUnit.SECONDS);
    while (key != null) {
      for (WatchEvent<?> event : key.pollEvents()) {
        events.add(event.kind().name() + " " + event.context() + " x" + event.count());
      }
      key.reset();
      key = watcher.poll(500, TimeUnit.MILLISECONDS);
    }
    return events;
  }
}
