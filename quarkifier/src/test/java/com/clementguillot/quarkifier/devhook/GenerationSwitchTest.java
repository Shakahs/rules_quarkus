package com.clementguillot.quarkifier.devhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clementguillot.quarkifier.dev.DevSession;
import io.quarkus.dev.spi.HotReplacementContext;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link GenerationSwitch}. */
class GenerationSwitchTest {

  @TempDir Path tempDir;

  private GenerationSwitch installed;

  @AfterEach
  void tearDown() {
    System.clearProperty(GenerationSwitch.SESSION_PROPERTY);
    if (installed != null) {
      installed.close();
    }
  }

  private DevSession session(String content) throws Exception {
    Path bazel = Files.createDirectories(tempDir.resolve("bazel-out/app"));
    Files.writeString(bazel.resolve("App.class"), content);
    return DevSession.start(
        tempDir.resolve("session"), List.of(new DevSession.Module("bazel-out/app", "000-app", bazel)));
  }

  private static Path target(Path root, String link) throws Exception {
    return Files.readSymbolicLink(root.resolve(link));
  }

  /** The pre-scan steps a dev application registers, and the scans it is asked for. */
  private static final class Scans {
    final List<Runnable> preScanSteps = new CopyOnWriteArrayList<>();
    final LinkedBlockingQueue<Boolean> requested = new LinkedBlockingQueue<>();

    HotReplacementContext context() {
      return (HotReplacementContext)
          Proxy.newProxyInstance(
              HotReplacementContext.class.getClassLoader(),
              new Class<?>[] {HotReplacementContext.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "addPreScanStep" -> {
                      preScanSteps.add((Runnable) args[0]);
                      yield null;
                    }
                    case "doScan" -> {
                      // As Quarkus does: the pre-scan steps run first, inside the scan.
                      preScanSteps.forEach(Runnable::run);
                      requested.add((Boolean) args[0]);
                      yield true;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                  });
    }
  }

  @Test
  void switchToReady_movesCurrentToTheReadyGeneration() throws Exception {
    DevSession session = session("v0");
    session.publish();

    GenerationSwitch.switchToReady(session.root());

    assertEquals(Path.of("gen-1"), target(session.root(), GenerationSwitch.CURRENT));
    assertFalse(
        Files.exists(
            session.root().resolve(GenerationSwitch.CURRENT + ".next"), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void switchToReady_leavesCurrentAloneWhenItIsReady() throws Exception {
    DevSession session = session("v0");
    Path current = session.root().resolve(GenerationSwitch.CURRENT);
    var before = Files.readAttributes(current, "unix:ino", LinkOption.NOFOLLOW_LINKS);

    GenerationSwitch.switchToReady(session.root());

    assertEquals(before, Files.readAttributes(current, "unix:ino", LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void setupHotDeployment_withoutASession_registersNothing() {
    var scans = new Scans();

    installed = new GenerationSwitch();
    installed.setupHotDeployment(scans.context());

    assertTrue(scans.preScanSteps.isEmpty());
  }

  @Test
  void theScanStepSwitchesToTheReadyGeneration() throws Exception {
    DevSession session = session("v0");
    session.publish();
    System.setProperty(GenerationSwitch.SESSION_PROPERTY, session.root().toString());
    var scans = new Scans();
    installed = new GenerationSwitch();
    installed.setupHotDeployment(scans.context());

    assertEquals(1, scans.preScanSteps.size());
    scans.preScanSteps.get(0).run();

    assertEquals(Path.of("gen-1"), target(session.root(), GenerationSwitch.CURRENT));
  }

  @Test
  void publishingABuildStartsAScanThatSwitchesToIt() throws Exception {
    Path bazel = Files.createDirectories(tempDir.resolve("bazel-out/app"));
    Files.writeString(bazel.resolve("App.class"), "v0");
    DevSession session =
        DevSession.start(
            tempDir.resolve("session"),
            List.of(new DevSession.Module("bazel-out/app", "000-app", bazel)));
    System.setProperty(GenerationSwitch.SESSION_PROPERTY, session.root().toString());
    var scans = new Scans();
    installed = new GenerationSwitch();
    installed.setupHotDeployment(scans.context());

    Files.delete(bazel.resolve("App.class"));
    Files.writeString(bazel.resolve("App.class"), "v1");
    session.publish();

    assertEquals(Boolean.FALSE, scans.requested.poll(10, TimeUnit.SECONDS), "a scan was requested");
    assertEquals(Path.of("gen-1"), target(session.root(), GenerationSwitch.CURRENT));
    assertEquals(
        "v1", Files.readString(session.classPaths().get("bazel-out/app").resolve("App.class")));
  }
}
