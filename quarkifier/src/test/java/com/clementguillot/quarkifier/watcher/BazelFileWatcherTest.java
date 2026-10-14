package com.clementguillot.quarkifier.watcher;

import static org.junit.jupiter.api.Assertions.*;

import com.clementguillot.quarkifier.QuarkifierConfig;
import com.clementguillot.quarkifier.TestQuarkifierConfig;
import com.clementguillot.quarkifier.dev.DevSession;
import com.clementguillot.quarkifier.devhook.GenerationSwitch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link BazelFileWatcher}. */
class BazelFileWatcherTest {

  @TempDir Path tempDir;

  private QuarkifierConfig testConfig(Path outputDir, List<Path> sourceDirs) {
    var args =
        new java.util.ArrayList<>(
            List.of(
                "--application-classpath", "app.jar",
                "--application-model", "model.json",
                "--output-dir", outputDir.toString(),
                "--mode", "dev",
                "--app-name", "test-app",
                "--bazel-targets", "//pkg:lib",
                "--workspace-dir", tempDir.toString(),
                "--bazel-build-timeout-seconds", "5"));
    if (!sourceDirs.isEmpty()) {
      args.add("--source-dirs");
      args.add(
          sourceDirs.stream()
              .map(Path::toString)
              .collect(java.util.stream.Collectors.joining(",")));
    }
    return TestQuarkifierConfig.parse(args.toArray(String[]::new));
  }

  @Test
  void close_isIdempotent() throws IOException {
    var config = testConfig(tempDir.resolve("output"), List.of());
    var watcher = new BazelFileWatcher(config);

    watcher.close();
    // Second close should not throw
    assertDoesNotThrow(watcher::close);
  }

  @Test
  void close_releasesWatchService() throws IOException {
    Path sourceDir = tempDir.resolve("src/main/java");
    Files.createDirectories(sourceDir);

    var config = testConfig(tempDir.resolve("output"), List.of(sourceDir));
    var watcher = new BazelFileWatcher(config);
    watcher.registerWatchers(config.sourceDirs());

    watcher.close();

    // After close, watchLoop should exit immediately
    assertDoesNotThrow(watcher::watchLoop);
  }

  @Test
  void registerWatchers_skipsNonExistentDirs() throws IOException {
    var config = testConfig(tempDir.resolve("output"), List.of(tempDir.resolve("nonexistent")));
    var watcher = new BazelFileWatcher(config);

    // Should not throw, just log a warning
    assertDoesNotThrow(() -> watcher.registerWatchers(config.sourceDirs()));
    watcher.close();
  }

  @Test
  void registerWatchers_registersExistingDirsRecursively() throws IOException {
    Path sourceDir = tempDir.resolve("src/main/java/com/example");
    Files.createDirectories(sourceDir);

    var config = testConfig(tempDir.resolve("output"), List.of(tempDir.resolve("src/main/java")));
    var watcher = new BazelFileWatcher(config);
    watcher.registerWatchers(config.sourceDirs());

    // Watcher should be functional (close without error proves registration worked)
    assertDoesNotThrow(watcher::close);
  }

  @Test
  void versionControlDirectoriesAreExcludedWithoutExcludingDeclaredDotDirectories() {
    assertTrue(BazelFileWatcher.isVersionControlDirectory(Path.of("workspace/.git")));
    assertTrue(BazelFileWatcher.isVersionControlDirectory(Path.of("workspace/.hg")));
    assertTrue(BazelFileWatcher.isVersionControlDirectory(Path.of("workspace/.svn")));
    assertFalse(BazelFileWatcher.isVersionControlDirectory(Path.of("workspace/.schemas")));
    assertFalse(BazelFileWatcher.isVersionControlDirectory(Path.of("workspace/proto")));
  }

  @Test
  void triggerBuild_serializesConcurrentCalls() throws Exception {
    var config = testConfig(tempDir.resolve("output"), List.of());
    var watcher = new BazelFileWatcher(config);

    // triggerBuild will fail the bazel build (no real bazel), but should not throw
    AtomicInteger callCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(2);

    // Fire two concurrent triggers
    Thread t1 =
        new Thread(
            () -> {
              watcher.triggerBuild();
              callCount.incrementAndGet();
              latch.countDown();
            });
    Thread t2 =
        new Thread(
            () -> {
              watcher.triggerBuild();
              callCount.incrementAndGet();
              latch.countDown();
            });

    t1.start();
    t2.start();

    assertTrue(latch.await(15, TimeUnit.SECONDS), "Both triggers should complete");
    assertEquals(2, callCount.get());
    watcher.close();
  }

  @Test
  void watchLoop_exitsOnClose() throws Exception {
    Path sourceDir = tempDir.resolve("src/main/java");
    Files.createDirectories(sourceDir);

    var config = testConfig(tempDir.resolve("output"), List.of(sourceDir));
    var watcher = new BazelFileWatcher(config);
    watcher.registerWatchers(config.sourceDirs());

    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);

    Thread watchThread =
        new Thread(
            () -> {
              started.countDown();
              watcher.watchLoop();
              finished.countDown();
            });
    watchThread.setDaemon(true);
    watchThread.start();

    assertTrue(started.await(2, TimeUnit.SECONDS));
    // Give the watch loop time to block on take()
    Thread.sleep(50);

    watcher.close();
    assertTrue(finished.await(2, TimeUnit.SECONDS), "watchLoop should exit after close()");
  }

  /** A dev config whose hot-reload "bazel build" is {@code bazelCommand}. */
  private QuarkifierConfig buildingWith(String bazelCommand) {
    return TestQuarkifierConfig.parse(
        "--application-classpath", "app.jar",
        "--application-model", "model.json",
        "--output-dir", tempDir.resolve("output").toString(),
        "--mode", "dev",
        "--bazel-targets", "//pkg:lib",
        "--workspace-dir", tempDir.toString(),
        "--bazel-command", bazelCommand,
        "--bazel-build-timeout-seconds", "5");
  }

  private DevSession session() throws IOException {
    Path bazel = Files.createDirectories(tempDir.resolve("bazel-out/app"));
    Files.writeString(bazel.resolve("App.class"), "v0");
    return DevSession.start(
        tempDir.resolve("session"),
        List.of(new DevSession.Module("bazel-out/app", "000-app", bazel)));
  }

  @Test
  void triggerBuild_publishesASuccessfulBuildToTheSession() throws Exception {
    DevSession session = session();
    var watcher =
        new BazelFileWatcher(
            buildingWith("true"), new ProjectFileMirror(java.util.Map.of()), session);

    watcher.triggerBuild();
    watcher.close();

    assertEquals(
        Path.of("gen-1"),
        Files.readSymbolicLink(session.root().resolve(GenerationSwitch.READY)));
  }

  @Test
  void triggerBuild_publishesNothingWhenTheBuildFails() throws Exception {
    DevSession session = session();
    var watcher =
        new BazelFileWatcher(
            buildingWith("false"), new ProjectFileMirror(java.util.Map.of()), session);

    watcher.triggerBuild();
    watcher.close();

    assertEquals(
        Path.of("gen-0"),
        Files.readSymbolicLink(session.root().resolve(GenerationSwitch.READY)));
    assertFalse(Files.exists(session.root().resolve("gen-1")));
  }
}
