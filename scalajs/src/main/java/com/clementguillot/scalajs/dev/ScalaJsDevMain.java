package com.clementguillot.scalajs.dev;

import com.google.devtools.build.lib.worker.ProtoWorkerMessageProcessor;
import com.google.devtools.build.lib.worker.WorkRequestHandler;
import com.google.devtools.build.lib.worker.WorkRequestHandler.WorkRequestCallback;
import com.google.devtools.build.lib.worker.WorkRequestHandler.WorkRequestHandlerBuilder;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

/**
 * Compiles a Scala.js module incrementally and links it, as a Bazel action.
 *
 * <p>The action's state — the zinc analysis, the class and IR files it produced, the sources
 * extracted from source jars — lives in a directory beside the output, so an invocation after an
 * edit recompiles only what the edit touched and relinks from the IR already on disk. That
 * directory is the contract: the tool runs correctly as a fresh process every time. Run as a
 * persistent worker ({@code --persistent_worker}, the proto protocol), it additionally keeps the
 * compiler's class loaders and the linker's IR cache warm between requests.
 */
public final class ScalaJsDevMain {

  private static final String PERSISTENT_WORKER_FLAG = "--persistent_worker";

  private ScalaJsDevMain() {}

  public static void main(String[] args) throws IOException {
    if (Arrays.asList(args).contains(PERSISTENT_WORKER_FLAG)) {
      runAsWorker();
      return;
    }
    int exitCode;
    try (PrintWriter out = new PrintWriter(System.err, true)) {
      exitCode = run(Arguments.expand(args), out);
    }
    System.exit(exitCode);
  }

  /**
   * Runs one request.
   *
   * @param args the tool's arguments, with any {@code @file} already expanded
   * @param out where diagnostics for this request are written
   * @return the exit code: 0 on success, 1 on a compile or link failure, 2 on a usage error
   */
  public static int run(List<String> args, PrintWriter out) {
    try {
      ModuleRequest request = ModuleRequest.parse(args);
      return ModuleBuild.execute(request, out) ? 0 : 1;
    } catch (IllegalArgumentException e) {
      out.println(e.getMessage());
      return 2;
    } catch (RuntimeException | IOException e) {
      e.printStackTrace(out);
      return 1;
    }
  }

  private static void runAsWorker() throws IOException {
    WorkRequestHandler handler =
        new WorkRequestHandlerBuilder(
                new WorkRequestCallback((request, out) -> run(request.getArgumentsList(), out)),
                System.err,
                new ProtoWorkerMessageProcessor(System.in, System.out))
            .build();
    // The worker protocol owns stdout, and the processor above holds it. Anything the compiler
    // or the linker prints must reach stderr instead, or it corrupts the response stream.
    System.setOut(System.err);
    handler.processRequests();
  }
}
