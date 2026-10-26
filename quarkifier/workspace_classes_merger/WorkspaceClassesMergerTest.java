package com.clementguillot.quarkifier.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.IndexWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceClassesMergerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void indexesEveryMergedClassAndReplacesIncompleteInputIndexes() throws Exception {
    Path entities =
        archive(
            "entities.jar",
            Map.of(
                classPath(CustomerEntity.class),
                classBytes(CustomerEntity.class),
                "META-INF/jandex.idx",
                indexBytes(CustomerEntity.class)));
    Path companions =
        archive(
            "companions.jar",
            Map.of(
                classPath(CustomerCompanion.class),
                classBytes(CustomerCompanion.class),
                "META-INF/jandex.idx",
                indexBytes(CustomerCompanion.class),
                "META-INF/beans.xml",
                "<beans/>".getBytes(StandardCharsets.UTF_8)));
    Path output = temporaryDirectory.resolve("merged");

    WorkspaceClassesMerger.main(
        new String[] {output.toString(), entities.toString(), companions.toString()});

    Index index = readIndex(output);
    assertEquals(2, index.getKnownClasses().size());
    assertEquals(
        "customerNumber",
        index
            .getClassByName(DotName.createSimple(CustomerEntity.class.getName()))
            .recordComponents()
            .get(0)
            .name());
    assertNotNull(index.getClassByName(DotName.createSimple(CustomerCompanion.class.getName())));
    assertEquals("<beans/>", Files.readString(output.resolve("META-INF/beans.xml")));
  }

  @Test
  void indexBytesAreIndependentOfInputOrderAndIdenticalDuplicateClasses() throws Exception {
    Map<String, byte[]> classes =
        Map.of(
            classPath(CustomerEntity.class),
            classBytes(CustomerEntity.class),
            "META-INF/beans.xml",
            "<beans/>".getBytes(StandardCharsets.UTF_8));
    Path first = archive("first.jar", classes);
    Path second =
        archive(
            "second.jar",
            Map.of(
                classPath(CustomerEntity.class), classBytes(CustomerEntity.class),
                classPath(CustomerCompanion.class), classBytes(CustomerCompanion.class)));
    Path forward = temporaryDirectory.resolve("forward");
    Path reversed = temporaryDirectory.resolve("reversed");

    WorkspaceClassesMerger.main(
        new String[] {forward.toString(), first.toString(), second.toString()});
    WorkspaceClassesMerger.main(
        new String[] {reversed.toString(), second.toString(), first.toString()});

    assertEquals(2, readIndex(forward).getKnownClasses().size());
    assertArrayEquals(
        Files.readAllBytes(forward.resolve("META-INF/jandex.idx")),
        Files.readAllBytes(reversed.resolve("META-INF/jandex.idx")));
  }

  @Test
  void resourceOnlyBeanArchivesHaveAnEmptyReadableIndex() throws Exception {
    Path resources =
        archive(
            "resources.jar",
            Map.of(
                "META-INF/beans.xml",
                "<beans/>".getBytes(StandardCharsets.UTF_8),
                "application.properties",
                "quarkus.application.name=resource-fixture".getBytes(StandardCharsets.UTF_8)));
    Path output = temporaryDirectory.resolve("resources");

    WorkspaceClassesMerger.main(new String[] {output.toString(), resources.toString()});

    assertTrue(readIndex(output).getKnownClasses().isEmpty());
    assertTrue(Files.exists(output.resolve("application.properties")));
  }

  @Test
  void preservesUnindexedArchivesWithoutDiscoveryMarkers() throws Exception {
    Path classes =
        archive(
            "unindexed.jar",
            Map.of(
                classPath(CustomerEntity.class),
                classBytes(CustomerEntity.class),
                "application.properties",
                "quarkus.application.name=unindexed-fixture".getBytes(StandardCharsets.UTF_8)));
    Path output = temporaryDirectory.resolve("unindexed");

    WorkspaceClassesMerger.main(new String[] {output.toString(), classes.toString()});

    assertFalse(Files.exists(output.resolve("META-INF/jandex.idx")));
    assertArrayEquals(
        classBytes(CustomerEntity.class),
        Files.readAllBytes(output.resolve(classPath(CustomerEntity.class))));
    assertTrue(Files.exists(output.resolve("application.properties")));
  }

  private Index readIndex(Path output) throws Exception {
    try (InputStream input = Files.newInputStream(output.resolve("META-INF/jandex.idx"))) {
      return new IndexReader(input).read();
    }
  }

  private Path archive(String name, Map<String, byte[]> entries) throws Exception {
    Path archive = temporaryDirectory.resolve(name);
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
      for (String entry : entries.keySet().stream().sorted().toList()) {
        output.putNextEntry(new JarEntry(entry));
        output.write(entries.get(entry));
        output.closeEntry();
      }
    }
    return archive;
  }

  private static String classPath(Class<?> type) {
    return type.getName().replace('.', '/') + ".class";
  }

  private static byte[] indexBytes(Class<?> type) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new IndexWriter(output).write(Index.of(type));
    return output.toByteArray();
  }

  private static byte[] classBytes(Class<?> type) throws Exception {
    try (InputStream input = type.getResourceAsStream("/" + classPath(type))) {
      return input.readAllBytes();
    }
  }

  record CustomerEntity(long customerNumber) {}

  static final class CustomerCompanion {}
}
