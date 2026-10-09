package com.clementguillot.quarkus.web.testing;

import com.google.devtools.build.runfiles.Runfiles;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/** Checks the real frontend resource archive against every original esbuild asset. */
public final class FrontendArchiveTest {
  public static void main(String[] args) throws Exception {
    Runfiles runfiles = Runfiles.create();
    Path archive = Path.of(runfiles.rlocation(args[0]));
    Path bundle = Path.of(runfiles.rlocation(args[1])).toRealPath();
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      Set<String> names = new HashSet<>();
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        String name = entries.nextElement().getName();
        require(!name.startsWith("/") && !name.contains("../"), "Invalid archive path: " + name);
        require(names.add(name), "Duplicate archive entry: " + name);
      }
      String html = text(zip, "META-INF/resources/index.html");
      require(html.equals(text(zip, "templates/spa.html")), "Static and SPA shells differ");
      require(!html.contains("bazel:web-assets"), "Unexpanded HTML marker");
      require(text(zip, "META-INF/beans.xml").contains("bean-discovery-mode=\"none\""), "Missing indexing marker");
      int assets = 0;
      int js = 0;
      int css = 0;
      try (var files = Files.walk(bundle)) {
        for (Path file : files.filter(Files::isRegularFile).toList()) {
          String relative = bundle.relativize(file).toString().replace('\\', '/');
          String name = "META-INF/resources/_static/" + relative;
          require(zip.getEntry(name) != null, "Missing bundled asset: " + relative);
          try (InputStream original = Files.newInputStream(file);
               InputStream packaged = zip.getInputStream(zip.getEntry(name))) {
            require(MessageDigest.isEqual(digest(original), digest(packaged)), "Changed asset: " + relative);
          }
          assets++;
          if (relative.matches("app-[^.]+\\.js")) {
            js++;
            require(html.contains("src=\"/_static/" + relative + "\""), "Missing JS reference");
          }
          if (relative.matches("app-[^.]+\\.css")) {
            css++;
            require(html.contains("href=\"/_static/" + relative + "\""), "Missing CSS reference");
          }
        }
      }
      require(js == 1 && css == 1, "Expected one JS and one CSS entry");
      System.out.println("Verified " + assets + " unchanged assets and matching HTML shells in " + archive);
    }
  }

  private static byte[] digest(InputStream input) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[64 * 1024];
    int count;
    while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    return digest.digest();
  }

  private static String text(ZipFile zip, String name) throws Exception {
    require(zip.getEntry(name) != null, "Missing archive entry: " + name);
    try (InputStream input = zip.getInputStream(zip.getEntry(name))) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
