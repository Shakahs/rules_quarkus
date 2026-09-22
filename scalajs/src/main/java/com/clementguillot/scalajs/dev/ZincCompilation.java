package com.clementguillot.scalajs.dev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import sbt.internal.inc.FileAnalysisStore;
import sbt.internal.inc.IncrementalCompilerImpl;
import sbt.internal.inc.PlainVirtualFileConverter;
import xsbti.CompileFailed;
import xsbti.FileConverter;
import xsbti.Logger;
import xsbti.VirtualFile;
import xsbti.compile.AnalysisContents;
import xsbti.compile.AnalysisStore;
import xsbti.compile.CompileOptions;
import xsbti.compile.CompileOrder;
import xsbti.compile.CompileResult;
import xsbti.compile.Inputs;
import xsbti.compile.PreviousResult;

/**
 * Zinc over one compiler: incremental compilation to the state directory's class directory.
 *
 * <p>An instance holds the compiler's class loaders and zinc's bridge loader, which is what a
 * persistent worker keeps warm. Nothing else is remembered between compilations: the analysis is
 * read from and written back to the state directory each time, so a fresh process picks up where
 * the last one left off.
 */
final class ZincCompilation {

  private static final int MAX_ERRORS = 1000;
  private static final String SCALAJS_FLAG = "-scalajs";

  private final CompilerInstance compiler;
  private final IncrementalCompilerImpl zinc = new IncrementalCompilerImpl();
  private final FileConverter converter = PlainVirtualFileConverter.converter();

  ZincCompilation(CompilerInstance compiler) {
    this.compiler = compiler;
  }

  /**
   * Compiles the sources incrementally against the analysis in the state directory.
   *
   * @return whether the compilation succeeded; problems have been written to the reporter
   */
  boolean compile(
      StateDirectory state,
      List<Path> sources,
      List<Path> classpath,
      List<String> scalacOptions,
      CompilerReporter reporter,
      Logger logger)
      throws IOException {
    AnalysisStore store = FileAnalysisStore.binary(state.analysis().toFile());
    Optional<AnalysisContents> previous = store.get();
    if (previous.isEmpty()) {
      // Products without an analysis describing them are stale by definition.
      state.resetClasses();
    }
    Files.createDirectories(state.backup());

    Inputs inputs =
        Inputs.of(
            compiler.compilers(),
            options(state, sources, classpath, scalacOptions),
            IncrementalSetup.setup(state, reporter, logger),
            previousResult(previous));
    try {
      CompileResult result = zinc.compile(inputs, logger);
      store.set(AnalysisContents.create(result.analysis(), result.setup()));
      return true;
    } catch (CompileFailed e) {
      reporter.printSummary();
      return false;
    }
  }

  private CompileOptions options(
      StateDirectory state, List<Path> sources, List<Path> classpath, List<String> scalacOptions) {
    List<String> options = new ArrayList<>();
    options.add(SCALAJS_FLAG);
    options.addAll(scalacOptions);
    // The class directory comes first, as sbt passes it: an incremental compilation recompiles
    // only the invalidated sources, and finds the rest of the module there. Beyond it the
    // classpath is exactly the module's: its dependencies carry the Scala.js standard library, and
    // the compiler's JVM one has no business beside it.
    List<Path> compileClasspath = new ArrayList<>();
    compileClasspath.add(state.classes());
    compileClasspath.addAll(classpath);
    return CompileOptions.of(
        virtualFiles(compileClasspath),
        virtualFiles(sources),
        state.classes(),
        options.toArray(new String[0]),
        new String[0],
        MAX_ERRORS,
        Function.identity(),
        CompileOrder.Mixed,
        Optional.empty(),
        Optional.of(converter),
        Optional.empty(),
        Optional.empty());
  }

  private static PreviousResult previousResult(Optional<AnalysisContents> previous) {
    return previous
        .map(
            contents ->
                PreviousResult.of(
                    Optional.of(contents.getAnalysis()), Optional.of(contents.getMiniSetup())))
        .orElseGet(() -> PreviousResult.of(Optional.empty(), Optional.empty()));
  }

  private VirtualFile[] virtualFiles(List<Path> paths) {
    return paths.stream().map(converter::toVirtualFile).toArray(VirtualFile[]::new);
  }
}
