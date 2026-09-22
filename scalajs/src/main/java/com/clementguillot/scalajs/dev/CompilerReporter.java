package com.clementguillot.scalajs.dev;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import xsbti.Position;
import xsbti.Problem;
import xsbti.Reporter;
import xsbti.Severity;

/** Prints the compiler's diagnostics as they arrive and remembers whether any was an error. */
final class CompilerReporter implements Reporter {

  private final PrintWriter out;
  private final List<Problem> logged = new ArrayList<>();

  CompilerReporter(PrintWriter out) {
    this.out = out;
  }

  @Override
  public void reset() {
    logged.clear();
  }

  @Override
  public boolean hasErrors() {
    return logged.stream().anyMatch(problem -> problem.severity() == Severity.Error);
  }

  @Override
  public boolean hasWarnings() {
    return logged.stream().anyMatch(problem -> problem.severity() == Severity.Warn);
  }

  @Override
  public void printSummary() {
    long errors = logged.stream().filter(p -> p.severity() == Severity.Error).count();
    long warnings = logged.stream().filter(p -> p.severity() == Severity.Warn).count();
    if (errors > 0 || warnings > 0) {
      out.printf("%d error(s), %d warning(s)%n", errors, warnings);
    }
  }

  @Override
  public Problem[] problems() {
    return logged.toArray(new Problem[0]);
  }

  @Override
  public void log(Problem problem) {
    logged.add(problem);
    // Scala 3 renders its own diagnostics, with the source excerpt and the caret; the fallback
    // is for a bridge that does not.
    out.println(problem.rendered().orElseGet(() -> render(problem)));
  }

  @Override
  public void comment(Position pos, String msg) {
    out.println(location(pos) + msg);
  }

  private static String render(Problem problem) {
    return location(problem.position())
        + problem.severity().toString().toLowerCase(java.util.Locale.ROOT)
        + ": "
        + problem.message();
  }

  private static String location(Position position) {
    StringBuilder location = new StringBuilder();
    position.sourcePath().ifPresent(location::append);
    position.line().ifPresent(line -> location.append(':').append(line));
    if (location.length() > 0) {
      location.append(": ");
    }
    return location.toString();
  }
}
