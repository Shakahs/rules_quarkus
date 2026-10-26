package io.quarkus.deployment.index;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.paths.DirectoryPathTree;
import io.quarkus.paths.OpenPathTree;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryIndexTest {
  @TempDir Path archive;

  @Test
  void dependencyDirectoryReusesItsSavedIndex() throws Exception {
    writeIndex();
    // If Quarkus parses classes instead of reading the saved index this is invalid bytecode.
    Files.write(archive.resolve("must-not-be-parsed.class"), new byte[] {0});

    assertNotNull(readDirectory(null).getClassByName(IndexedCustomer.class.getName()));
  }

  @Test
  void resourceExclusionsRebuildTheIndexInsteadOfReturningExcludedClasses() throws Exception {
    writeIndex();
    writeClass(IndexedCustomer.class);
    writeClass(IndexedInvoice.class);

    Index index = readDirectory(Set.of(classPath(IndexedCustomer.class)));

    assertNull(index.getClassByName(IndexedCustomer.class.getName()));
    assertNotNull(index.getClassByName(IndexedInvoice.class.getName()));
  }

  @Test
  void directoryWithoutSavedIndexStillIndexesItsClasses() throws Exception {
    writeClass(IndexedCustomer.class);

    assertNotNull(readDirectory(null).getClassByName(IndexedCustomer.class.getName()));
  }

  private Index readDirectory(Set<String> removed) throws Exception {
    Method method =
        ApplicationArchiveBuildStep.class.getDeclaredMethod(
            "indexPathTree", OpenPathTree.class, Set.class);
    method.setAccessible(true);
    return (Index) method.invoke(null, new DirectoryPathTree(archive), removed);
  }

  private void writeIndex() throws Exception {
    Path index = archive.resolve("META-INF/jandex.idx");
    Files.createDirectories(index.getParent());
    try (var output = Files.newOutputStream(index)) {
      new IndexWriter(output).write(Index.of(IndexedCustomer.class));
    }
  }

  private void writeClass(Class<?> fixture) throws Exception {
    Path output = archive.resolve(classPath(fixture));
    Files.createDirectories(output.getParent());
    try (InputStream input = fixture.getResourceAsStream("/" + classPath(fixture))) {
      Files.copy(input, output);
    }
  }

  private static String classPath(Class<?> fixture) {
    return fixture.getName().replace('.', '/') + ".class";
  }

  record IndexedCustomer(long customerNumber) {}

  record IndexedInvoice(long invoiceNumber) {}
}
