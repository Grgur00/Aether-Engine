# Root Build Architecture and Actions

[Function index](FUNCTION-INDEX.md) | [Gradle plugin functions](GRADLE-PLUGIN-FUNCTIONS.md) | [Build utilities](BUILD-UTILITY-FUNCTIONS.md)

Sources: [settings.gradle.kts](../../settings.gradle.kts),
[build.gradle.kts](../../build.gradle.kts), [gradle.properties](../../gradle.properties),
[wrapper properties](../../gradle/wrapper/gradle-wrapper.properties),
[build-logic settings](../../build-logic/settings.gradle.kts) and
[build-logic build](../../build-logic/build.gradle.kts).
This reference explains the root configuration/action blocks and the **one
explicit local Kotlin function**, rather than treating Gradle lambdas as named
Java methods. Module-specific task actions are a separate layer, inventoried in
the [module build topology reference](MODULE-BUILD-ARCHITECTURE.md).

## Configuration Layers

```text
wrapper distribution + checksum -> Gradle invocation
settings -> plugin repositories + toolchain resolver + project inclusion
    -> included build-logic -> shared precompiled convention plugins
root project -> common coordinates + publishing aggregate + schema aggregates
module build -> convention application + dependencies + specialized tasks
task graph -> selected task execution, not every registered task
```

Settings name the root `aether-engine`, include `build-logic` and explicitly include
49 module projects plus two examples. Project inclusion is build topology, not a
runtime call/dependency graph. Changing the inclusion list does not automatically
publish the added module or make the root verification placeholders run its tests.

Plugin resolution uses Gradle Plugin Portal and Maven Central. Foojay resolver
convention version 1.0.0 supports toolchain resolution. Dependency resolution uses
Maven Central with `FAIL_ON_PROJECT_REPOS`: project-added repositories are not an
approved alternate dependency source. The included build has its own settings
and repository configuration; it is not a subproject inheriting all root actions.

## Root Task and Configuration Actions

| Action | Behavior | Limits |
| --- | --- | --- |
| `plugins` | Applies `base` and JReleaser 1.25.0. | Root is not itself a Java application/library. Plugin application is not release execution. |
| `allprojects` | Sets group `io.github.grgur00` and version from `aetherVersion`, default `0.1.0-SNAPSHOT`. | Included build is separate. Coordinates alone do not create publications. |
| `stageMavenCentral` | Depends on staging-publication tasks for the explicit 15-module public set and `verifyReleaseVersion`. | Does not include all 49 modules. Does not itself run the Python Maven checker, sign or upload. Dependency ordering is not a guarantee every build action waits behind version validation. |
| `verifyReleaseVersion` | Execution-time action rejects uppercase `-SNAPSHOT` suffix and requires three numeric dot-separated components with optional alphanumeric/dot/hyphen prerelease suffix. | Regex syntax check, not SemVer precedence or remote uniqueness validation. Default version fails a release stage. |
| `jreleaser` | Uses `jreleaser.yml`, enables Git root search and disables automatic assemble dependency. | Selected JReleaser tasks/configuration determine actual release effects; root configuration alone is not publication. |
| `integrationTest` | Registers verification aggregate depending on included `build-logic :check`. | Description says all module integration tests, but this root block does not wire module integration suites. Do not infer coverage from description. |
| `crashTestSmoke` | Registers named verification task with fast-fixture description. | No dependencies or action in this root source; success is not crash-test evidence. |
| `crashTest` | Registers named verification task with complete-suite description. | No dependencies or action in this root source. Actual crash drivers/tests must be invoked separately. |
| `aetherSchemaInit` | Root aggregate receives each Java project's initial proposal task. | Proposal generation, not committed-lock mutation. |
| `aetherSchemaUpdate` | Root aggregate receives each Java project's update proposal task. | Same proposal mechanism as init; not automatic acceptance. |
| `aetherSchemaAccept` | Root aggregate receives each Java project's accept task, which depends on update. | Explicit source-changing operation; review resulting schema locks. |
| `aetherSchemaCheck` | Root verification aggregate depends on each Java project's `compileJava`. | No independent scanner or project-scoped check task registered by this root block. Processor verification happens through compilation where configured. |

The 15 published modules are API, memory, format, IO, memtable, WAL, SSTable,
LSM, engine, codec annotations, codec, codec processor, embedded typed adapter,
Gradle plugin and BOM. The staged Maven checker independently carries its own
expected set; source agreement should be audited when publication scope changes.

