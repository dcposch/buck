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

package com.facebook.buck.android;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.List;

public class GenRDotTxtUtil {

  private GenRDotTxtUtil() {}

  /**
   * Creates a command that will run {@code aapt} for the purpose of generating {@code R.txt}.
   * @param resDirectories Directories of resource files. Will be specified with {@code -S} to
   *     {@code aapt}
   * @param genDirectoryPath Directory where {@code R.java} and potentially {@code R.txt} will be
   *     generated
   * @param libraryPackage Normally, {@code aapt} expects an {@code AndroidManifest.xml} so that it
   *     can extract the {@code package} attribute to determine the Java package of the generated
   *     {@code R.java} file. For this class, the client must specify the {@code package} directly
   *     rather than the path to {@code AndroidManifest.xml}. This precludes the need to keep a
   *     number of dummy {@code AndroidManifest.xml} files in the codebase.
   * @param isTempRDotJava If true, the values of the resource values in the generated
   *     {@code R.txt} will be meaningless.
   *     <p>
   *     If false, this command will produce an {@code R.txt} file with resource values designed to
   *     match those in an .apk that includes the resources.
   * @param dummyAndroidManifest Where the a dummy {@code AndroidManifest.xml} file can be written.
   */
  public static List<Step> createSteps(
    ImmutableList<Path> resDirectories,
    Path genDirectoryPath,
    final Supplier<String> libraryPackage,
    boolean isTempRDotJava,
    Path dummyAndroidManifest) {
    return ImmutableList.of(
            new MkdirStep(dummyAndroidManifest.getParent()),
            new WriteFileStep(
                new Supplier<String>() {
                  @Override
                  public String get() {
                    return String.format(
                        "<manifest xmlns:android='http://schemas.android.com/apk/res/android' " +
                            "package='%s' />",
                        libraryPackage.get());
                  }
                },
                dummyAndroidManifest),
            new GenRDotTxtStepInternal(
                resDirectories,
                genDirectoryPath,
                isTempRDotJava,
                dummyAndroidManifest));
  }

  private static class GenRDotTxtStepInternal extends ShellStep {
    private final ImmutableList<Path> resDirectories;
    private final Path genDirectoryPath;
    private final boolean isTempRDotJava;
    private final Path androidManifest;

    public GenRDotTxtStepInternal(
        ImmutableList<Path> resDirectories,
        Path genDirectoryPath,
        boolean isTempRDotJava,
        Path dummyAndroidManifest) {
      this.resDirectories = Preconditions.checkNotNull(resDirectories);
      this.androidManifest = Preconditions.checkNotNull(dummyAndroidManifest);
      this.genDirectoryPath = Preconditions.checkNotNull(genDirectoryPath);
      this.isTempRDotJava = isTempRDotJava;
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      AndroidPlatformTarget androidPlatformTarget = context.getAndroidPlatformTarget();

      builder.add(androidPlatformTarget.getAaptExecutable().toString());
      builder.add("package");

      // verbose flag, if appropriate.
      if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
        builder.add("-v");
      }

      // Add all of the res/ directories.
      for (Path res : resDirectories) {
        builder.add("-S").add(res.toString());
      }

      builder.add("--output-text-symbols").add(genDirectoryPath.toString());
      if (isTempRDotJava) {
        builder.add("--non-constant-id");
      }

      // aapt generates the R.txt file iff the "-J" flag is passed.
      builder.add("-J").add(genDirectoryPath.toString());

      // Add the remaining flags.
      builder.add("-M").add(context.getProjectFilesystem().resolve(androidManifest).toString());
      builder.add("--auto-add-overlay");
      builder.add("-I").add(androidPlatformTarget.getAndroidJar().toString());

      return builder.build();
    }

    @Override
    public String getShortName() {
      return String.format("aapt_package");
    }

    @Override
    protected boolean shouldPrintStderr(Verbosity verbosity) {
      // Print out errors about missing resource dependencies.
      return verbosity.shouldPrintStandardInformation();
    }
  }
}
