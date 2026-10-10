# Module Guide For Contributors

[Onboarding index](README.md) | [Guided code tour](CODE-TOUR.md) | [Architecture](ARCHITECTURE.md)

This is a lookup table for choosing where to read and edit code. All 49 Java
modules declared in [settings.gradle.kts](../../settings.gradle.kts) appear below.
Linked build files define actual dependencies and tasks; named symbols are search
targets within the module, not claims that every subsystem is integrated.

## Local Engine And Application Layers

The [observability reference](OBSERVABILITY-FUNCTIONS.md) covers metric/health
contracts, owning metered decorator, bounded latency samples and append-only
registry export. Scan creation is not traversal; exported percentiles are not
histogram buckets, and metrics do not prove durability.

| Module/build boundary | Start with | Responsibility and change boundary |
| --- | --- | --- |
| [aether-api](../../modules/aether-api/build.gradle.kts) | `AetherDatabase`, `WriteBatch`, `Snapshot`, typed interfaces | Public semantic and resource-lifetime contracts; changes affect callers and both engines |
| [aether-engine](../../modules/aether-engine/build.gradle.kts) | `Aether`, `PersistentAetherDatabase`, `InMemoryAetherDatabase` | Local composition, commit/visibility/recovery and background lifecycle |
| [aether-memory](../../modules/aether-memory/build.gradle.kts) | `FfmNativeRegion`, `NativeMemoryBudget` | Native allocation, bounds and lifetime; do not create unrelated FFM allocators elsewhere |
| [aether-memtable](../../modules/aether-memtable/build.gradle.kts) | `NativeSkipListMemTable`, `VersionedKeyValueStore` | Ordered versioned mutable records; reference model versus native implementation |
| [aether-format](../../modules/aether-format/build.gradle.kts) | `AetherFormatCatalog`, `ChecksumPolicy` | Shared persisted-format identities and compatibility/checksum policies |
| [aether-io](../../modules/aether-io/build.gradle.kts) | `PathSecurityValidator`, `DatabaseLock`, backup/restore classes | Validated filesystem ownership, locks and storage I/O primitives |
| [aether-wal](../../modules/aether-wal/build.gradle.kts) | `WalLogicalGroupCodec`, `WalFragmentCodec` | Logical groups, physical fragments and replay validation; reopen compatibility matters |
| [aether-sstable](../../modules/aether-sstable/build.gradle.kts) | `SSTableBuilder`, `SSTableReader`, `VersionSet` | Immutable tables, index/filter blocks and durable manifest authority |
| [aether-lsm](../../modules/aether-lsm/build.gradle.kts) | `CompactionDroppingIterator`, `WritePressureController` | Selection/retention and write-pressure policies consumed by engine orchestration |
| [aether-cache](../../modules/aether-cache/build.gradle.kts) | `BlockLease`, `BlockCacheKey` | Cache primitives and leased ownership; inspect consumers before assuming integration |
| [aether-codec-annotations](../../modules/aether-codec-annotations/build.gradle.kts) | `AetherField`, record annotations | User-facing schema declarations and stable field identities |
| [aether-codec-processor](../../modules/aether-codec-processor/build.gradle.kts) | `AetherRecordProcessor` | Compile-time generated codecs/providers and schema proposals |
| [aether-codec](../../modules/aether-codec/build.gradle.kts) | `BuiltInKeyCodecs`, `SchemaCompatibilityChecker` | Runtime typed encoding, envelopes and schema compatibility |
| [aether-embedded-typed](../../modules/aether-embedded-typed/build.gradle.kts) | `AetherEmbedded`, `EmbeddedTypedDatabase` | Typed collections over the local byte engine; metadata and close ownership |
| [aether-training-cache](../../modules/aether-training-cache/build.gradle.kts) | `TrainingCache`, `TrainingCacheProtocol`, `TrainingCacheDaemon` | Local immutable-artifact service; a distinct Python-facing protocol, not general RPC |

The engine exposes `aether-api` through an `api` dependency and consumes lower
storage modules through `implementation`. The typed wrapper composes the engine
and codec layer; it is not where WAL ordering should be changed. Inspect build
files before adding a dependency: an API edge exposes types to consumers, while
an implementation edge keeps the dependency behind the module boundary.

## Cross-Cutting And Developer Tools

