// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.android;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.rules.java.DeployArchiveBuilder;
import com.google.devtools.build.lib.rules.java.JavaConfiguration;
import com.google.devtools.build.lib.rules.java.JavaSemantics;
import com.google.devtools.build.lib.rules.java.JavaTargetAttributes;
import com.google.devtools.build.lib.rules.java.ProguardSpecProvider;
import javax.annotation.Nullable;

/** Common code for proguarding. */
public final class ProguardHelper {

  /**
   * Attribute for attaching proguard specs explicitly to a rule, if such an attribute is desired.
   */
  public static final String PROGUARD_SPECS = "proguard_specs";

  /** A class collecting Proguard output artifacts. */
  @Immutable
  public static final class ProguardOutput {
    @Nullable private final Artifact outputJar;
    @Nullable private final Artifact mapping;
    @Nullable private final Artifact protoMapping;
    @Nullable private final Artifact seeds;
    @Nullable private final Artifact usage;
    @Nullable private final Artifact constantStringObfuscatedMapping;
    @Nullable private final Artifact libraryJar;
    private final Artifact config;
    @Nullable private final Artifact startupProfileRewritten;
    @Nullable private final Artifact mergedBaselineProfileRewritten;

    public ProguardOutput(
        @Nullable Artifact outputJar,
        @Nullable Artifact mapping,
        @Nullable Artifact protoMapping,
        @Nullable Artifact seeds,
        @Nullable Artifact usage,
        @Nullable Artifact constantStringObfuscatedMapping,
        @Nullable Artifact libraryJar,
        Artifact config,
        @Nullable Artifact startupProfileRewritten,
        @Nullable Artifact mergedBaselineProfileRewritten) {
      this.outputJar = outputJar;
      this.mapping = mapping;
      this.protoMapping = protoMapping;
      this.seeds = seeds;
      this.usage = usage;
      this.constantStringObfuscatedMapping = constantStringObfuscatedMapping;
      this.libraryJar = libraryJar;
      this.config = config;
      this.startupProfileRewritten = startupProfileRewritten;
      this.mergedBaselineProfileRewritten = mergedBaselineProfileRewritten;
    }

    public static ProguardOutput createEmpty(Artifact outputJar) {
      return new ProguardOutput(outputJar, null, null, null, null, null, null, null, null, null);
    }

    @Nullable
    public Artifact getOutputJar() {
      return outputJar;
    }

    public boolean hasMapping() {
      return mapping != null;
    }

    @Nullable
    public Artifact getMapping() {
      return mapping;
    }

    @Nullable
    public Artifact getProtoMapping() {
      return protoMapping;
    }

    @Nullable
    public Artifact getConstantStringObfuscatedMapping() {
      return constantStringObfuscatedMapping;
    }

    @Nullable
    public Artifact getSeeds() {
      return seeds;
    }

    @Nullable
    public Artifact getUsage() {
      return usage;
    }

    @Nullable
    public Artifact getLibraryJar() {
      return libraryJar;
    }

    public Artifact getConfig() {
      return config;
    }

    public Artifact getMergedBaselineProfileRewritten() {
      return mergedBaselineProfileRewritten;
    }

    public Artifact getStartupProfileRewritten() {
      return startupProfileRewritten;
    }

    /** Adds the output artifacts to the given set builder. */
    public void addAllToSet(NestedSetBuilder<Artifact> filesBuilder) {
      addAllToSet(filesBuilder, null);
    }

    /**
     * Adds the output artifacts to the given set builder. If the progaurd map was updated then add
     * the updated map instead of the original proguard output map
     */
    public void addAllToSet(NestedSetBuilder<Artifact> filesBuilder, Artifact finalProguardMap) {
      if (outputJar != null) {
        filesBuilder.add(outputJar);
      }
      if (protoMapping != null) {
        filesBuilder.add(protoMapping);
      }
      if (constantStringObfuscatedMapping != null) {
        filesBuilder.add(constantStringObfuscatedMapping);
      }
      if (seeds != null) {
        filesBuilder.add(seeds);
      }
      if (usage != null) {
        filesBuilder.add(usage);
      }
      if (config != null) {
        filesBuilder.add(config);
      }
      if (finalProguardMap != null) {
        filesBuilder.add(finalProguardMap);
      } else if (mapping != null) {
        filesBuilder.add(mapping);
      }
    }
  }

