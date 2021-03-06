/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.util;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Executes a {@link Process} and blocks until it is finished.
 */
public class ProcessExecutor {

  /**
   * Options for {@link ProcessExecutor#execute(Process, Set, Optional)}.
   */
  public static enum Option {
    PRINT_STD_OUT,
    PRINT_STD_ERR,

    /**
     * If set, do not write output to stdout or stderr. However, if the process exits with a
     * non-zero exit code, then the stdout and stderr from the process will be presented to the user
     * to aid in debugging.
     */
    IS_SILENT,
  }

  private final PrintStream stdOutStream;
  private final PrintStream stdErrStream;
  private final Ansi ansi;

  /**
   * Creates a new {@link ProcessExecutor} with the specified parameters used for writing the output
   * of the process.
   */
  public ProcessExecutor(Console console) {
    Preconditions.checkNotNull(console);
    this.stdOutStream = console.getStdOut();
    this.stdErrStream = console.getStdErr();
    this.ansi = console.getAnsi();
  }

  /**
   * Convenience method for {@link #execute(Process, Set, Optional)}
   * with boolean values set to {@code false} and optional values set to absent.
   */
  public Result execute(Process process) throws InterruptedException {
    return execute(process,
        ImmutableSet.<Option>of(),
        /* stdin */ Optional.<String>absent());
  }

  /**
   * Executes the specified process.
   * <p>
   * If {@code options} contains {@link Option#PRINT_STD_OUT}, then the stdout of the process will
   * be written directly to the stdout passed to the constructor of this executor. Otherwise,
   * the stdout of the process will be made available via {@link Result#getStdout()}.
   * <p>
   * If {@code options} contains {@link Option#PRINT_STD_ERR}, then the stderr of the process will
   * be written directly to the stderr passed to the constructor of this executor. Otherwise,
   * the stderr of the process will be made available via {@link Result#getStderr()}.
   */
  public Result execute(
      Process process,
      Set<Option> options,
      Optional<String> stdin) throws InterruptedException {

    // Read stdout/stderr asynchronously while running a Process.
    // See http://stackoverflow.com/questions/882772/capturing-stdout-when-calling-runtime-exec
    boolean shouldPrintStdOut = options.contains(Option.PRINT_STD_OUT);
    @SuppressWarnings("resource")
    PrintStream stdOutToWriteTo = shouldPrintStdOut ?
        stdOutStream : new CapturingPrintStream();
    InputStreamConsumer stdOut = new InputStreamConsumer(
        process.getInputStream(),
        stdOutToWriteTo,
        ansi,
        /* flagOutputWrittenToStream */ !shouldPrintStdOut);

    boolean shouldPrintStdErr = options.contains(Option.PRINT_STD_ERR);
    @SuppressWarnings("resource")
    PrintStream stdErrToWriteTo = shouldPrintStdErr ?
        stdErrStream : new CapturingPrintStream();
    InputStreamConsumer stdErr = new InputStreamConsumer(
        process.getErrorStream(),
        stdErrToWriteTo,
        ansi,
        /* flagOutputWrittenToStream */ !shouldPrintStdErr);

    // Consume the streams so they do not deadlock.
    Thread stdOutConsumer = Threads.namedThread("ProcessExecutor (stdOut)", stdOut);
    stdOutConsumer.start();
    Thread stdErrConsumer = Threads.namedThread("ProcessExecutor (stdErr)", stdErr);
    stdErrConsumer.start();

    // Block until the Process completes.
    try {

      // If a stdin string was specific, then write that first.  This shouldn't cause
      // deadlocks, as the stdout/stderr consumers are running in separate threads.
      if (stdin.isPresent()) {
        try (OutputStreamWriter stdinWriter = new OutputStreamWriter(process.getOutputStream())) {
          stdinWriter.write(stdin.get());
        }
      }

      // Wait for the process and consumer threads to finish.
      process.waitFor();
      stdOutConsumer.join();
      stdErrConsumer.join();

    } catch (IOException e) {
      // Buck was killed while waiting for the consumers to finish or while writing stdin
      // to the process. This means either the user killed the process or a step failed
      // causing us to kill all other running steps. Neither of these is an exceptional
      // situation.
      return new Result(1, /* stdout */ null, /* stderr */ null);
    } finally {
      process.destroy();
      process.waitFor();
    }

    String stdoutText = getDataIfNotPrinted(stdOutToWriteTo, shouldPrintStdOut);
    String stderrText = getDataIfNotPrinted(stdErrToWriteTo, shouldPrintStdErr);

    // Report the exit code of the Process.
    int exitCode = process.exitValue();

    // If the command has failed and we're not being explicitly quiet, ensure everything gets
    // printed.
    if (exitCode != 0 && !options.contains(Option.IS_SILENT)) {
      if (!shouldPrintStdOut) {
        stdOutStream.print(stdoutText);
      }
      if (!shouldPrintStdErr) {
        stdErrStream.print(stderrText);
      }
    }

    return new Result(exitCode, stdoutText, stderrText);
  }

  @Nullable
  private static String getDataIfNotPrinted(PrintStream printStream, boolean shouldPrint) {
    if (!shouldPrint) {
      CapturingPrintStream capturingPrintStream = (CapturingPrintStream) printStream;
      return capturingPrintStream.getContentsAsString(Charsets.US_ASCII);
    } else {
      return null;
    }
  }

  /**
   * Values from the result of
   * {@link ProcessExecutor#execute(Process, Set, Optional)}.
   */
  public static class Result {
    private final int exitCode;
    @Nullable private final String stdout;
    @Nullable private final String stderr;

    public Result(int exitCode, @Nullable String stdOut, @Nullable String stderr) {
      this.exitCode = exitCode;
      this.stdout = stdOut;
      this.stderr = stderr;
    }

    public int getExitCode() {
      return exitCode;
    }

    @Nullable
    public String getStdout() {
      return stdout;
    }

    @Nullable
    public String getStderr() {
      return stderr;
    }
  }
}