| Module/build boundary | Start with | Responsibility and limitation |
| --- | --- | --- |
| [aether-admission](../../modules/aether-admission/build.gradle.kts) | `AdmissionController`, `AdmissionDecision` | Explicit resource/overload decisions; rejection is part of behavior |
| [aether-config](../../modules/aether-config/build.gradle.kts) | `AetherConfigRegistry`, `AetherConfigLoader` | Typed settings, validation and configuration models; verify each actual runtime consumer |
| [aether-observability-api](../../modules/aether-observability-api/build.gradle.kts) | `MetricDescriptor`, `HealthStatus` | Diagnostic contracts; metrics are not substitutes for durability checks |
| [aether-reliability](../../modules/aether-reliability/build.gradle.kts) | `CrashPointRegistry`, corruption helpers | Explicit fault hooks and controlled failure fixtures |
| [aether-security-api](../../modules/aether-security-api/build.gradle.kts) | `AetherAuthorizer`, `AuditSink` | [Authorization and audit contract functions](SECURITY-FUNCTIONS.md); not automatic endpoint enforcement |
| [aether-security-core](../../modules/aether-security-core/build.gradle.kts) | `AuditJsonFormatter`, `FileAuditSink` | [Audit and node identity functions](SECURITY-FUNCTIONS.md) |
| [aether-crypto](../../modules/aether-crypto/build.gradle.kts) | `AetherAead`, `AeadEnvelope` | [Encryption and key wrapping functions](CRYPTO-FUNCTIONS.md); not automatic engine encryption |
| [aether-tools](../../modules/aether-tools/build.gradle.kts) | `AetherCli` | Offline inspection/administration; respect exclusive directory ownership |
| [aether-workbench](../../modules/aether-workbench/build.gradle.kts) | `AetherWorkbench`, `DatabaseWorkspace` | Swing local database tooling; not a headless server |
| [aether-release](../../modules/aether-release/build.gradle.kts) | Evidence/certification models | Release-readiness evaluation machinery, not evidence that a release is certified |
| [aether-bom](../../modules/aether-bom/build.gradle.kts) | Gradle constraints | Consumer dependency alignment; no application runtime entry point |
| [aether-gradle-plugin](../../modules/aether-gradle-plugin/build.gradle.kts) | `AetherPlugin` | Consumer build/schema integration; test generated-task behavior |
| [aether-benchmarks](../../modules/aether-benchmarks/build.gradle.kts) | `BenchmarkProfileRunner` | Executable workload registration; not every named profile has a runner |
| [aether-testkit](../../modules/aether-testkit/build.gradle.kts) | Source sets and fixture declarations | Test support; do not add as a production dependency |
| [aether-concurrency-tests](../../modules/aether-concurrency-tests/build.gradle.kts) | Actual attached tasks/source sets | Concurrency-test boundary; a module name does not establish an active JCStress suite |

## Remote And Distributed Foundations

The [replication contract reference](REPLICATION-CONTRACT-FUNCTIONS.md) covers
the complete replication API, sequence planner, and applied-state record. It
does not replace concrete log-store or Raft algorithm review.
The [replicated format reference](REPLICATION-FORMAT-FUNCTIONS.md) explains
command, entry, identity and segment-header bytes and integrity checks.
The [concrete store reference](REPLICATED-STORE-FUNCTIONS.md) covers append/force,
rotation, recovery, guarded truncation and lifecycle failure boundaries.
The [Raft core reference](RAFT-CORE-FUNCTIONS.md) covers vote contracts, freshness,
follower progress, quorum/commit helpers and runtime configuration, not a complete
running consensus service.
The [Raft storage reference](RAFT-STORAGE-FUNCTIONS.md) details vote bytes and
state-slot checks, without implying a slot manager or force-before-reply service.
The [cluster membership reference](CLUSTER-MEMBERSHIP-FUNCTIONS.md) covers every
API/core declaration: endpoints, identity, stable/joint values and dual-majority
calculations. Configuration hashes and durable acknowledgments still need caller
validation; these modules do not supply a running membership-change service.
The [cluster codec reference](CLUSTER-CODEC-FUNCTIONS.md) explains identity and
stable/joint byte layouts, integrity coverage, embedded hash handling and parsing
limits. Byte integrity does not prove authorized or committed membership history.

The [RPC frame function reference](RPC-FRAME-FUNCTIONS.md) details wire envelopes,
incremental decoding, HELLO validation, fragmentation, assembly, stream IDs and
the checks left to connection layers.
The [RPC API reference](RPC-API-FUNCTIONS.md) covers call policy, handlers,
cancellation, payload ownership and admission charges without implying transport
or exactly-once mutation guarantees.
The [transport limits reference](RPC-TRANSPORT-LIMITS.md) maps configuration to
runtime budgets and identifies standalone foundations not wired into plaintext RPC.
The [development server reference](RPC-SERVER-FUNCTIONS.md) traces actual socket
ownership, handler dispatch, responder completion and immediate shutdown behavior.
The [development client reference](RPC-CLIENT-FUNCTIONS.md) completes transport
coverage with connection reuse, retry/deadline boundaries and accounting cleanup.
The [remote routing reference](REMOTE-ROUTING-FUNCTIONS.md) describes SDK endpoint
selection, inflight pooling and uncertainty-aware retry decisions above RpcClient.
The [remote facade reference](REMOTE-FACADE-FUNCTIONS.md) traces application calls
and typed collection mappings, including retry-option integration boundaries.

