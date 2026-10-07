# Module Guide For Contributors

[Onboarding index](README.md) | [Guided code tour](CODE-TOUR.md) | [Architecture](ARCHITECTURE.md)

This is a lookup table for choosing where to read and edit code. All 49 Java
modules declared in [settings.gradle.kts](../../settings.gradle.kts) appear below.
Linked build files define actual dependencies and tasks; named symbols are search
targets within the module, not claims that every subsystem is integrated.

## Local Engine And Application Layers

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
| [aether-security-api](../../modules/aether-security-api/build.gradle.kts) | `AetherAuthorizer`, `AuditSink` | Authorization/identity/audit contracts; not automatic enforcement on every entry point |
| [aether-security-core](../../modules/aether-security-core/build.gradle.kts) | `AuditJsonFormatter`, `FileAuditSink` | Audit implementations and supporting security helpers |
| [aether-crypto](../../modules/aether-crypto/build.gradle.kts) | `AetherAead`, `AeadEnvelope` | Cryptographic envelopes/primitives; existence does not imply all engine files are encrypted |
| [aether-tools](../../modules/aether-tools/build.gradle.kts) | `AetherCli` | Offline inspection/administration; respect exclusive directory ownership |
| [aether-workbench](../../modules/aether-workbench/build.gradle.kts) | `AetherWorkbench`, `DatabaseWorkspace` | Swing local database tooling; not a headless server |
| [aether-release](../../modules/aether-release/build.gradle.kts) | Evidence/certification models | Release-readiness evaluation machinery, not evidence that a release is certified |
| [aether-bom](../../modules/aether-bom/build.gradle.kts) | Gradle constraints | Consumer dependency alignment; no application runtime entry point |
| [aether-gradle-plugin](../../modules/aether-gradle-plugin/build.gradle.kts) | `AetherPlugin` | Consumer build/schema integration; test generated-task behavior |
| [aether-benchmarks](../../modules/aether-benchmarks/build.gradle.kts) | `BenchmarkProfileRunner` | Executable workload registration; not every named profile has a runner |
| [aether-testkit](../../modules/aether-testkit/build.gradle.kts) | Source sets and fixture declarations | Test support; do not add as a production dependency |
| [aether-concurrency-tests](../../modules/aether-concurrency-tests/build.gradle.kts) | Actual attached tasks/source sets | Concurrency-test boundary; a module name does not establish an active JCStress suite |

## Remote And Distributed Foundations

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
| [aether-client-api](../../modules/aether-client-api/build.gradle.kts) | `ClientGetRequest`, `ClientProtocol` | General remote-client request/response contracts |
| [aether-client-codec](../../modules/aether-client-codec/build.gradle.kts) | `ClientGetCodecV1`, `ClientWriteCodecV1` | Client operation serialization |
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