  /**
   * Retrieves the full set of proguard specs that should be applied to this binary, including the
   * specs passed in, if Proguard should run on the given rule. {@link #createProguardAction} relies
   * on this method returning an empty list if the given rule doesn't declare specs in
   * --java_optimization_mode=legacy.
   *
   * <p>If there are no proguard_specs on this rule, an empty list will be returned, regardless of
   * any given specs or specs from dependencies. {@link
   * com.google.devtools.build.lib.rules.android.AndroidBinary#createAndroidBinary} relies on that
   * behavior.
   */
  public static ImmutableList<Artifact> collectTransitiveProguardSpecs(
      RuleContext ruleContext, Iterable<Artifact> specsToInclude) {
    return collectTransitiveProguardSpecs(
        ruleContext,
        specsToInclude,
        ruleContext.attributes().has(PROGUARD_SPECS, BuildType.LABEL_LIST)
            ? ruleContext.getPrerequisiteArtifacts(PROGUARD_SPECS).list()
            : ImmutableList.<Artifact>of(),
        ruleContext.getPrerequisites("deps", ProguardSpecProvider.PROVIDER));
  }

  /**
   * Retrieves the full set of proguard specs that should be applied to this binary, including the
   * specs passed in, if Proguard should run on the given rule.
   *
   * <p>Unlike {@link #collectTransitiveProguardSpecs(RuleContext, Iterable)}, this method requires
   * values to be passed in explicitly, and does not extract them from rule attributes.
   *
   * <p>If there are no proguard_specs on this rule, an empty list will be returned, regardless of
   * any given specs or specs from dependencies. {@link
   * com.google.devtools.build.lib.rules.android.AndroidBinary#createAndroidBinary} relies on that
   * behavior.
   */
  public static ImmutableList<Artifact> collectTransitiveProguardSpecs(
      RuleContext context,
      Iterable<Artifact> specsToInclude,
      ImmutableList<Artifact> localProguardSpecs,
      Iterable<ProguardSpecProvider> proguardDeps) {
    return collectTransitiveProguardSpecs(
        context.getLabel(), context, specsToInclude, localProguardSpecs, proguardDeps);
  }
  /**
   * Retrieves the full set of proguard specs that should be applied to this binary, including the
   * specs passed in, if Proguard should run on the given rule.
   *
   * <p>Unlike {@link #collectTransitiveProguardSpecs(RuleContext, Iterable)}, this method requires
   * values to be passed in explicitly, and does not extract them from rule attributes.
   *
   * <p>If there are no proguard_specs on this rule, an empty list will be returned, regardless of
   * any given specs or specs from dependencies. {@link
   * com.google.devtools.build.lib.rules.android.AndroidBinary#createAndroidBinary} relies on that
   * behavior.
   */
  public static ImmutableList<Artifact> collectTransitiveProguardSpecs(
      Label label,
      ActionConstructionContext context,
      Iterable<Artifact> specsToInclude,
      ImmutableList<Artifact> localProguardSpecs,
      Iterable<ProguardSpecProvider> proguardDeps) {
    if (localProguardSpecs.isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableSortedSet.Builder<Artifact> builder =
        ImmutableSortedSet.orderedBy(Artifact.EXEC_PATH_COMPARATOR)
            .addAll(localProguardSpecs)
            .addAll(specsToInclude);
    for (ProguardSpecProvider dep : proguardDeps) {
      builder.addAll(dep.getTransitiveProguardSpecs().toList());
    }

    return builder.build().asList();
  }

  /** @return true if proguard_generate_mapping is specified. */
  public static final boolean genProguardMapping(AttributeMap rule) {
    return rule.has("proguard_generate_mapping", Type.BOOLEAN)
        && rule.get("proguard_generate_mapping", Type.BOOLEAN);
  }

  public static final boolean genObfuscatedConstantStringMap(AttributeMap rule) {
    return rule.has("proguard_generate_obfuscated_constant_string_mapping", Type.BOOLEAN)
        && rule.get("proguard_generate_obfuscated_constant_string_mapping", Type.BOOLEAN);
  }

  public static ProguardOutput getProguardOutputs(
      Artifact outputJar,
      @Nullable Artifact proguardSeeds,
      @Nullable Artifact proguardUsage,
      RuleContext ruleContext,
      JavaSemantics semantics,
      @Nullable Artifact proguardOutputMap,
      @Nullable Artifact libraryJar,
      @Nullable Artifact startupProfileRewritten,
      @Nullable Artifact mergedBaselineProfileRewritten)
      throws InterruptedException {
    boolean mappingRequested = genProguardMapping(ruleContext.attributes());

    Artifact proguardOutputProtoMap = null;
    Artifact proguardConstantStringMap = null;

    if (mappingRequested) {
      // TODO(bazel-team): if rex is enabled, the proguard map will change and then will no
      // longer correspond to the proto map
      proguardOutputProtoMap = semantics.getProtoMapping(ruleContext);
    }

    if (genObfuscatedConstantStringMap(ruleContext.attributes())) {
      proguardConstantStringMap = semantics.getObfuscatedConstantStringMap(ruleContext);
    }

    Artifact proguardConfigOutput =
        ruleContext.getImplicitOutputArtifact(JavaSemantics.JAVA_BINARY_PROGUARD_CONFIG);

    return new ProguardOutput(
        outputJar,
        proguardOutputMap,
        proguardOutputProtoMap,
        proguardSeeds,
        proguardUsage,
        proguardConstantStringMap,
        libraryJar,
        proguardConfigOutput,
        startupProfileRewritten,
        mergedBaselineProfileRewritten);
  }

  /**
   * Creates an action to run Proguard over the given {@code programJar} with various other given
   * inputs to produce {@code proguardOutputJar}. If requested explicitly, or implicitly with
   * --java_optimization_mode, the action also produces a mapping file (which shows what methods and
   * classes in the output Jar correspond to which methods and classes in the input). The "pair"
   * returned by this method indicates whether a mapping is being produced.
   *
   * <p>See the Proguard manual for the meaning of the various artifacts in play.
   *
   * @param proguard Proguard executable to use
   * @param proguardSpecs Proguard specification files to pass to Proguard
   * @param proguardMapping optional mapping file for Proguard to apply
   * @param proguardDictionary Optional dictionary file for Proguard to apply
   * @param libraryJars any other Jar files that the {@code programJar} will run against
   * @param optimizationPasses if not null specifies to break proguard up into multiple passes with
   *     the given number of optimization passes.
   * @param proguardOutputMap mapping generated by Proguard if requested. could be null.
   * @param startupProfileIn Profiles provided by the startup_profiles attribute. Their existence
   *     enables feedback directed optimization in optimization tools. These are rewritten by the
   *     optimizer.
   * @param baselineProfileIn Profile to pass the optimizer to rewrite through optimization.
   * @param baselineProfileDir Directory to write baseline profile artifacts if baseline profile is
   *     provided.
   */
  public static ProguardOutput createOptimizationActions(
      RuleContext ruleContext,
      FilesToRunProvider proguard,
      Artifact programJar,
      ImmutableList<Artifact> proguardSpecs,
      @Nullable Artifact proguardSeeds,
      @Nullable Artifact proguardUsage,
      @Nullable Artifact proguardMapping,
      @Nullable Artifact proguardDictionary,
      NestedSet<Artifact> libraryJars,
      Artifact proguardOutputJar,
      JavaSemantics semantics,
      @Nullable Integer optimizationPasses,
      @Nullable Artifact proguardOutputMap,
      @Nullable Artifact startupProfileIn,
      @Nullable Artifact baselineProfileIn,
      String baselineProfileDir)
      throws InterruptedException {
    Preconditions.checkArgument(!proguardSpecs.isEmpty());
    Artifact libraryJar = null;

    if (!libraryJars.isEmpty() && !libraryJars.isSingleton()) {
      JavaTargetAttributes attributes = new JavaTargetAttributes.Builder(semantics).build();
      Artifact combinedLibraryJar =
          getProguardTempArtifact(ruleContext, "combined_library_jars.jar");
      new DeployArchiveBuilder(semantics, ruleContext)
          .setOutputJar(combinedLibraryJar)
          .setAttributes(attributes)
          .addRuntimeJars(libraryJars)
          .build();
      libraryJars = NestedSetBuilder.create(Order.STABLE_ORDER, combinedLibraryJar);
      libraryJar = combinedLibraryJar;
    } else if (libraryJars.isSingleton()) {
      libraryJar = libraryJars.getSingleton();
    }

    boolean filterLibraryJarWithProgramJar =
        ruleContext.getFragment(AndroidConfiguration.class).filterLibraryJarWithProgramJar();

    if (filterLibraryJarWithProgramJar) {
      Preconditions.checkState(libraryJars.isSingleton());
      Artifact singletonLibraryJar = libraryJars.getSingleton();

      Artifact filteredLibraryJar =
          getProguardTempArtifact(ruleContext, "combined_library_jars_filtered.jar");

      new ZipFilterBuilder(ruleContext)
          .setInputZip(singletonLibraryJar)
          .setOutputZip(filteredLibraryJar)
          .addFilterZips(ImmutableList.of(programJar))
          .setCheckHashMismatchMode(ZipFilterBuilder.CheckHashMismatchMode.NONE)
          .build();

      libraryJars = NestedSetBuilder.create(Order.STABLE_ORDER, filteredLibraryJar);
      libraryJar = filteredLibraryJar;
    }
    Artifact startupProfileRewritten =
        startupProfileIn == null
            ? null
            : ruleContext.getBinArtifact(baselineProfileDir + "rewritten-startup-prof.txt");
    Artifact baselineProfileRewritten =
        baselineProfileIn == null && startupProfileIn == null
            ? null
            : ruleContext.getBinArtifact(baselineProfileDir + "rewritten-merged-prof.txt");
    ProguardOutput output =
        getProguardOutputs(
            proguardOutputJar,
            proguardSeeds,
            proguardUsage,
            ruleContext,
            semantics,
            proguardOutputMap,
            libraryJar,
            startupProfileRewritten,
            baselineProfileRewritten);

    JavaConfiguration javaConfiguration =
        ruleContext.getConfiguration().getFragment(JavaConfiguration.class);
    JavaConfiguration.NamedLabel optimizer = javaConfiguration.getBytecodeOptimizer();
    String mnemonic = optimizer.name();
    if (optimizationPasses == null) {
      // Run the optimizer as a single step.
      SpawnAction.Builder proguardAction = new SpawnAction.Builder();
      CustomCommandLine.Builder commandLine = CustomCommandLine.builder();
      defaultAction(
          proguardAction,
          commandLine,
          proguard,
          programJar,
          proguardSpecs,
          proguardMapping,
          proguardDictionary,
          libraryJars,
          output.getOutputJar(),
          output.getMapping(),
          output.getProtoMapping(),
          output.getSeeds(),
          output.getUsage(),
          output.getConstantStringObfuscatedMapping(),
          output.getConfig(),
          startupProfileIn,
          startupProfileRewritten,
          baselineProfileIn,
          baselineProfileRewritten,
          mnemonic);
      proguardAction
          .setProgressMessage("Trimming binary with %s: %s", mnemonic, ruleContext.getLabel())
          .addOutput(proguardOutputJar);
      proguardAction.addCommandLine(commandLine.build());
      ruleContext.registerAction(proguardAction.build(ruleContext));
    } else {
      Optional<Label> optimizerTarget = optimizer.label();
      FilesToRunProvider executable = null;
      if (optimizerTarget.isPresent()) {
        TransitiveInfoCollection optimizerDep = ruleContext.getPrerequisite(":bytecode_optimizer");
        if (optimizerDep.getLabel().equals(optimizerTarget.get())) {
          executable = optimizerDep.getProvider(FilesToRunProvider.class);
        }
      } else {
        checkState("Proguard".equals(mnemonic), "Need label to run %s", mnemonic);
        executable = proguard;
      }
      checkNotNull(executable, "couldn't find optimizer %s", optimizer);

      // Optimization passes have been specified, so run proguard in multiple phases.
      Artifact lastStageOutput =
          getProguardTempArtifact(ruleContext, "proguard_preoptimization.jar");
      SpawnAction.Builder initialAction = new SpawnAction.Builder();
      CustomCommandLine.Builder initialCommandLine = CustomCommandLine.builder();
      defaultAction(
          initialAction,
          initialCommandLine,
          proguard,
          programJar,
          proguardSpecs,
          proguardMapping,
          proguardDictionary,
          libraryJars,
          output.getOutputJar(),
          /* proguardOutputMap */ null,
          /* proguardOutputProtoMap */ null,
          output.getSeeds(), // ProGuard only prints seeds during INITIAL and NORMAL runtypes.
          /* proguardUsage */ null,
          /* constantStringObfuscatedMapping */ null,
          /* proguardConfigOutput */ null,
          startupProfileIn,
          /* startupProfileRewritten */ null,
          baselineProfileIn,
          /* baselineProfileRewritten */ null,
          mnemonic);
      initialAction
          .setProgressMessage("Trimming binary with %s: Verification/Shrinking Pass", mnemonic)
          .addOutput(lastStageOutput)
          .setMnemonic(mnemonic);
      initialCommandLine.add("-runtype INITIAL").addExecPath("-nextstageoutput", lastStageOutput);
      initialAction.addCommandLine(initialCommandLine.build());
      ruleContext.registerAction(initialAction.build(ruleContext));
      for (int i = 1; i <= optimizationPasses; i++) {
        if (javaConfiguration.splitBytecodeOptimizationPass()
            // TODO(b/237004872) Remove this comparison when possible.
            && javaConfiguration.bytecodeOptimizationPassActions() < 2) {
          // Use legacy names in this case.
          lastStageOutput =
              createSingleOptimizationAction(
                  "_INITIAL",
                  ruleContext,
                  mnemonic,
                  i,
                  executable,
                  programJar,
                  proguardSpecs,
                  proguardMapping,
                  proguardDictionary,
                  libraryJars,
                  output,
                  lastStageOutput);
          lastStageOutput =
              createSingleOptimizationAction(
                  "_FINAL",
                  ruleContext,
                  mnemonic,
                  i,
                  executable,
                  programJar,
                  proguardSpecs,
                  proguardMapping,
                  proguardDictionary,
                  libraryJars,
                  output,
                  lastStageOutput);
        } else {
          int totalActions = javaConfiguration.bytecodeOptimizationPassActions();
          for (int action = 1; action <= totalActions; action++) {
            lastStageOutput =
                createSingleOptimizationAction(
                    "_ACTION_" + action + "_OF_" + totalActions,
                    ruleContext,
                    mnemonic,
                    i,
                    executable,
                    programJar,
                    proguardSpecs,
                    proguardMapping,
                    proguardDictionary,
                    libraryJars,
                    output,
                    lastStageOutput);
          }
        }
      }

      SpawnAction.Builder finalAction = new SpawnAction.Builder();
      CustomCommandLine.Builder finalCommandLine = CustomCommandLine.builder();
      defaultAction(
          finalAction,
          finalCommandLine,
          proguard,
          programJar,
          proguardSpecs,
          proguardMapping,
          proguardDictionary,
          libraryJars,
          output.getOutputJar(),
          output.getMapping(),
          output.getProtoMapping(),
          /* proguardSeeds */ null, // runtype FINAL does not produce seeds.
          output.getUsage(),
          output.getConstantStringObfuscatedMapping(),
          output.getConfig(),
          /* startupProfileInput */ null,
          startupProfileRewritten,
          /* baselineProfileInput */ null,
          baselineProfileRewritten,
          mnemonic);
      finalAction
          .setProgressMessage(
              "Trimming binary with %s: Obfuscation and Final Output Pass", mnemonic)
          .addInput(lastStageOutput)
          .addOutput(proguardOutputJar);
      finalCommandLine.add("-runtype FINAL").addExecPath("-laststageoutput", lastStageOutput);
      finalAction.addCommandLine(finalCommandLine.build());
      ruleContext.registerAction(finalAction.build(ruleContext));
    }

    return output;
  }

  /**
   * Creates a single build stage where the run type of the optimizer is "OPTMIZATION*" where "*" is
   * the value of the provided runtypeSuffix. For example, if runtypeSuffix is "_INITIAL", the run
   * type will be "OPTIMIZATION_INITIAL".
   *
   * @return The output artifact from the last "OPTIMIZATION*" stage.
   */
  private static Artifact createSingleOptimizationAction(
      String runtypeSuffix,
      RuleContext ruleContext,
      String mnemonic,
      int optimizationPassNum,
      FilesToRunProvider executable,
      Artifact programJar,
      ImmutableList<Artifact> proguardSpecs,
      @Nullable Artifact proguardMapping,
      @Nullable Artifact proguardDictionary,
      NestedSet<Artifact> libraryJars,
      ProguardOutput output,
      Artifact lastStageOutput) {
    Artifact optimizationOutput =
        getProguardTempArtifact(
            ruleContext,
            mnemonic
                + "_optimization"
                + Ascii.toLowerCase(runtypeSuffix)
                + "_"
                + optimizationPassNum
                + ".jar");
    SpawnAction.Builder optimizationAction = new SpawnAction.Builder();
    CustomCommandLine.Builder optimizationCommandLine = CustomCommandLine.builder();
    defaultAction(
        optimizationAction,
        optimizationCommandLine,
        executable,
        programJar,
        proguardSpecs,
        proguardMapping,
        proguardDictionary,
        libraryJars,
        output.getOutputJar(),
        /* proguardOutputMap */ null,
        /* proguardOutputProtoMap */ null,
        /* proguardSeeds */ null,
        /* proguardUsage */ null,
        /* constantStringObfuscatedMapping */ null,
        /* proguardConfigOutput */ null,
        /* startupProfileInput */ null,
        /* startupProfileRewritten */ null,
        /* baselineProfileInput */ null,
        /* baselineProfileRewritten */ null,
        mnemonic);
    optimizationAction
        .setProgressMessage(
            "Trimming binary with %s: Optimization%s Pass %d",
            mnemonic, Ascii.toLowerCase(runtypeSuffix), optimizationPassNum)
        .setMnemonic(mnemonic)
        .addInput(lastStageOutput)
        .addOutput(optimizationOutput);
    optimizationCommandLine
        .addDynamicString("-runtype OPTIMIZATION" + runtypeSuffix)
        .addExecPath("-laststageoutput", lastStageOutput)
        .addExecPath("-nextstageoutput", optimizationOutput);
    optimizationAction.addCommandLine(optimizationCommandLine.build());
    ruleContext.registerAction(optimizationAction.build(ruleContext));
    return optimizationOutput;
  }

  private static void defaultAction(
      SpawnAction.Builder builder,
      CustomCommandLine.Builder commandLine,
      FilesToRunProvider executable,
      Artifact programJar,
      ImmutableList<Artifact> proguardSpecs,
      @Nullable Artifact proguardMapping,
      @Nullable Artifact proguardDictionary,
      NestedSet<Artifact> libraryJars,
      Artifact proguardOutputJar,
      @Nullable Artifact proguardOutputMap,
      @Nullable Artifact proguardOutputProtoMap,
      @Nullable Artifact proguardSeeds,
      @Nullable Artifact proguardUsage,
      @Nullable Artifact constantStringObfuscatedMapping,
      @Nullable Artifact proguardConfigOutput,
      @Nullable Artifact startupProfileInput,
      @Nullable Artifact startupProfileRewritten,
      @Nullable Artifact baselineProfileInput,
      @Nullable Artifact baselineProfileRewritten,
      String mnemonic) {

    builder
        .addTransitiveInputs(libraryJars)
        .addInputs(proguardSpecs)
        .setExecutable(executable)
        .setMnemonic(mnemonic)
        .addInput(programJar);

    commandLine
        .add("-forceprocessing")
        .addExecPath("-injars", programJar)
        // This is handled by the build system there is no need for proguard to check if things are
        // up to date.
        .add("-outjars")
        // Don't register the output jar as an output of the action, because multiple proguard
        // actions will be created for optimization runs which will overwrite the jar, and only
        // the final proguard action will declare the output jar as an output.
        .addExecPath(proguardOutputJar);

    for (Artifact libraryJar : libraryJars.toList()) {
      commandLine.addExecPath("-libraryjars", libraryJar);
    }

    if (proguardMapping != null) {
      builder.addInput(proguardMapping);
      commandLine.addExecPath("-applymapping", proguardMapping);
    }

    if (proguardDictionary != null) {
      builder.addInput(proguardDictionary);
      commandLine
          .addExecPath("-obfuscationdictionary", proguardDictionary)
          .addExecPath("-classobfuscationdictionary", proguardDictionary)
          .addExecPath("-packageobfuscationdictionary", proguardDictionary);
    }

    for (Artifact proguardSpec : proguardSpecs) {
      commandLine.addPrefixedExecPath("@", proguardSpec);
    }

    if (proguardOutputMap != null) {
      builder.addOutput(proguardOutputMap);
      commandLine.addExecPath("-printmapping", proguardOutputMap);
    }

    if (proguardOutputProtoMap != null) {
      builder.addOutput(proguardOutputProtoMap);
      commandLine.addExecPath("-protomapping", proguardOutputProtoMap);
    }

    if (constantStringObfuscatedMapping != null) {
      builder.addOutput(constantStringObfuscatedMapping);
      commandLine.addExecPath(
          "-obfuscatedconstantstringoutputfile", constantStringObfuscatedMapping);
    }

    if (proguardSeeds != null) {
      builder.addOutput(proguardSeeds);
      commandLine.addExecPath("-printseeds", proguardSeeds);
    }

    if (proguardUsage != null) {
      builder.addOutput(proguardUsage);
      commandLine.addExecPath("-printusage", proguardUsage);
    }

    if (proguardConfigOutput != null) {
      builder.addOutput(proguardConfigOutput);
      commandLine.addExecPath("-printconfiguration", proguardConfigOutput);
    }
    if (startupProfileInput != null) {
      builder.addInput(startupProfileInput);
      commandLine.addExecPath("-startupprofile", startupProfileInput);
    }
    if (startupProfileRewritten != null) {
      builder.addOutput(startupProfileRewritten);
      commandLine.addExecPath("-printstartupprofile", startupProfileRewritten);
    }
    if (baselineProfileInput != null) {
      builder.addInput(baselineProfileInput);
      commandLine.addExecPath("-baselineprofile", baselineProfileInput);
    }
    if (baselineProfileRewritten != null) {
      builder.addOutput(baselineProfileRewritten);
      commandLine.addExecPath("-printbaselineprofile", baselineProfileRewritten);
    }
  }

  /** Returns an intermediate artifact used to run Proguard. */
  public static Artifact getProguardTempArtifact(RuleContext ruleContext, String name) {
    return getProguardTempArtifact(ruleContext.getLabel(), ruleContext, "legacy", name);
  }

  /** Returns an intermediate artifact used to run Proguard. */
  public static Artifact getProguardTempArtifact(
      Label label, ActionConstructionContext context, String prefix, String name) {
    // TODO(bazel-team): Remove the redundant inclusion of the rule name, as getUniqueDirectory
    // includes the rulename as well.
    return context.getUniqueDirectoryArtifact(
        "proguard", Joiner.on("_").join(prefix, label.getName(), name));
  }

  public static Artifact getProguardConfigArtifact(RuleContext ruleContext, String prefix) {
    return getProguardConfigArtifact(ruleContext.getLabel(), ruleContext, prefix);
  }

  public static Artifact getProguardConfigArtifact(
      Label label, ActionConstructionContext context, String prefix) {
    return getProguardTempArtifact(label, context, prefix, "proguard.cfg");
  }
}