These modules are a separate development path. An ordinary `Aether.open(path)`
does not contact Raft, and the training-cache daemon is not a secured cluster
node. Trace concrete transport/handler wiring before describing an end-to-end
distributed feature as implemented.

| Module/build boundary | Start with | Responsibility |
| --- | --- | --- |
| [aether-rpc-api](../../modules/aether-rpc-api/build.gradle.kts) | `RpcCallOptions`, handler/status contracts | Calls, deadlines, admission and error semantics |
| [aether-rpc-codec](../../modules/aether-rpc-codec/build.gradle.kts) | `RpcFrameCodecV1`, `RpcFrameDecoder` | Framing, bounded decoding and wire compatibility |
| [aether-rpc-transport](../../modules/aether-rpc-transport/build.gradle.kts) | `PlaintextDevelopmentRpc` | Development socket transport; no confidentiality/certificate authentication |
| [aether-rpc-testkit](../../modules/aether-rpc-testkit/build.gradle.kts) | Source sets and fixtures | RPC test support |
| [aether-client-api](../../modules/aether-client-api/build.gradle.kts) | `ClientGetRequest`, `ClientProtocol` | [Remote-client contract functions](CLIENT-PROTOCOL-FUNCTIONS.md) |
| [aether-client-codec](../../modules/aether-client-codec/build.gradle.kts) | `ClientGetCodecV1`, `ClientWriteCodecV1` | [Client serialization functions](CLIENT-CODEC-FUNCTIONS.md) |
| [aether-client](../../modules/aether-client/build.gradle.kts) | `RemoteAetherClient`, `RemoteConnectionPool` | Endpoint resolution, connection use and retry/outcome handling |
| [aether-client-testkit](../../modules/aether-client-testkit/build.gradle.kts) | Source sets and fixtures | Client test support |
| [aether-replication-api](../../modules/aether-replication-api/build.gradle.kts) | `ReplicatedLogStore`, `LogPosition` | Replicated log contracts and identities |
| [aether-replicated-log](../../modules/aether-replicated-log/build.gradle.kts) | `ReplicatedLogEntryCodecV1` | Persisted replicated-entry encoding; distinct from local WAL groups |
| [aether-state-machine](../../modules/aether-state-machine/build.gradle.kts) | `AppliedState` | Applied-state foundations |
| [aether-replication-testkit](../../modules/aether-replication-testkit/build.gradle.kts) | Source sets and fixtures | Replication test support |
| [aether-raft-api](../../modules/aether-raft-api/build.gradle.kts) | `RaftRole`, vote/append reason types | Consensus-facing records/contracts |
| [aether-raft-core](../../modules/aether-raft-core/build.gradle.kts) | `RaftCommitTracker`, `FollowerProgress` | Progress, freshness, quorum and commit helpers |
| [aether-raft-storage](../../modules/aether-raft-storage/build.gradle.kts) | `RaftPersistentState`, state-slot codecs | Persisted consensus-state encoding |
| [aether-raft-testkit](../../modules/aether-raft-testkit/build.gradle.kts) | Source sets and fixtures | Raft test support |
| [aether-cluster-api](../../modules/aether-cluster-api/build.gradle.kts) | `ClusterIdentity`, `ClusterConfiguration` | Membership and identity contracts |
| [aether-cluster-codec](../../modules/aether-cluster-codec/build.gradle.kts) | `IdentityCodecV1`, configuration codecs | Durable/wire membership representations |
| [aether-cluster-core](../../modules/aether-cluster-core/build.gradle.kts) | `DualMajority` | Joint-configuration quorum logic, not complete deployment orchestration |

## From Module To Test

The [workbench workspace reference](WORKBENCH-WORKSPACE-FUNCTIONS.md) explains
database ownership, display-key indexing, editing and local codec inspectors.
The [universal typed-value reference](WORKBENCH-TYPED-VALUE-FUNCTIONS.md) explains
record text parsing, scalar modes and outer-envelope preservation.
The [record dialog reference](WORKBENCH-RECORD-DIALOG-FUNCTIONS.md) covers modal
editor selection, structured field controls and blank-form defaults.
The [window/action reference](WORKBENCH-WINDOW-FUNCTIONS.md) completes the module's
function map with startup, borrowed ownership, actions and grouped rendering.

