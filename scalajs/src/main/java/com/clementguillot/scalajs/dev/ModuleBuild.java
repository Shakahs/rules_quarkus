package com.clementguillot.scalajs.dev;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One request end to end: prepare the state directory, compile, link.
 *
 * <p>A session — the compiler's loaders and the linker's caches — is kept per state directory
 * for as long as the process lives, and replaced when a request for the same directory names a
 * different compiler. Requests for one directory run one at a time.
 */
final class ModuleBuild {

  private static final Map<Path, Session> SESSIONS = new ConcurrentHashMap<>();

  private ModuleBuild() {}

  static boolean execute(ModuleRequest request, PrintWriter out) throws IOException {
    Session session =
        SESSIONS.compute(
            request.stateDir(),
            (dir, existing) ->
                existing != null && existing.serves(request) ? existing : new Session(request));
    synchronized (session) {
      return session.build(request, out);
    }
  }

  private static final class Session {
    private final String scalaVersion;
    private final List<Path> compilerJars;
    private final Path bridgeJar;
    private final ZincCompilation compilation;
    private final ScalaJsLink link = new ScalaJsLink();

    Session(ModuleRequest request) {
      this.scalaVersion = request.scalaVersion();
      this.compilerJars = request.compilerJars();
      this.bridgeJar = request.bridgeJar();
      this.compilation =
          new ZincCompilation(new CompilerInstance(scalaVersion, compilerJars, bridgeJar));
    }

    boolean serves(ModuleRequest request) {
      return scalaVersion.equals(request.scalaVersion())
          && compilerJars.equals(request.compilerJars())
          && bridgeJar.equals(request.bridgeJar());
    }

    boolean build(ModuleRequest request, PrintWriter out) throws IOException {
      StateDirectory state = new StateDirectory(request.stateDir());
      state.prepare(scalaVersion, bridgeJar);

      List<Path> sources = new ArrayList<>(request.sources());
      sources.addAll(SourceJars.extract(request.sourceJars(), state.sources()));

      boolean compiled =
          compilation.compile(
              state,
              sources,
              request.classpathJars(),
              request.scalacOptions(),
              new CompilerReporter(out),
              new WriterLogger(out));
      if (!compiled) {
        return false;
      }
      link.link(state.classes(), request.linkJars(), request.mainClass(), request.outputDir());
      return true;
    }
  }
}
