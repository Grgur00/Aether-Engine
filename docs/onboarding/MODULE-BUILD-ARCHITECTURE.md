# Module Build Topology and Actions

[Root build](ROOT-BUILD-ARCHITECTURE.md) | [Module guide](MODULE-GUIDE.md) | [Build conventions](GRADLE-PLUGIN-FUNCTIONS.md)

This inventory covers all 49 `modules/*/build.gradle.kts` files and both
`examples/*/build.gradle.kts` files included by root settings. Each row describes
direct project declarations only. Follow a source at
`modules/<name>/build.gradle.kts` or `examples/<name>/build.gradle.kts`;
the specialized action sources are linked below.
Across these 51 files, 44 use the library convention, five use the application
convention, one defines a Java platform and one defines the consumer Gradle plugin.

## Reading Dependency Scopes

`api` exposes a dependency on a library consumer's compile classpath;
`implementation` is available to this implementation and its runtime but is not
an exported compile dependency. It is not a Java access modifier or security
boundary. `testImplementation` supplies test compilation/runtime;
`annotationProcessor` supplies compilation processors, not application runtime;
`testAnnotationProcessor` applies to test compilation. Shared conventions add
common test dependencies separately. `none` means no direct project dependency
in this file, not an empty resolved classpath.

Dependency names below omit the common `aether-` prefix, but module names do not.
These are build edges, not function calls. Testkit naming does not establish that
only tests consume a module: several testkit builds export main-source APIs.
No table edge claims a production-integrated distributed engine.

```text
settings includes project -> module applies conventions -> declared scopes
    -> resolved compile/runtime/processor classpaths -> selected task
main code / generated codecs / test code have different classpaths
```

## Storage and Local Libraries

| Module | Direct Project Declarations |
| --- | --- |
| `aether-admission` | none |
| `aether-api` | none |
| `aether-cache` | implementation: api |
| `aether-config` | none |
| `aether-crypto` | implementation: reliability; testImplementation: io |
| `aether-engine` | api: api; implementation: admission, config, memory, format, observability-api, reliability, io, memtable, wal, sstable, lsm |
| `aether-format` | api: api; implementation: memory |
| `aether-io` | implementation: memory, format; testImplementation: reliability |
| `aether-lsm` | implementation: api, memory, format, io, memtable, wal, sstable |
| `aether-memory` | api: api |
| `aether-memtable` | implementation: api, memory, format |
| `aether-observability-api` | none |
| `aether-release` | none |
| `aether-reliability` | none |
| `aether-security-api` | none |
| `aether-security-core` | api: security-api |
| `aether-sstable` | implementation: api, memory, format, reliability, io |
| `aether-training-cache` | implementation: api, config, engine, sstable; testImplementation: reliability |
| `aether-wal` | implementation: api, memory, format, reliability, io |