## Per-Java-Project Schema Wiring

The `subprojects` action waits for the Java plugin through `plugins.withId`.
It then resolves main source set and annotationProcessor configuration, uses
project-local `aether-schemas` and build-local `aether-schema/proposal`, and
registers proposal/accept tasks. This wiring is distinct from the published
consumer `AetherPlugin`, although the workflows are similar.

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `proposalTask(name, descriptionText)` | Registers a JavaCompile task over main Java sources and compile classpath, borrows compileJava's compiler provider and annotation processor path, directs class/generated outputs under build/aether-schema, adds `-proc:only`, PROPOSE mode and absolute schema/proposal paths, declares optional schema input and proposal output. | Local closure in each Java subproject. Init/update share output locations and implementation. Provider wiring is not a dependency on finishing ordinary compileJava. No cleanup of stale proposals or atomic source acceptance. No separately explicit preview/release setting here; applicable shared conventions configure JavaCompile tasks. |

| Project Action | Behavior | Limits |
| --- | --- | --- |
| `projectInit` / `projectUpdate` | Call proposalTask with corresponding name/description. | Neither adds a distinct compatibility policy outside processor behavior. |
| `projectAccept doLast` | Depends on update; if proposal `index.json` exists, creates schema directory and copies whole proposal tree into it. | Silently skips absent index. Copy is not atomic replacement, stale destination removal or independent inventory validation. |
| `schema*.configure` | Wires root init/update/accept aggregates to project tasks and root check to compileJava. | Aggregation does not inspect task output content itself. |
| `afterEvaluate` | If annotationProcessor has declared dependencies, adds optional schema-directory input and processor directory argument to compileJava. | Checks declared dependency collection, not processor discovery/transitive behavior. Up-to-date compile may skip execution; this does not force a fresh schema scan. |

## Toolchain and Execution Defaults

Wrapper selects Gradle 9.6.1 all-distribution with SHA-256 checksum, URL validation
and 10000-ms network timeout. These are local configuration facts, not a claim
that this version is latest. Root properties enable parallel execution/build
caching, disable configuration cache, show all warnings and give Gradle JVM a
2-GiB maximum heap with UTF-8 encoding. Gradle JVM memory is not Java daemon
storage-cache or GPU memory configuration.

Build-logic applies Kotlin DSL, selects Java/Kotlin toolchain 21, and puts output
under `.build/{gradleVersion}` to separate generated accessors from concurrent
different-Gradle-version IDE builds. It has Plugin Portal/Maven Central
repositories. Shared Java conventions and consumer plugin configuration are
documented in the Gradle plugin reference; they supply preview/compiler/test
settings that the root task names do not establish on their own.

## Training-Cache Module Actions

Source: [training-cache build](../../modules/aether-training-cache/build.gradle.kts).
This module applies `aether.java-library`, with implementation dependencies on
API, configuration, engine and SSTable; reliability is a test dependency.
These build dependencies do not make every engine type a supported training-cache
API. The task actions below are separate from the root aggregates above.

| Task | Action and Inputs | Outputs and Boundary |
| --- | --- | --- |
| `paperRuntimeClasspath` | Depends on `classes` and runtime-classpath build dependencies. Its `doLast` exports the main runtime classpath, hashes selected source files and inventories resolved runtime entries. | Writes `paper-runtime-classpath.txt` and `paper-runtime-build.json` under this module's build directory. Not a test, signed attestation or frozen campaign approval. |
| `trainingCacheBenchmark` | Runs `TrainingCacheBenchmark` on main runtime classpath. Ordered arguments: `benchmarkDir` default `build/training-cache-benchmark`, `samples` 1000, `payloadBytes` 256, `output` `build/training-cache-benchmark.json`. | Executes a benchmark, not model training or the paired H2 campaign. Storage/report paths are application arguments. |
| `trainingCacheCrashCampaign` | Runs `TrainingCacheCrashCampaign`. Ordered arguments: `campaignDir` default `build/training-cache-crashes`, `trials` 1000. | Starts forced-process recovery trials. Registration alone performs no recovery check; this is distinct from the empty root `crashTest` task. |
| `trainingCacheDaemon` | Runs `TrainingCacheDaemon`. Ordered arguments: `cacheDir` default `build/training-cache-daemon`, `port` 9484. | Starts the loopback service. One Gradle invocation does not itself enforce a longitudinal lifetime across dataset versions; the controller owns that lifetime. |
| `trainingCachePopulate` | Runs `TrainingCachePopulate`. Ordered arguments: `cacheDir` default `build/training-cache-daemon`, `samples` 128, `payloadBytes` 1048576. | Populates deterministic benchmark values, not external OCT input acquisition or an H2 protocol approval. |
| `trainingCacheTestReport` | Depends on `test`. Reads sorted `.xml` suite files from `test-results/test`, totals suite attributes and walks testcase elements. | Writes `reports/training-cache-test.json`. A failed dependency can prevent this action; it is not a finally-run failure collector. |

