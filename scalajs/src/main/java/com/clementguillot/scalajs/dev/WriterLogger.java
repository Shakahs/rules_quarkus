package com.clementguillot.scalajs.dev;

import java.io.PrintWriter;
import java.util.function.Supplier;
import xsbti.Logger;

/** Zinc's log, at warning level and above, on the request's writer. */
final class WriterLogger implements Logger {

  private final PrintWriter out;

  WriterLogger(PrintWriter out) {
    this.out = out;
  }

  @Override
  public void error(Supplier<String> msg) {
    out.println("error: " + msg.get());
  }

  @Override
  public void warn(Supplier<String> msg) {
    out.println("warning: " + msg.get());
  }

  @Override
  public void info(Supplier<String> msg) {
    // Zinc's progress narration is noise in a build log.
  }

  @Override
  public void debug(Supplier<String> msg) {
    // See info.
  }

  @Override
  public void trace(Supplier<Throwable> exception) {
    exception.get().printStackTrace(out);
  }
}
