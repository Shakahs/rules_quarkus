package com.clementguillot.scalajs.dev;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import sbt.internal.inc.AnalyzingCompiler;
import sbt.internal.inc.ScalaInstance;
import sbt.internal.inc.ZincUtil;
import sbt.internal.inc.javac.JavaTools;
import xsbti.compile.ClasspathOptionsUtil;
import xsbti.compile.Compilers;

/**
 * The compiler as zinc drives it: a {@code ScalaInstance} in class loaders of its own, and the
 * analyzing compiler that runs the bridge over it.
 *
 * <p>The loaders are parented on the platform and this tool's {@code xsbti}, never on the tool's
 * classpath, so the compiler's standard library and zinc's do not meet. The library jars get a
 * loader beneath the rest, as sbt lays it out. The bridge is left out: zinc loads it above the
 * compiler itself.
 */
final class CompilerInstance {

  private final String scalaVersion;
  private final ScalaInstance instance;
  private final AnalyzingCompiler scalac;

  CompilerInstance(String scalaVersion, List<Path> compilerJars, Path bridgeJar) {
    this.scalaVersion = scalaVersion;
    this.instance = scalaInstance(scalaVersion, compilerJars, bridgeJar);
    this.scalac = ZincUtil.scalaCompiler(instance, bridgeJar);
  }

  Compilers compilers() {
    return Compilers.of(
        scalac,
        JavaTools.directOrFork(
            instance, ClasspathOptionsUtil.noboot(scalaVersion), scala.Option.empty()));
  }

  private static ScalaInstance scalaInstance(
      String version, List<Path> compilerJars, Path bridgeJar) {
    List<File> library = new ArrayList<>();
    List<File> other = new ArrayList<>();
    List<File> all = new ArrayList<>();
    for (Path jar : compilerJars) {
      if (jar.equals(bridgeJar)) {
        continue;
      }
      File file = jar.toFile();
      all.add(file);
      if (isStandardLibrary(file)) {
        library.add(file);
      } else {
        other.add(file);
      }
    }
    if (library.isEmpty()) {
      throw new IllegalArgumentException(
          "No Scala standard library (scala/Predef.class) among the compiler jars");
    }
    ClassLoader shared = new XsbtiSharingClassLoader(ClassLoader.getSystemClassLoader());
    ClassLoader libraryLoader = new URLClassLoader(urls(library), shared);
    ClassLoader loader = new URLClassLoader(urls(other), libraryLoader);
    File[] allJars = all.toArray(new File[0]);
    return new ScalaInstance(
        version,
        loader,
        loader,
        libraryLoader,
        library.toArray(new File[0]),
        allJars,
        allJars,
        scala.Option.apply(version));
  }

  /**
   * Whether the jar is the standard library, told by what it holds: a build may rename jars
   * (rules_jvm_external serves them as {@code processed_<name>.jar}), so the name says nothing.
   */
  private static boolean isStandardLibrary(File jar) {
    try (ZipFile zip = new ZipFile(jar)) {
      return zip.getEntry("scala/Predef.class") != null;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read compiler jar " + jar, e);
    }
  }

  private static URL[] urls(List<File> files) {
    URL[] urls = new URL[files.size()];
    for (int i = 0; i < urls.length; i++) {
      try {
        urls[i] = files.get(i).toURI().toURL();
      } catch (MalformedURLException e) {
        throw new IllegalArgumentException("Not a file: " + files.get(i), e);
      }
    }
    return urls;
  }
}
