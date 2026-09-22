package com.clementguillot.scalajs.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.devtools.build.runfiles.Runfiles;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the tool against a real compiler, bridge and Scala.js library.
 *
 * <p>The module is two objects, an entry point calling a greeting. The sequence edits the
 * greeting's body, breaks it, and repairs it, and after each run checks what the state
 * directory and the linked module say about which class was recompiled.
 */
class ScalaJsDevMainTest {

  private static final String SCALA_VERSION = "3.9.0";
  private static final String GREETING_V1 = "Hello, world";
  private static final String GREETING_V2 = "Hello again, world";

  private static Path bridgeJar;
  private static List<Path> compilerJars;
  private static List<Path> libraryJars;

  @TempDir Path tempDir;

  private Path sourceDir;
  private Path stateDir;
  private Path outputDir;

  @BeforeAll
  static void locateToolchain() throws IOException {
    Runfiles.Preloaded runfiles = Runfiles.preload();
    compilerJars = readManifest(runfiles, System.getProperty("scalajs.dev.test.compilerManifest"));
    libraryJars = readManifest(runfiles, System.getProperty("scalajs.dev.test.libraryManifest"));
    bridgeJar =
        compilerJars.stream()
            .filter(jar -> jar.getFileName().toString().contains("scala3-sbt-bridge"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No bridge among " + compilerJars));
  }

  @BeforeEach
  void writeModule() throws IOException {
    sourceDir = tempDir.resolve("src");
    stateDir = tempDir.resolve("state");
    outputDir = tempDir.resolve("out");
    Files.createDirectories(sourceDir);
    writeSource(
        "Main.scala",
        """
        package demo

        import scala.scalajs.js

        object Main {
          def main(): Unit = js.Dynamic.global.console.log(Greeting.text)
        }
        """);
    writeGreeting(GREETING_V1);
  }

  @Test
  void compilesLinksAndRecompilesOnlyTheEditedClass() throws IOException, InterruptedException {
    StringWriter log = new StringWriter();
    assertEquals(0, ScalaJsDevMain.run(arguments(), new PrintWriter(log)), log.toString());
    Path module = outputDir.resolve("main.js");
    assertTrue(Files.exists(module), "linked module");
    assertTrue(Files.readString(module).contains(GREETING_V1));
    Path mainIr = stateDir.resolve("classes/demo/Main$.sjsir");
    Path greetingIr = stateDir.resolve("classes/demo/Greeting$.sjsir");
    assertTrue(Files.exists(mainIr) && Files.exists(greetingIr), "IR beside the class files");
    byte[] mainBefore = Files.readAllBytes(mainIr);
    byte[] greetingBefore = Files.readAllBytes(greetingIr);

    // A fresh process after an edit: the state directory alone carries the increment.
    writeGreeting(GREETING_V2);
    assertEquals(0, runInFreshProcess(), "second run in a fresh process");
    assertTrue(Files.readString(module).contains(GREETING_V2), "relinked module");
    assertFalse(Files.readString(module).contains(GREETING_V1));
    assertNotEquals(List.of(greetingBefore), List.of(Files.readAllBytes(greetingIr)));
    assertEquals(
        new String(mainBefore, StandardCharsets.ISO_8859_1),
        new String(Files.readAllBytes(mainIr), StandardCharsets.ISO_8859_1),
        "the entry point was not recompiled");
  }

  @Test
  void recompilesAClassAgainstTheModuleClassesItLeftAlone() throws IOException {
    StringWriter log = new StringWriter();
    assertEquals(0, ScalaJsDevMain.run(arguments(), new PrintWriter(log)), log.toString());
    Path greetingIr = stateDir.resolve("classes/demo/Greeting$.sjsir");
    byte[] greetingBefore = Files.readAllBytes(greetingIr);

    // Only the entry point is invalidated; it still refers to the greeting, which the compiler
    // has to find among the classes the previous run produced.
    writeSource(
        "Main.scala",
        """
        package demo

        import scala.scalajs.js

        object Main {
          def main(): Unit = js.Dynamic.global.console.log("Greeting: " + Greeting.text)
        }
        """);
    StringWriter second = new StringWriter();
    assertEquals(0, ScalaJsDevMain.run(arguments(), new PrintWriter(second)), second.toString());
    assertTrue(Files.readString(outputDir.resolve("main.js")).contains("Greeting: "));
    assertEquals(
        new String(greetingBefore, StandardCharsets.ISO_8859_1),
        new String(Files.readAllBytes(greetingIr), StandardCharsets.ISO_8859_1),
        "the greeting was not recompiled");
  }

  @Test
  void failedCompilationLeavesTheStateLinkable() throws IOException {
    StringWriter log = new StringWriter();
    assertEquals(0, ScalaJsDevMain.run(arguments(), new PrintWriter(log)), log.toString());

    writeSource("Greeting.scala", "package demo\n\nobject Greeting { def text: String = 1 }\n");
    StringWriter failure = new StringWriter();
    assertEquals(1, ScalaJsDevMain.run(arguments(), new PrintWriter(failure)));
    assertTrue(failure.toString().contains("Greeting.scala"), failure.toString());
    assertTrue(failure.toString().contains("error"), failure.toString());
    assertTrue(
        Files.exists(stateDir.resolve("classes/demo/Greeting$.sjsir")),
        "the previous product is restored");

    writeGreeting(GREETING_V2);
    StringWriter repaired = new StringWriter();
    assertEquals(0, ScalaJsDevMain.run(arguments(), new PrintWriter(repaired)), repaired.toString());
    assertTrue(Files.readString(outputDir.resolve("main.js")).contains(GREETING_V2));
  }

  @Test
  void usageErrorIsReported() {
    StringWriter log = new StringWriter();
    assertEquals(2, ScalaJsDevMain.run(List.of("--bogus", "x"), new PrintWriter(log)));
    assertTrue(log.toString().contains("--bogus"));
  }

  private int runInFreshProcess() throws IOException, InterruptedException {
    Path argsFile = tempDir.resolve("args.txt");
    Files.write(argsFile, arguments(), StandardCharsets.UTF_8);
    String java = ProcessHandle.current().info().command().orElseThrow();
    Process process =
        new ProcessBuilder(
                java,
                "-Xmx2g",
                "-cp",
                System.getProperty("java.class.path"),
                ScalaJsDevMain.class.getName(),
                "@" + argsFile)
            .inheritIO()
            .start();
    assertTrue(process.waitFor(10, TimeUnit.MINUTES), "the fresh process finished");
    return process.exitValue();
  }

  private List<String> arguments() {
    List<String> args = new ArrayList<>();
    args.add("--state-dir");
    args.add(stateDir.toString());
    args.add("--output-dir");
    args.add(outputDir.toString());
    args.add("--main-class");
    args.add("demo.Main");
    args.add("--scala-version");
    args.add(SCALA_VERSION);
    args.add("--bridge-jar");
    args.add(bridgeJar.toString());
    for (Path jar : compilerJars) {
      args.add("--compiler-jar");
      args.add(jar.toString());
    }
    args.add("--source");
    args.add(sourceDir.resolve("Main.scala").toString());
    args.add("--source");
    args.add(sourceDir.resolve("Greeting.scala").toString());
    for (Path jar : libraryJars) {
      args.add("--classpath-jar");
      args.add(jar.toString());
      args.add("--link-jar");
      args.add(jar.toString());
    }
    args.add("--scalac-option");
    args.add("-deprecation");
    return args;
  }

  private void writeGreeting(String text) throws IOException {
    writeSource(
        "Greeting.scala",
        "package demo\n\nobject Greeting {\n  def text: String = \"" + text + "\"\n}\n");
  }

  private void writeSource(String name, String content) throws IOException {
    Files.writeString(sourceDir.resolve(name), content, StandardCharsets.UTF_8);
  }

  private static List<Path> readManifest(Runfiles.Preloaded runfiles, String manifestPath)
      throws IOException {
    Path manifest = Path.of(runfiles.unmapped().rlocation(manifestPath));
    List<Path> jars = new ArrayList<>();
    for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
      if (!line.isBlank()) {
        jars.add(Path.of(runfiles.unmapped().rlocation(line)));
      }
    }
    return jars;
  }
}
