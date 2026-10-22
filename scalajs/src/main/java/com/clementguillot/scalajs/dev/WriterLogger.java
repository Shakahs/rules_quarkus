package com.clementguillot.scalajs.dev;

import java.io.PrintWriter;
import java.util.function.Supplier;
import xsbti.Logger;

/** Zinc and Scala.js diagnostics, at every level, on the Bazel request's writer. */
final class WriterLogger implements Logger, org.scalajs.logging.Logger {

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
    out.println("info: " + msg.get());
  }

  @Override
  public void debug(Supplier<String> msg) {
    out.println("debug: " + msg.get());
  }

  @Override
  public void trace(Supplier<Throwable> exception) {
    exception.get().printStackTrace(out);
  }

  @Override
  public void log(org.scalajs.logging.Level level, scala.Function0<String> message) {
    out.println(level.toString().toLowerCase(java.util.Locale.ROOT) + ": " + message.apply());
  }

  @Override
  public void trace(scala.Function0<Throwable> exception) {
    exception.apply().printStackTrace(out);
  }
}
