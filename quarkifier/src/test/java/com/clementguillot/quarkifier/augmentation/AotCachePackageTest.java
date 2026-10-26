package com.clementguillot.quarkifier.augmentation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AotCachePackageTest {
  @TempDir Path packageDirectory;

  @Test
  void restoresTrainingTimestampsAfterArtifactTransport(@TempDir Path testProject) throws Exception {
    Files.writeString(packageDirectory.resolve("app.aot"), "cache");
    Path runner = Files.writeString(packageDirectory.resolve("quarkus-run.jar"), "runner");
    Files.createDirectories(packageDirectory.resolve("lib"));
    Path dependency =
        Files.writeString(packageDirectory.resolve("lib/dependency with space.jar"), "dep");
    Files.setLastModifiedTime(runner, FileTime.from(Instant.ofEpochSecond(1700000001)));
    Files.setLastModifiedTime(dependency, FileTime.from(Instant.ofEpochSecond(1700000002)));

    AotCachePackage.recordJarTimestamps(packageDirectory);
    Files.setLastModifiedTime(runner, FileTime.from(Instant.EPOCH));
    Files.setLastModifiedTime(dependency, FileTime.from(Instant.EPOCH));

    assertEquals(
        "1700000002 lib/dependency with space.jar\0" + "1700000001 quarkus-run.jar\0",
        Files.readString(
            packageDirectory.resolve(AotCachePackage.FILE_NAME), StandardCharsets.UTF_8));
    AotCachePackage.stage(packageDirectory, testProject);
    assertEquals(
        1700000001,
        Files.getLastModifiedTime(testProject.resolve("quarkus-run.jar")).toInstant().getEpochSecond());
    assertEquals(
        1700000002,
        Files.getLastModifiedTime(testProject.resolve("lib/dependency with space.jar"))
            .toInstant()
            .getEpochSecond());
    assertEquals("runner", Files.readString(testProject.resolve("quarkus-run.jar")));
    assertEquals("cache", Files.readString(testProject.resolve("app.aot")));
    assertEquals(Instant.EPOCH, Files.getLastModifiedTime(runner).toInstant());
  }

  @Test
  void leavesPackagesWithoutACacheUnchanged() throws Exception {
    Files.writeString(packageDirectory.resolve("quarkus-run.jar"), "runner");
    AotCachePackage.recordJarTimestamps(packageDirectory);
    assertFalse(Files.exists(packageDirectory.resolve(AotCachePackage.FILE_NAME)));
  }
}