### Runtime Inventory and digest

The local `digest(file)` helper creates SHA-256, reads the input stream in reusable
1-MiB chunks until EOF and formats the digest as lowercase hexadecimal. `use`
closes the stream even on failure. File access errors propagate; the helper does
not retry, lock files or take an atomic filesystem snapshot.

`paperRuntimeClasspath` writes the platform-separated classpath before hashing
it. Selected source inputs are root `*.gradle.kts`, `gradle.properties`,
`gradle/**`, module build scripts and `src/main/**`, and build-logic scripts,
properties and `src/**`. Paths in the source map are relative to the root with
invariant separators and sorted before hashing. Python scripts, paper configs,
example sources and module tests are not included by that source selection.
Do not treat this receipt as an inventory of every campaign input.

Runtime entries are keyed by absolute path. A file receives `kind=file` and a
digest; a directory receives `kind=directory` and a sorted relative-file digest
map; a missing entry receives `kind=absent`. Directories are not hashed as a
single file. Absolute paths and the classpath text are host-specific even when
the class bytes match. Concurrent edits between reads can produce a mixed
inventory; the action does not validate consistency after writing.

The JSON has schema `aether-java-build-v1`, `sources`, `runtime`,
`classpathSha256` and `buildJavaVersion`. The latter is the Gradle process's
`java.version`, not an assertion about every separately launched JVM. The two
output writes are direct, not an atomic transaction. This task has no explicit
input/output declarations for the receipt action in the module script.

### JFR Launch Arguments

Only benchmark and daemon tasks add the optional JFR configuration here.
Supplying `-PjfrFile=...` resolves the path with `project.file`; it appends `.jfr`
unless that exact lowercase suffix already exists. `jfrSettings` defaults to
`profile`. JVM arguments set stack depth 256 and start recording with disk and
dump-on-exit enabled. The script does not create the destination parent directory
or verify that the resulting recording contains useful events. Crash/populate
tasks do not receive those JFR arguments from this block.

All four JavaExec tasks use main runtime classpath and explicit main classes.
The module script does not override their working directory; do not interpret
relative application path arguments as root-relative by looking at examples
that explicitly set `workingDir` in their own build files.

### Test Report Aggregation

Suite counters parse `tests`, `failures`, `errors` and `skipped`, falling back to
zero for missing or unparsable values. Each testcase status prioritizes failure,
then error, then skipped, then passed; duration falls back to 0.0. Report entries
store testcase class name as `suite`, testcase name, status and duration.
The aggregate `passed` is `tests - failures - errors - skipped`, not a recount
of successful case entries. Suite counters and case lists are not cross-validated.

If the XML directory is absent or has no matching files, the action can produce
a zero-test report. Malformed XML propagates a parser exception. The default DOM
factory is not explicitly hardened for untrusted XML in this source; this is
local Gradle test output, not a general uploaded-report parser. The report is
written directly and does not certify coverage, freshness of each suite, or a
successful scientific experiment.

## Verification

Documentation contracts check the explicit included projects, root registered
task names, one local helper and public-module agreement with the Maven checker.
These are static source checks, not execution of publishing/schema acceptance or
proof that placeholder tasks cover module tests. Generated-page equality, link
audits and browser checks validate the reference's presentation. Module-specific
build actions outside training-cache are inventoried in the module build topology
reference; whole-repo semantic coverage remains separate audit work.
Training-cache contracts enumerate its six registered tasks,
local digest helper, default properties and receipt fields. They compare source
and documentation; they do not execute JavaExec tasks, inspect actual JFR files,
or validate DOM parser behavior against untrusted reports.
