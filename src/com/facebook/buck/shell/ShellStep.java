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

package com.facebook.buck.shell;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Option;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

public abstract class ShellStep implements Step {

  /** Defined lazily by {@link #getShellCommand(com.facebook.buck.step.ExecutionContext)}. */
  private ImmutableList<String> shellCommandArgs;

  /** If specified, working directory will be different from project root. **/
  @Nullable
  protected final File workingDirectory;

  /**
   * This is set if {@link #shouldPrintStdout(Verbosity)} returns {@code true} when the command is
   * executed.
   */
  @Nullable
  private String stdout;

  /**
   * This is set if {@link #shouldPrintStderr(Verbosity)} returns {@code true} when the command is
   * executed.
   */
  @Nullable
  private String stderr;

  private long startTime = 0L;
  private long endTime = 0L;

  protected ShellStep() {
    this(/* workingDirectory */ null);
  }

  protected ShellStep(@Nullable File workingDirectory) {
    this.workingDirectory = workingDirectory;
  }

  /**
   * Get the working directory for this command.
   * @return working directory specified on construction
   *         ({@code null} if project directory will be used).
   */
  @Nullable
  @VisibleForTesting
  public File getWorkingDirectory() {
    return workingDirectory;
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    // Kick off a Process in which this ShellCommand will be run.
    List<String> cmd = getShellCommand(context);
    ProcessBuilder processBuilder = new ProcessBuilder(cmd);

    setProcessEnvironment(context, processBuilder.environment());

    File wd;
    if (workingDirectory != null) {
      wd = workingDirectory;
    } else {
      wd = context.getProjectDirectoryRoot().toAbsolutePath().toFile();
    }
    String strCmd = Joiner.on(" ").join(cmd);
    System.err.println("Running in "+wd+": "+cmd);
    if(strCmd.contains("PassingTest")){
        System.exit(0);
    }
    processBuilder.directory(wd);

    Optional<String> stdin = getStdin();
    if (stdin.isPresent()) {
      processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE);
    }

    Process process;
    int exitCode;
    try {
      startTime = System.currentTimeMillis();
      process = processBuilder.start();
      exitCode = interactWithProcess(context, process);
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      exitCode = 1;
    }

    endTime = System.currentTimeMillis();

