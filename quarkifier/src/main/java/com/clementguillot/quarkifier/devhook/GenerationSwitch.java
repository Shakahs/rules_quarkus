package com.clementguillot.quarkifier.devhook;

import io.quarkus.dev.spi.HotReplacementContext;
import io.quarkus.dev.spi.HotReplacementSetup;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import org.jboss.logging.Logger;

/**
 * Switches the dev application to the newest complete Bazel build, inside Quarkus's scan.
 *
 * <p>The dev modules' class paths run through one symbolic link, {@value #CURRENT}, in the dev
 * session directory: each module is {@code current/<module>}. After every successful hot-reload
 * build the watcher links the build's outputs into a new generation directory and points {@value
 * #READY} at it ({@code DevSession}); Bazel's own outputs are never read by the application, since
 * Bazel deletes an action's outputs before running it again.
 *
 * <p>Only this class moves {@value #CURRENT}, and only from the step Quarkus runs at the start of
 * every scan ({@link HotReplacementContext#addPreScanStep}). {@code RuntimeUpdatesProcessor} runs
 * those steps under its scan lock, before it checks the class paths for changes and restarts. One
 * rename moves every module at once, so a scan and the restart it causes always read a single
 * build, and the application's class paths never change between scans.
 *
 * <p>The watcher publishes a generation by renaming {@value #READY}; this class watches the session
 * directory for that rename and starts a scan itself, so a build reaches the application without
 * waiting for an HTTP request to trigger the scan.
 *
 * <p>Quarkus loads this class with {@link java.util.ServiceLoader} from its base runtime class
 * loader, which holds the dev application model's non-reloadable runtime dependencies; the launcher
 * adds it there as a jar of its own. It must therefore stay one self-contained class: the JDK, the
 * development-mode SPI, and JBoss Logging, all of which that class loader provides.
 */
public final class GenerationSwitch implements HotReplacementSetup {

  /** The system property naming the dev session directory. */
  public static final String SESSION_PROPERTY = "rules_quarkus.dev.session";

  /** The link the dev modules' class paths run through. */
  public static final String CURRENT = "current";

  /** The link to the newest complete generation. */
  public static final String READY = "ready";

  /**
   * The directory this class writes {@value #TRIGGER_FILE} into whenever it moves {@value
   * #CURRENT}. It is the source directory of the continuous tests: Quarkus watches it and, on a
   * change, looks for changed classes and runs the tests they affect.
   */
  public static final String TEST_TRIGGER = "test-trigger";

  /** The file in {@value #TEST_TRIGGER} naming the generation the application now runs. */
  public static final String TRIGGER_FILE = "generation";

  private static final Logger LOGGER = Logger.getLogger(GenerationSwitch.class);

  private WatchService publications;

  @Override
  public void setupHotDeployment(HotReplacementContext context) {
    String session = System.getProperty(SESSION_PROPERTY);
    if (session == null) {
      return;
    }
    Path root = Path.of(session);
    context.addPreScanStep(() -> switchToReady(root));
    try {
      publications = root.getFileSystem().newWatchService();
      root.register(publications, StandardWatchEventKinds.ENTRY_CREATE);
    } catch (IOException e) {
      LOGGER.errorf(
          e, "Cannot watch %s for new builds; they reach the application on its next scan", root);
      return;
    }
    WatchService watched = publications;
    Thread scanner = new Thread(() -> scanOnPublication(watched, context), "rules-quarkus-dev");
    scanner.setDaemon(true);
    scanner.start();
  }

  @Override
  public void close() {
    if (publications != null) {
      try {
        publications.close();
      } catch (IOException e) {
        LOGGER.debugf(e, "Closing the dev session watch failed");
      }
    }
  }

  /**
   * Points {@value #CURRENT} at the generation {@value #READY} names, when they differ.
   *
   * <p>A failure leaves {@value #CURRENT} where it was, which is a complete build too, so it is
   * logged and the scan goes on.
   *
   * @param root the dev session directory
   */
  public static void switchToReady(Path root) {
    try {
      Path ready = Files.readSymbolicLink(root.resolve(READY));
      Path current = root.resolve(CURRENT);
      if (ready.equals(Files.readSymbolicLink(current))) {
        return;
      }
      Path next = root.resolve(CURRENT + ".next");
      Files.deleteIfExists(next);
      Files.createSymbolicLink(next, ready);
      Files.move(next, current, StandardCopyOption.ATOMIC_MOVE);
      LOGGER.infof("Switched to the Bazel build in %s", root.resolve(ready));
    } catch (IOException e) {
      LOGGER.errorf(e, "Cannot switch %s to its newest build", root);
      return;
    }
    triggerTests(root);
  }

  /**
   * Tells the continuous tests that the classes changed.
   *
   * <p>Quarkus looks for changed test and application classes when a test source changes, and
   * otherwise only once a second on a platform without a file watch service. A dev module declares
   * no sources, so this class gives the tests one file to watch. Quarkus's test scan waits for the
   * scan lock this step runs under, so it reads the generation just switched to.
   *
   * @param root the dev session directory
   */
  private static void triggerTests(Path root) {
    Path trigger = root.resolve(TEST_TRIGGER);
    if (!Files.isDirectory(trigger)) {
      return;
    }
    try {
      Files.writeString(
          trigger.resolve(TRIGGER_FILE),
          Files.readSymbolicLink(root.resolve(CURRENT)).toString());
    } catch (IOException e) {
      LOGGER.errorf(e, "Cannot tell the continuous tests about the build in %s", root);
    }
  }

  /** Starts a scan whenever {@value #READY} is renamed into place, until the watch is closed. */
  private static void scanOnPublication(WatchService watched, HotReplacementContext context) {
    try {
      while (true) {
        WatchKey key = watched.take();
        boolean published =
            key.pollEvents().stream()
                .anyMatch(
                    event ->
                        event.kind() == StandardWatchEventKinds.OVERFLOW
                            || READY.equals(String.valueOf(event.context())));
        key.reset();
        if (published) {
          scan(context);
        }
      }
    } catch (ClosedWatchServiceException e) {
      LOGGER.debug("The dev session watch is closed");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void scan(HotReplacementContext context) {
    try {
      context.doScan(false);
    } catch (Exception e) {
      LOGGER.errorf(e, "Scanning for the new build failed");
    }
  }
}
