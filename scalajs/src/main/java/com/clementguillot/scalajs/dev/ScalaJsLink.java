package com.clementguillot.scalajs.dev;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.scalajs.linker.PathIRContainer;
import org.scalajs.linker.PathOutputDirectory;
import org.scalajs.linker.StandardImpl;
import org.scalajs.logging.NullLogger$;
import scala.Tuple2;
import scala.collection.immutable.Seq;
import scala.concurrent.Await$;
import scala.concurrent.ExecutionContext;
import scala.concurrent.ExecutionContext$;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration$;
import scala.jdk.javaapi.CollectionConverters;

/**
 * The Scala.js linker in its development configuration: one ES module, no optimizer, no source
 * map, and an incremental linker that keeps its state between links.
 *
 * <p>Linking reads IR from the class directory and from every jar on the link classpath. A
 * persistent worker keeps the IR cache, so an unchanged jar is not read twice; a fresh process
 * reads them all once, which is the cost of a cold start and nothing more.
 *
 * <p>The linker's API lives in {@code org.scalajs.linker.interface}, a package Java source cannot
 * name because {@code interface} is a keyword. Its classes are reached by their binary names
 * instead; everything outside that package is called directly.
 */
final class ScalaJsLink {

  private static final String API = "org.scalajs.linker.interface.";

  private final Object irCache;
  private final Object linker;
  private final Method cached;
  private final Method linkMethod;
  private final Method mainMethod;

  ScalaJsLink() {
    try {
      Class<?> configClass = Class.forName(API + "StandardConfig");
      Class<?> moduleKindClass = Class.forName(API + "ModuleKind");
      Object esModule = Class.forName(API + "ModuleKind$ESModule$").getField("MODULE$").get(null);
      Object config = configClass.getMethod("apply").invoke(null);
      config = configClass.getMethod("withModuleKind", moduleKindClass).invoke(config, esModule);
      config = configClass.getMethod("withOptimizer", boolean.class).invoke(config, false);
      config = configClass.getMethod("withSourceMap", boolean.class).invoke(config, false);
      config = configClass.getMethod("withBatchMode", boolean.class).invoke(config, false);
      this.linker =
          StandardImpl.class.getMethod("clearableLinker", configClass).invoke(null, config);

      Class<?> irFileCacheClass = Class.forName(API + "IRFileCache");
      Object irFileCache = StandardImpl.class.getMethod("irFileCache").invoke(null);
      this.irCache = irFileCacheClass.getMethod("newCache").invoke(irFileCache);
      this.cached =
          Class.forName(API + "IRFileCache$Cache")
              .getMethod("cached", Seq.class, ExecutionContext.class);

      Class<?> loggerClass = Class.forName("org.scalajs.logging.Logger");
      this.linkMethod =
          Class.forName(API + "Linker")
              .getMethod(
                  "link",
                  Seq.class,
                  Seq.class,
                  Class.forName(API + "OutputDirectory"),
                  loggerClass,
                  ExecutionContext.class);
      this.mainMethod =
          Class.forName(API + "ModuleInitializer")
              .getMethod("mainMethod", String.class, String.class);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("The Scala.js linker on the classpath is unusable", e);
    }
  }

  /** Links the IR under {@code classes} and in {@code jars} into {@code outputDir}. */
  void link(Path classes, List<Path> jars, String mainClass, Path outputDir) throws IOException {
    Files.createDirectories(outputDir);
    ExecutionContext ec = ExecutionContext$.MODULE$.global();
    List<Path> classpath = new ArrayList<>(jars.size() + 1);
    classpath.add(classes);
    classpath.addAll(jars);

    Tuple2<?, ?> found =
        (Tuple2<?, ?>)
            await(PathIRContainer.fromClasspath(CollectionConverters.asScala(classpath).toSeq(), ec));
    Object irFiles = await((Future<?>) invoke(cached, irCache, found._1(), ec));
    // The module's entry point is a no-argument `main`, which is a different initializer from
    // the `main(Array[String])` one; naming the wrong one fails at link time.
    Object initializer = invoke(mainMethod, null, mainClass, "main");
    Seq<Object> initializers = CollectionConverters.asScala(List.of(initializer)).toSeq();
    Object output = PathOutputDirectory.apply(outputDir);
    await(
        (Future<?>)
            invoke(linkMethod, linker, irFiles, initializers, output, NullLogger$.MODULE$, ec));
  }

  private static Object invoke(Method method, Object target, Object... args) {
    try {
      return method.invoke(target, args);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Scala.js linker call " + method.getName() + " failed", e);
    }
  }

  private static Object await(Future<?> future) {
    try {
      return Await$.MODULE$.result(future, Duration$.MODULE$.Inf());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (java.util.concurrent.TimeoutException e) {
      throw new IllegalStateException(e);
    }
  }
}