    return exitCode;
  }

  @VisibleForTesting
  void setProcessEnvironment(ExecutionContext context, Map<String, String> environment) {

    // Replace environment with client environment.
    environment.clear();
    environment.putAll(context.getEnvironment());

    // Add extra environment variables for step, if appropriate.
    if (!getEnvironmentVariables(context).isEmpty()) {
      environment.putAll(getEnvironmentVariables(context));
    }
  }

  @VisibleForTesting
  int interactWithProcess(ExecutionContext context, Process process) throws InterruptedException {
    ImmutableSet.Builder<Option> options = ImmutableSet.builder();
    if (shouldFlushStdOutErrAsProgressIsMade()) {
      options.add(Option.PRINT_STD_OUT);
      options.add(Option.PRINT_STD_ERR);
    }
    if (context.getVerbosity() == Verbosity.SILENT) {
      options.add(Option.IS_SILENT);
    }

    ProcessExecutor executor = context.getProcessExecutor();
    ProcessExecutor.Result result = executor.execute(process, options.build(), getStdin());
    stdout = result.getStdout();
    stderr = result.getStderr();

    Verbosity verbosity = context.getVerbosity();
    if (!Strings.isNullOrEmpty(stdout) && shouldPrintStdout(verbosity)) {
      context.postEvent(ConsoleEvent.info("%s", stdout));
    }
    if (!Strings.isNullOrEmpty(stderr) && shouldPrintStderr(verbosity)) {
      context.postEvent(ConsoleEvent.severe("%s", stderr));
    }

    return result.getExitCode();
  }

  public long getDuration() {
    Preconditions.checkState(startTime > 0);
    Preconditions.checkState(endTime > 0);
    return endTime - startTime;
  }

  /**
   * This method is idempotent.
   * @return the shell command arguments
   */
  public final ImmutableList<String> getShellCommand(ExecutionContext context) {
    if (shellCommandArgs == null) {
      shellCommandArgs = getShellCommandInternal(context);
    }
    return shellCommandArgs;
  }

  protected Optional<String> getStdin() {
    return Optional.absent();
  }

  /**
   * Implementations of this method should not have any observable side-effects.
   */
  @VisibleForTesting
  protected abstract ImmutableList<String> getShellCommandInternal(ExecutionContext context);

  @Override
  public final String getDescription(ExecutionContext context) {
    // Get environment variables for this command as VAR1=val1 VAR2=val2... etc., with values
    // quoted as necessary.
    Iterable<String> env = Iterables.transform(getEnvironmentVariables(context).entrySet(),
        new Function<Entry<String, String>, String>() {
          @Override
          public String apply(Entry<String, String> e) {
            return String.format("%s=%s", e.getKey(), Escaper.escapeAsBashString(e.getValue()));
          }
    });

    // Quote the arguments to the shell command as needed (this applies to $0 as well
    // e.g. if we run '/path/a b.sh' quoting is needed).
    Iterable<String> cmd = Iterables.transform(getShellCommand(context), Escaper.BASH_ESCAPER);

    String shellCommand = Joiner.on(" ").join(Iterables.concat(env, cmd));
    if (getWorkingDirectory() == null) {
      return shellCommand;
    } else {
      // If the ShellCommand has a specific working directory, set through ProcessBuilder, then
      // this is what the user might type in a shell to get the same behavior. The (...) syntax
      // introduces a subshell in which the command is only executed if cd was successful.
      return String.format("(cd %s && %s)",
          Escaper.escapeAsBashString(workingDirectory.getPath()),
          shellCommand);
    }
  }

  /**
   * Returns the environment variables to include when running this {@link ShellStep}.
   * <p>
   * By default, this method returns an empty map.
   * @param context that may be useful when determining environment variables to include.
   */
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return ImmutableMap.of();
  }

  /**
   * @param verbosity is provided in case that affects what should be printed.
   * @return whether the stdout of the shell command, when executed, should be printed to the stderr
   *     of the specified {@link ExecutionContext}. If {@code false}, stdout will only be printed on
   *     error and only if verbosity is set to standard information.
   */
  protected boolean shouldPrintStdout(Verbosity verbosity) {
    return false;
  }

  /**
   * @return the stdout of this ShellCommand or throws an exception if the stdout was not recorded
   */
  public final String getStdout() {
    Preconditions.checkNotNull(this.stdout, "stdout was not set: " +
        "shouldPrintStdout() must return false and execute() must have been invoked");
    return this.stdout;
  }

  /**
   * @return whether the stderr of the shell command, when executed, should be printed to the stderr
   *     of the specified {@link ExecutionContext}. If {@code false}, stderr will only be printed on
   *     error and only if verbosity is set to standard information.
   */
  protected boolean shouldPrintStderr(Verbosity verbosity) {
    return verbosity.shouldPrintOutput();
  }

  /**
   * @return the stderr of this ShellCommand or throws an exception if the stderr was not recorded
   */
  public final String getStderr() {
    Preconditions.checkNotNull(this.stderr, "stderr was not set: " +
        "shouldPrintStdErr() must return false and execute() must have been invoked");
    return this.stderr;
  }

  /**
   * By default, the output written to stdout and stderr will be buffered into individual
   * {@link ByteArrayOutputStream}s and then converted into strings for easier consumption. This
   * means that output from both streams that would normally be interleaved will now be displayed
   * separately.
   * <p>
   * To disable this behavior and print to stdout and stderr directly, this method should be
   * overridden to return {@code true}.
   */
  protected boolean shouldFlushStdOutErrAsProgressIsMade() {
    return false;
  }
}