The [Gradle plugin and conventions reference](GRADLE-PLUGIN-FUNCTIONS.md) separates
repository Java/test conventions from the published consumer schema plugin.

The [release certification reference](RELEASE-CERTIFICATION-FUNCTIONS.md) traces
evidence declarations, blocker rules, properties codec and readiness report output.

The [Java benchmark runner reference](JAVA-BENCHMARK-RUNNER-FUNCTIONS.md) explains
which registered profiles execute, combined load/read/recovery timing and report
limits. Registration of a distributed profile is not a running cluster benchmark.
The [benchmark evidence reference](JAVA-BENCHMARK-EVIDENCE-FUNCTIONS.md) traces
report validation, JSON output, artifact paths and compact baseline serialization.
The [comparison reference](JAVA-BENCHMARK-COMPARISON-FUNCTIONS.md) covers numeric
regression gates, median-run selection and declared external-engine semantics.

Within a Java module, start at `src/main/java`, then read matching packages under
`src/test/java`. Build conventions are in `build-logic`, not duplicated in every
module. Package-private implementations are intentionally tested within their
package; widening an API solely to reach a test is usually unnecessary.

For example, an SSTable decoder change needs SSTable malformed-input tests and
affected engine reopen tests, while a typed collection change needs typed/codec
tests rather than a storage format rewrite. Exact commands, optional test flags
and placeholder-task limitations are in [Testing and contributing](TESTING-AND-CONTRIBUTING.md).

Python code lives under `clients/python`: `aether_training_cache` is the wire/store
integration, and `aether_ml` adds derived-artifact identity, transforms and dataset
adapters. `scripts` orchestrates research/reproduction rather than implementing
the public database. See [Architecture](ARCHITECTURE.md#non-java-areas) for other
repository areas and [Operations](OPERATIONS-AND-DEBUGGING.md) before opening an
existing database with tools.

The [H2 session-controller reference](H2-SESSION-FUNCTIONS.md) explains the
separately authorized multi-host evidence layer over the original frozen
research harness, including the currently session-2-only execution limit.
The [DALI workload reference](DALI-COMPARISON-FUNCTIONS.md) separates direct GPU
RGB preprocessing from Java/mmap artifact reuse and secondary analysis; it is
not the H2/OCT workload or evidence of equivalent Pillow decoding.
The [bulk layout regression reference](BULK-LAYOUT-FUNCTIONS.md) explains sampled
RSS, activity-fenced warm packed reads and exact-prefix incremental readback,
all separate from the V0 population endpoint.
The [dataset preparation reference](DATASET-PREPARATION-FUNCTIONS.md) follows
COCO acquisition, hashed vision manifests, seeded V1/V2 membership and preflight
validation, separating ID overlap from verified artifact reuse.
The [transform evolution reference](TRANSFORM-EVOLUTION-FUNCTIONS.md) covers the
synthetic six-scenario final-artifact invalidation check, not stage-level reuse
or longitudinal training performance.
The [preflight utility reference](PREFLIGHT-UTILITY-FUNCTIONS.md) distinguishes
recorded-file checksums, CUDA host smoke and CPU artifact/backend correctness.
The [systems driver reference](SYSTEM-DRIVER-FUNCTIONS.md) separates shared-store
process scaling from GPU training and instrumented process death from power loss.
The [systems analysis reference](SYSTEM-ANALYSIS-FUNCTIONS.md) documents frozen
runtime comparisons, paired ratios and resource/concurrency/recovery exports.
The [campaign audit reference](CAMPAIGN-AUDIT-FUNCTIONS.md) separates the older
suite's coverage/claim gate, archive preservation and V1 validation from H2.
The [build utility reference](BUILD-UTILITY-FUNCTIONS.md) explains static handbook
generation, staged Maven inspection, draft PDF compilation and Studio CLI quoting.
The [root build reference](ROOT-BUILD-ARCHITECTURE.md) separates project topology,
shared conventions, release staging, schema aggregation and verification placeholders.
The [module build topology reference](MODULE-BUILD-ARCHITECTURE.md) inventories
all 49 module and two example builds, separating exported API dependencies,
implementation, processor/test classpaths, application launch and packaging actions.
The [example application reference](EXAMPLE-APPLICATION-FUNCTIONS.md) explains
both examples' complete main trees, including persistent notes, Swing close
ownership, social repositories, application-side joins and implicit record methods.