All these apply `aether.java-library`. API, engine, format, IO, LSM, memory,
memtable, SSTable and WAL also apply `aether.publishing`; the others in this
table do not. Engine, memory and memtable append `-Xlint:-preview` to every
JavaCompile task. Suppressing that warning does not disable preview language
support, change the toolchain or establish compatibility with a non-preview JVM.
The [training-cache action reference](ROOT-BUILD-ARCHITECTURE.md#training-cache-module-actions)
explains all six of that module's custom tasks and the local digest helper.

## Typed Codecs and Build Products

| Module | Direct Project Declarations |
| --- | --- |
| `aether-codec` | api: api, codec-annotations |
| `aether-codec-annotations` | none |
| `aether-codec-processor` | implementation: codec-annotations; testImplementation: api, codec |
| `aether-embedded-typed` | api: api, codec-annotations; implementation: codec, engine; testImplementation: codec-annotations; testAnnotationProcessor: codec-processor |
| `aether-gradle-plugin` | none |
| `aether-bom` | api: api, memory, format, io, memtable, wal, sstable, lsm, engine, codec-annotations, codec, codec-processor, embedded-typed, gradle-plugin |

Codec, annotations, processor and embedded-typed apply library and publishing
conventions. In [embedded-typed](../../modules/aether-embedded-typed/build.gradle.kts),
`compileTestJava` receives
`-Aaether.schemaDirectory=${project.projectDir}/src/test/aether-schemas`.
Test annotations are explicitly visible to compiler/IDE consumers, and test
processor output is separate from application annotations. This does not register
a runtime processor or accept schema proposals automatically.

The [Gradle plugin build](../../modules/aether-gradle-plugin/build.gradle.kts)
applies `java-gradle-plugin`, Java base, testing and publishing. It enables source
and Javadoc JARs and registers `aetherSchema`: external ID
`io.github.grgur00.aether`, display name Aether Engine, implementation
`io.aetherdb.gradle.AetherPlugin`. The JAR manifest stores `Implementation-Version`
from project version. Registration is packaging metadata, not execution of the
consumer plugin. See the function reference for its application behavior.

The [BOM](../../modules/aether-bom/build.gradle.kts) applies `java-platform` and
publishing, with `allowDependencies()`. Its table row is **14 API constraints**,
not library runtime dependencies: importing the platform aligns eligible modules
without loading all implementations. It excludes itself, internal distributed
foundations, training-cache and application tools. A constraint set is not a
complete support or test matrix.

## Distributed Foundations and Clients

| Module | Direct Project Declarations |
| --- | --- |
| `aether-client` | api: api, client-api; implementation: codec, client-codec, rpc-api |
| `aether-client-api` | none |
| `aether-client-codec` | api: client-api; implementation: format |
| `aether-client-testkit` | api: client-api, client-codec |
| `aether-cluster-api` | none |
| `aether-cluster-codec` | api: cluster-api; implementation: format |
| `aether-cluster-core` | api: cluster-api |
| `aether-raft-api` | none |
| `aether-raft-core` | api: raft-api; implementation: config, reliability |
| `aether-raft-storage` | api: raft-api; implementation: format, reliability |
| `aether-raft-testkit` | api: raft-api, raft-core |
| `aether-replicated-log` | api: replication-api; implementation: api, format, io, reliability |
| `aether-replication-api` | none |
| `aether-replication-testkit` | api: replicated-log, state-machine |
| `aether-rpc-api` | api: admission |
| `aether-rpc-codec` | api: rpc-api; implementation: format |
| `aether-rpc-testkit` | api: rpc-codec, rpc-transport |
| `aether-rpc-transport` | api: rpc-api; implementation: config, rpc-codec |
| `aether-state-machine` | api: replication-api; implementation: replicated-log |

These files all apply `aether.java-library` without local publication configuration
or custom task blocks. Main-scope `api` in testkit files deliberately exports the
listed types to their consumers; it is not shorthand for `testImplementation`.
The root public publication set does not include these modules. The engine's
direct dependencies above do not wire in Raft, replication, remote client or
cluster modules. Follow the architectural runtime boundaries in the module guide
before assuming a distributed service exists.

## Applications, Examples and Test Infrastructure

| Module | Direct Project Declarations |
| --- | --- |
| `aether-benchmarks` | implementation: api, engine |
| `aether-concurrency-tests` | implementation: memory, memtable, lsm |
| `aether-testkit` | none |
| `aether-tools` | implementation: admission, api, config, crypto, engine, format, io, release, reliability, security-core, sstable, wal |
| `aether-workbench` | implementation: api, codec, engine, rpc-codec, replicated-log |
| `aether-persistent-notes` | implementation: api, codec, embedded-typed; annotationProcessor: codec-processor |
| `aether-sample-app` | implementation: api, codec, embedded-typed; annotationProcessor: codec-processor |

Concurrency-tests uses the library convention; its dependencies are main
implementation scope despite the project name. Testkit uses the library convention
plus `java-test-fixtures`. That creates fixture machinery, not automatic execution
of crash or concurrency campaigns through root placeholder tasks.

Benchmarks, tools, workbench and both examples apply `aether.java-application`.
Their main classes and additional actions are:

| Source | Main Class and Configuration |
| --- | --- |
| [benchmarks](../../modules/aether-benchmarks/build.gradle.kts) | `io.aetherdb.benchmarks.BenchmarkProfileRunner`; external implementation dependency `org.hdrhistogram:HdrHistogram:2.2.2`. |
| [tools](../../modules/aether-tools/build.gradle.kts) | `io.aetherdb.tools.AetherCli`; no local run working-directory override or JFR block. |
| [workbench](../../modules/aether-workbench/build.gradle.kts) | `io.aetherdb.workbench.AetherWorkbench`; `run` working directory is root project directory. |
| [persistent notes](../../examples/aether-persistent-notes/build.gradle.kts) | `io.aetherdb.examples.notes.PersistentNotesApplication`; `run` working directory is root project directory. |
| [sample app](../../examples/aether-sample-app/build.gradle.kts) | `io.aetherdb.examples.social.SocialNetworkApplication`; `run` working directory is root project directory. |

Benchmarks conditionally configures its `run` task when environment `AETHER_JFR`
equals the exact string `true`. It resolves `build/jfr/aether-benchmark.jfr` via
the module build directory, creates its parent, adds StartFlightRecording with
`settings=profile,disk=true,dumponexit=true,maxsize=2g`, and supplies
`aether.benchmark.jfr.path` as a system property. The check and parent creation
occur when this task is configured, not inside a `doLast` action. This is distinct
from training-cache's `-PjfrFile` and stackdepth configuration. Neither registration
nor the path property proves that a valid recording was produced.

Relative paths for workbench/examples resolve from the explicitly selected root
working directory when running through Gradle. Direct `java` invocations and
other applications need their own working-directory decision. Annotation
processing in the examples generates codecs during compilation; it does not
start an engine or daemon as part of project configuration.

## Verification and Change Ownership

Documentation tests compare all 51 module/example rows and each dependency scope
against current project declarations, check application main classes and verify
the publication/convention distinctions. This source-level inventory does not
resolve transitive dependencies, execute Gradle tasks, publish artifacts, launch
applications or prove runtime integration. Registered actions here are Gradle
configuration lambdas, not additional named Java methods. The training-cache
digest helper is covered in the root build reference.

When changing a dependency, review exported consumer types, annotation processor
visibility, runtime packaging and fixture consumers independently. When changing
a launch action, update defaults and working-directory documentation along with
the relevant application function reference. Whole-codebase semantic/function
coverage remains a separate audit, not something this build inventory proves.
