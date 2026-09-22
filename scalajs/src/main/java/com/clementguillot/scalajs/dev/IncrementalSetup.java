package com.clementguillot.scalajs.dev;

import java.util.Optional;
import sbt.internal.inc.Locate;
import xsbti.Logger;
import xsbti.Reporter;
import xsbti.T2;
import xsbti.VirtualFile;
import xsbti.compile.AuxiliaryClassFiles;
import xsbti.compile.ClassFileManagerType;
import xsbti.compile.CompileAnalysis;
import xsbti.compile.CompilerCache;
import xsbti.compile.DefinesClass;
import xsbti.compile.IncOptions;
import xsbti.compile.PerClasspathEntryLookup;
import xsbti.compile.ScalaJSFiles;
import xsbti.compile.Setup;
import xsbti.compile.TastyFiles;
import xsbti.compile.TransactionalManagerType;

/**
 * How zinc is configured for a Scala.js module.
 *
 * <p>The Scala.js IR and the TASTy files count as class files, so that recompiling or deleting a
 * class removes its IR and its TASTy too — the linker reads that directory, and a stale IR file
 * would link a class that no longer exists. The class-file manager is transactional: a failed
 * compilation restores the products of the last successful one, and the state stays linkable.
 */
final class IncrementalSetup {

  private IncrementalSetup() {}

  @SuppressWarnings("unchecked")
  static Setup setup(StateDirectory state, Reporter reporter, Logger logger) {
    ClassFileManagerType transactional =
        TransactionalManagerType.of(state.backup().toFile(), logger);
    IncOptions incOptions =
        IncOptions.of()
            .withAuxiliaryClassFiles(
                new AuxiliaryClassFiles[] {ScalaJSFiles.instance(), TastyFiles.instance()})
            .withClassfileManagerType(Optional.of(transactional));
    return Setup.of(
        new ExternalBinariesOnly(),
        false,
        state.zincCache(),
        CompilerCache.fresh(),
        incOptions,
        reporter,
        Optional.empty(),
        (T2<String, String>[]) new T2<?, ?>[0]);
  }

  /** Every classpath entry is a binary: no dependency of a module carries a zinc analysis. */
  private static final class ExternalBinariesOnly implements PerClasspathEntryLookup {
    @Override
    public Optional<CompileAnalysis> analysis(VirtualFile classpathEntry) {
      return Optional.empty();
    }

    @Override
    public DefinesClass definesClass(VirtualFile classpathEntry) {
      return Locate.definesClass(classpathEntry);
    }
  }
}
