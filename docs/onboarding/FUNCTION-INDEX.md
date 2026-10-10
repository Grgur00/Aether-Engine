# Function Reference Index

[Onboarding](README.md) | [Architecture](ARCHITECTURE.md) | [Module ownership](MODULE-GUIDE.md)

Use these references after the guided code tour to follow individual functions,
constructors, private helpers, ownership, copies, validation, and failure paths.
They describe the current checkout, including development changes that may not yet
be published on `main`. Source links on the website target `main`; check your local
revision when the implementation differs.

## Engine Entry Points and Orchestration

| Reference | Source coverage | Main questions |
| --- | --- | --- |
| [Engine functions](ENGINE-FUNCTIONS.md) | `Aether`, both database implementations, `WriteBatch`, snapshot handles, materialized cursors, `LookupResult` | What does an application call? Which bytes are copied? What is visible after a write? |
| [Persistent internals](PERSISTENT-INTERNALS.md) | Persistent commit, flush, recovery, pressure integration, compaction coordinator and lifecycle helpers | Which locks and publication steps establish visibility and durability? What happens after failure? |
| [Observability functions](OBSERVABILITY-FUNCTIONS.md) | Eight observability API and six engine telemetry files, 50 declarations | What is timed, which samples are bounded, and what does export record? |

Read these together: public factory behavior is not the full commit protocol,
and a helper name does not establish where its caller places a durability barrier.

## Immutable Tables and Manifest Authority

| Reference | Source coverage | Main questions |
| --- | --- | --- |
| [SSTable block functions](SSTABLE-BLOCK-FUNCTIONS.md) | `InternalKey`, restart encoding and scanner, varints, block envelope/handle, Bloom filter | How are entries represented and checksummed? What can verification skip copying? |
| [SSTable reads and verification](SSTABLE-READ-FUNCTIONS.md) | Reader/data blocks, streaming verifier, lookup/entry metadata, header/footer | What is checked on open or verification? What is cached? How are values materialized? |
| [SSTable construction](SSTABLE-BUILD-FUNCTIONS.md) | Builder, bulk-install bridge, finish/verification traces, bulk JFR event | When are files complete? What does deferred verification mean before publication? |
| [Manifest versions](MANIFEST-VERSION-FUNCTIONS.md) | `VersionSet`, `Version`, edits, additions/deletions, inspection | What makes an inventory authoritative? What is replayed, verified, or cleaned up? |
| [Manifest wire formats](MANIFEST-CODEC-FUNCTIONS.md) | CURRENT, manifest header/record codecs, corruption exception, masked CRC32C | Which bytes are protected and which parser checks apply? |
| [WAL formats](WAL-FUNCTIONS.md) | WAL constants, segment header, logical group and fragment codecs, corruption exception | How are logical writes fragmented, validated, and recovered from a prefix? |

The table verifier, reader cache, manifest publication, and WAL replay are distinct
integrity boundaries. A successful structural parse is not automatically a
published transaction or fresh checksum verification.

## Mutable Records and Native Memory

| Reference | Source coverage | Main questions |
| --- | --- | --- |
| [Heap MVCC reference](MVCC-REFERENCE-FUNCTIONS.md) | Reference store/record, byte-key identity, sequence source, unsigned comparison | What are the baseline version and snapshot semantics? |
| [Native memtable](NATIVE-MEMTABLE-FUNCTIONS.md) | Skip-list table, node layout, height generator, lookup result | How are initialized nodes published and retained across readers? |
| [Native regions and allocation](NATIVE-REGION-FUNCTIONS.md) | Capacity rules, budget, factory, FFM region, bump allocator, allocation exception | Who owns native memory, what is charged, and when can it close? |
| [Native records and access](NATIVE-RECORD-FUNCTIONS.md) | Native access helpers, record format/writer/reader/view, corruption exception | Which reads validate structure, borrow storage, or copy bytes? |

Start with the heap model for semantic ordering, then trace native node publication
and region lifetime separately. A Java reference to a borrowed view is not a
native-region lease.

## LSM Reads, Compaction, and Pressure

| Reference | Source coverage | Main questions |
| --- | --- | --- |
| [Compaction planning](COMPACTION-PLANNING-FUNCTIONS.md) | Config, file/inventory models, scores/debt, picker, plan/reasons, range registry | How are levels and overlapping files selected? Which assumptions remain with the coordinator? |
| [LSM iterators and reclamation](LSM-ITERATOR-FUNCTIONS.md) | Internal entries, list/merge iterators, snapshot collapse, dropping iterator, base-level checker | Which versions survive a read or compaction? When may a tombstone disappear? |
| [Read views and snapshots](READ-VIEW-FUNCTIONS.md) | Topology, retained source, view/handle/manager, snapshot multiset | What pins physical sources and what protects visibility sequences? |
| [Write pressure](WRITE-PRESSURE-FUNCTIONS.md) | Measurement/policy/result models, controller, states and reasons | What triggers slowdown, stop, or failure, and who actually waits? |

Planning does not execute compaction. Snapshot registration does not pin a file.
Pressure evaluation does not reserve a sequence or submit a WAL group. Use the
persistent-internals reference to connect those policies to engine orchestration.

## Supporting Policy and Test Mechanisms

| Reference | Source coverage | Main questions |
| --- | --- | --- |
| [Resource admission](ADMISSION-FUNCTIONS.md) | Resource limits/measurements, policy/snapshot/request/decision, evaluator and enums | What does acceptance guarantee for the supplied measurements, and what must callers reserve? |
| [Reliability and fault injection](RELIABILITY-FUNCTIONS.md) | Crash dispatcher/context/scopes/triggers/IDs, corruption plans and mutator | Is a test injecting an exception, process death, or persisted-byte corruption? |
| [Block cache](BLOCK-CACHE-FUNCTIONS.md) | Key/loader/lease/metrics, sharded cache and private eviction helpers | How are loads coalesced, arrays borrowed, entries pinned, and residency charged? |
| [Format catalogs](FORMAT-CATALOG-FUNCTIONS.md) | Format descriptors, policies, catalog and golden-fixture registry | Which metadata is descriptive, and what actually verifies bytes? |

Resource admission and the standalone block cache are available modules, not
automatically integrated into every engine path. Each reference records the
consumer boundaries checked in source.

## Broader Guides and Remaining Detail

The [filesystem identity reference](FILESYSTEM-IDENTITY-FUNCTIONS.md) covers managed
paths, OS locking, DB-IDENTITY, FORMAT-OPTIONS, and checkpoint metadata. The
[backup archive reference](BACKUP-ARCHIVE-FUNCTIONS.md) covers inventory objects,
manifest framing, ZIP I/O, hash verification, and content copies. The
[restore reference](BACKUP-RESTORE-FUNCTIONS.md) covers policy preflight, object
writing, checkpoint path mapping, and cleanup limitations.

The [typed key reference](TYPED-KEY-FUNCTIONS.md) now covers built-in scalar key
codecs, codec interfaces, collection identity, and physical key prefixes. The
[typed value reference](TYPED-VALUE-FUNCTIONS.md) covers scalar UTF-8 values and
the schema envelope. The [collection metadata reference](COLLECTION-SCHEMA-FUNCTIONS.md)
covers definitions, metadata persistence encoding, and descriptor compatibility.
The [canonical record reference](CANONICAL-RECORD-FUNCTIONS.md) covers AER1 framing,
scalar payload helpers, field iteration, and checksum/parsing boundaries.
The [generated container reference](GENERATED-CONTAINER-FUNCTIONS.md) covers
optional/list/set/map framing, scalar element decoding, provider lookup, and
descriptor resource validation. The [typed adapter reference](TYPED-ADAPTER-FUNCTIONS.md)
covers embedded factories, registration, collection handles, batches, snapshots,
and result contracts. The [schema annotation reference](SCHEMA-ANNOTATION-FUNCTIONS.md)
covers annotation members and the processor's entry, validation, and type-resolution
path. The [schema resource reference](SCHEMA-RESOURCE-FUNCTIONS.md) covers lock
loading, retired/reserved identities, descriptors, providers, and proposals.
The [codec generation reference](CODEC-GENERATION-FUNCTIONS.md) covers emitted
methods, field/default/container expressions, and FieldType helpers. Together these
three references cover the processor's declarations; Gradle schema tooling remains.

The [module guide](MODULE-GUIDE.md) covers all 49 Java module declarations at an
ownership level. That is broader than the individual-function references above.
The following guides explain workflows and architecture but do not yet constitute
an exhaustive function-by-function inventory of every implementation they mention:

- [Typed API and schemas](TYPED-API-AND-SCHEMAS.md): generated codecs, compatibility, collections, and typed adapters.
- [Python and training cache](TRAINING-CACHE-AND-PYTHON.md): identity-aware artifacts, client/protocol, and ML integration.
- [Operations and debugging](OPERATIONS-AND-DEBUGGING.md): CLI, configuration, filesystem ownership, and diagnostics.
- [Research tooling](EXPERIMENTS-AND-PROFILING.md): experiment orchestration, provenance, profiling, and result interpretation.
- [Architecture](ARCHITECTURE.md): remote/client, RPC, replication, Raft, security, and other independent foundations.

The [configuration loading reference](CONFIG-LOADING-FUNCTIONS.md) covers immutable
values, registered defaults, source precedence, scalar and combination validation,
redaction, and selected runtime conversion boundaries. The
[configuration change reference](CONFIG-CHANGE-FUNCTIONS.md) covers reload
evaluation, atomic configuration-state replacement, voting-member comparisons,
and staged old/new transitions. Together these cover the configuration module's
explicit declarations, not live reconfiguration of every runtime subsystem.

Schema tooling, operational commands, remaining Python workload helpers,
and distributed foundations still need further individual-function coverage.
The presence of a guide or source link does not prove that expansion is complete.

## Training Cache and Artifact Reuse

| Reference | Source coverage | Main questions |
| --- | --- | --- |
| [Artifact identity and storage policy](TRAINING-IDENTITY-FUNCTIONS.md) | Fingerprints, cache keys/entries, storage selection, durability modes | What makes a reusable artifact identity? Which bytes are copied? When is a segment selected? |
| [Cache operations and segments](TRAINING-CACHE-FUNCTIONS.md) | TrainingCache, segment store/reference, packed results, latency and metrics | How are immutable values published, validated, mapped, evicted, and reconstructed on reopen? |
| [Java diagnostic drivers](TRAINING-DRIVER-FUNCTIONS.md) | HitPathBenchmark, TrainingCacheBenchmark and TrainingCacheCrashCampaign | What do standalone Java lookup, synthetic reuse and forced-halt diagnostics measure and validate? |
| [Daemon lifecycle](TRAINING-DAEMON-FUNCTIONS.md) | TCP, Unix and TLS listeners, connection tasks, entry points and cleanup | Who owns the process/socket/cache lifetime? Where do admission, handshake and shutdown happen? |
| [Protocol and traces](TRAINING-PROTOCOL-FUNCTIONS.md) | Shared request parser/dispatcher, response encoding, protocol counters, ThreadLocal trace and diagnostic JSON | Which limits apply before dispatch? What does a correlated trace actually measure? |
| [Offline bulk publication](BULK-PUBLICATION-FUNCTIONS.md) | BulkArtifactWriter, EmptyStoreBulkLoader, Python pipe owner/adapter/diagnostic, bounded sorted staging, inventory verification and manifest commit | What acknowledges durability? What can already be committed after an exception? Which population timing excludes readback? |
| [Python connections and retries](PYTHON-CLIENT-FUNCTIONS.md) | Low-level client socket ownership, cancellation, exchange/retry, correlated timing | What can replay a request? When can a client reconnect, and what does cancellation stop? |
| [Python values and batches](PYTHON-CACHE-VALUE-FUNCTIONS.md) | Client identity/value APIs, packed batch views, references, presence, diagnostics | Which results preserve order? Where are bytes copied and malformed metadata checked? |
| [Python mapped views and tensors](PYTHON-MAPPING-FUNCTIONS.md) | Both mapped registries, CacheView, CPU array/tensor wrappers, pinned copies and transfer helper | Which import selects which registry? Who retains storage, and when is a copy required? |
| [Python loading, workers and prefetch](PYTHON-PIPELINE-FUNCTIONS.md) | Reference loader, ordered single-producer queue, spawned benchmark workers and selected preparation routing | Which queue is bounded? Who cancels pending work, closes owners and aggregates worker counters? |
| [Python store adapters and resources](PYTHON-STORE-ADAPTER-FUNCTIONS.md) | JavaArtifactStore, comparison mmap index/journal/store and Linux process observations | Which reads copy? Can part of a failed publication already exist? What do resource counters measure? |
| [Python transform cache and codecs](PYTHON-TRANSFORM-FUNCTIONS.md) | AetherTransformCache, source/transform identities, batch reuse/planning, bytes/array/tensor/dictionary codecs | Which work is cached? What invalidates identity, what is copied, and what happens after failure? |
| [Python dataset integration](PYTHON-DATASET-FUNCTIONS.md) | AetherDataset, selected transform, ordered fetch, population, merge and random augmentation | Where does deterministic caching end? How do population and normal fetching differ? |
| [Python ML lifecycle and operations](PYTHON-LIFECYCLE-FUNCTIONS.md) | AetherConfig, capability checks, client creation, namespace normalization, metrics export, trusted Python CLI, exception types | Who owns a connection? Which configuration values are validated, and what does a CLI invocation actually execute? |
| [Python PyTorch and MONAI adapters](PYTHON-FRAMEWORK-FUNCTIONS.md) | Loader facade, worker init, inherited Torch dataset, dictionary dataset, MONAI/DICOM/NIfTI identity helpers | What is handled by the framework? Which descriptor changes invalidate reusable artifacts? |
| [Filesystem artifact publication](PYTHON-PROVENANCE-STORE-FUNCTIONS.md) | Separate aetherml owner, identity, blob/node/cache-pointer commits, lookups, atomic-file helpers and trusted paths | Which files are authoritative? What survives failure, and which receipts can differ from persisted metadata? |
| [Filesystem datasets and lineage](PYTHON-PROVENANCE-WORKFLOW-FUNCTIONS.md) | CachedTransform, dataset/loader factory, lineage traversal, snapshots and experiment bookkeeping | Is None a hit? Does restore roll back a database? What does direct snapshot verification omit? |
| [Filesystem validation and diagnostics](PYTHON-PROVENANCE-DIAGNOSTIC-FUNCTIONS.md) | Recovery/report properties, store scans, cleanup, latency/storage summaries and environment probes | What can validation report or raise? What does recovery delete, and what do metric names really measure? |

These cover the Java cache owner/listeners/wire handlers and Python low-level
client/view contracts, higher-level deterministic transforms and indexable dataset
adapters, lifecycle/configuration helpers and framework facades, plus the Java
offline bulk publication path and Python population caller, lower-level loading,
prefetch/worker orchestration and benchmark storage/resource adapters. The three
filesystem provenance references also cover all explicit declarations in ml.py,
keeping that separate implementation's authority and limitations distinct.
Remaining workload helpers and research drivers still need individual-function
expansion. Cache diagnostics are not
uniform across byte-array, packed-batch, reference, and mapping APIs.

## H2 Research Protocol and Evidence

| Reference | Source coverage | Main questions |
| --- | --- | --- |
| [Protocol, pilot audit and freeze](H2-PROTOCOL-FUNCTIONS.md) | All 13 functions in h2_protocol.py: configuration, balanced assignment, audited pilot, runtime, separate candidate/harness identities and frozen bundle | What is preserved from the pilot? What does a receipt bind, and what does it not certify? |
| [Execution, worker and input binding](H2-EXECUTION-FUNCTIONS.md) | All 24 functions in h2_confirmatory.py, h2_worker.py and h2_input_paths.py, including nested callbacks | What stays alive through V0-V4? Which failures retry a whole block, and what prevents unsafe resume or edited evidence? |
| [Amended session controller](H2-SESSION-FUNCTIONS.md) | All eight functions in h2_sessions.py | How are original receipts preserved across hosts, and why are session 3, resume and final aggregation separate work? |
| [DALI workload and comparisons](DALI-COMPARISON-FUNCTIONS.md) | All 26 declarations across workload adapter, comparison, analysis and smoke files | How are canonical GPU artifacts compared with cached training, and which timing/parity claims are actually checked? |
| [Bulk layout regression checks](BULK-LAYOUT-FUNCTIONS.md) | All 6 declarations in the post-population layout helper | What do sampled RSS, warm packed reads and incremental readback actually verify outside V0 timing? |
| [Dataset preparation and manifests](DATASET-PREPARATION-FUNCTIONS.md) | All 8 declarations across acquisition, vision preparation, membership evolution and validation | How are hashed inputs and overlapping versions created, and what does preflight leave unchecked? |
| [Transform evolution checks](TRANSFORM-EVOLUTION-FUNCTIONS.md) | All 3 declarations in the synthetic evolution checker | Which source/parameter changes invalidate final artifacts, and how is Java/mmap byte parity checked? |
| [Preflight and smoke utilities](PREFLIGHT-UTILITY-FUNCTIONS.md) | All 4 declarations across checksums, GPU validation and CPU artifact smoke | What does each preflight actually check, and why is CPU smoke not paper timing? |
| [Concurrency and fault drivers](SYSTEM-DRIVER-FUNCTIONS.md) | All 10 declarations across storage scaling and process-crash drivers | How are clients synchronized, writers killed, results verified and interrupted trials preserved? |
| [Cache comparisons and systems exports](SYSTEM-ANALYSIS-FUNCTIONS.md) | All 9 declarations across frozen-build comparison and systems CSV/figure export | Which timing, grouping, byte-scope and completeness limits apply to these summaries? |
| [Campaign audit and preservation](CAMPAIGN-AUDIT-FUNCTIONS.md) | All 5 declarations across submission auditing, preservation and archived V1 validation | How are integrity, coverage, supported claims and human submission approval kept distinct? |
| [Build and documentation utilities](BUILD-UTILITY-FUNCTIONS.md) | All 13 declarations across static rendering, Maven checking, paper building and Studio argument adaptation | How do tooling inputs become pages, staged-artifact checks, draft PDFs and forwarded CLI arguments? |
| [Root build architecture](ROOT-BUILD-ARCHITECTURE.md) | Root/settings actions, schema proposal helper, six training-cache build tasks and digest helper | How are projects included, releases staged, schema tasks wired and runtime receipts produced; which verification tasks are placeholders? |
| [Module build topology](MODULE-BUILD-ARCHITECTURE.md) | All 49 module and two example build files | Which dependencies are exported, implementation-only or processor/test-scoped; what do application and packaging actions configure? |
| [Example applications](EXAMPLE-APPLICATION-FUNCTIONS.md) | Both examples' complete main source trees | How do notes persistence, Swing ownership, social CRUD and application-side joins work, and where do sequential guards stop? |
| [Flush diagnostics](FLUSH-DIAGNOSTIC-FUNCTIONS.md) | Complete FlushDiagnostics source, including nested types | How do collectors follow writes, charge stage intervals and link flush/compaction/table-finish evidence? |
| [Analysis and figures](H2-ANALYSIS-FUNCTIONS.md) | All five functions in h2_analysis.py, including the figure writer | Which checkpoint is primary? How are ratios, ties, break-even and uncertainty calculated from validated evidence? |

These cover the separately deployable H2 harness, not all imported campaign,
longitudinal service, preprocessing or training implementations. The archived
execution gate and statistical helpers have different responsibilities. No guide
is a live job-status report, authorization to pool hosts, or evidence of superiority.
The shared infrastructure references below explain the imported lifecycle and
evidence mechanisms. Original workload functions remain to expand.

## Shared Research Infrastructure

| Reference | Source coverage | Main questions |
| --- | --- | --- |
| [Campaign identity and evidence](RESEARCH-CAMPAIGN-FUNCTIONS.md) | All nine functions in system_campaign.py and evidence.py | What is kernel ownership? Which identity fields prevent resume drift, and how does training-matrix evidence differ from H2? |
| [Build, environment and process ownership](RESEARCH-PROCESS-FUNCTIONS.md) | All nine functions in paper_common.py, including the stdout reader | What binds source and executable bytes? Which probes are descriptive? What does daemon exit drain and clean up? |
| [Stage checkpoints and persistent service](RESEARCH-LIFECYCLE-FUNCTIONS.md) | All 16 functions in longitudinal_state.py and persistent_service.py | When can a closed stage resume? Why cannot a live persistent block resume partially, and which timers/leases/receipts belong to each policy? |
| [Dataset version manifests](RESEARCH-MANIFEST-FUNCTIONS.md) | All four functions in longitudinal_manifests.py and the module-level command | How are unchanged prefixes frozen? Which declared identities are checked, and what does receipt verification trust? |
| [MONAI comparison adapters](RESEARCH-ADAPTER-FUNCTIONS.md) | All 16 functions in monai_comparison.py, including the source callback | Which values are cached? How does the older two-version endpoint differ from full lifecycle cost? |
| [Longitudinal stage worker](LONGITUDINAL-WORKER-FUNCTIONS.md) | All eight functions in longitudinal_worker.py | Which versions train, how does the actual model work, and which phases/resource observations enter the endpoint? |
| [Longitudinal pilot and analysis](LONGITUDINAL-RUNNER-FUNCTIONS.md) | All 11 functions in longitudinal_comparison.py and longitudinal_analyze.py | How are stage/pair receipts assembled and reused? What do exploratory ratios and first-observed break-even mean? |

These describe current shared helpers, including trust and failure boundaries,
not a claim that every campaign uses identical checks. Closed-store snapshots,
one-file replacement, immutable campaign identity and full-block reuse are distinct
mechanisms. Remaining research drivers and original training workloads still need
their own per-function references.

## Main Benchmark Workload

Population tooling has its own [complete 18-function reference](POPULATION-PROFILE-FUNCTIONS.md),
covering isolated diagnostic arms, inclusive instrumentation and excluded readback.
The [cache request and JFR reference](CACHE-PROFILE-FUNCTIONS.md) covers seven
declarations across synthetic tracing-overhead and recording drivers.
The [JFR analyzer reference](JFR-ANALYSIS-FUNCTIONS.md) covers six declarations
for interval/thread attribution, allocation estimates and diagnostic summaries.
The [bulk JFR and compaction drivers](BULK-DIAGNOSTIC-DRIVERS.md) explain three
declarations for recording orchestration and durable reopen checks.
The [bulk verification campaign](VERIFICATION-CAMPAIGN-FUNCTIONS.md) covers eight
declarations for frozen baselines, correctness receipts and separate timing gates.
The [hit-path reference](HIT-PATH-FUNCTIONS.md) covers all 19 declarations in the
read-only Java/RPC/mmap/CPU-input diagnostic, including prefetch and evidence gates.
The [experiment ownership reference](EXPERIMENT-OWNERSHIP-FUNCTIONS.md) covers four
declarations for temporary-store capacity/cleanup, output locking and metadata freeze.

## Remote Protocol Foundations

The [RPC codec reference](RPC-FRAME-FUNCTIONS.md) covers all 38 explicit
declarations across ten implementation files: wire values, decoding, HELLO,
fragmentation, assembly, stream allocation and protocol exceptions.
The [RPC API reference](RPC-API-FUNCTIONS.md) covers 33 declarations across all
17 API files, separating interface promises from implemented record validation,
byte ownership and admission/status mapping. Concrete transport references follow
below; application/client integration beyond this RPC package is a separate scope.

The [transport limits and identity reference](RPC-TRANSPORT-LIMITS.md) covers 11
declarations in four support files: configuration conversion, admission policies,
process identity and standalone byte credits/state vocabulary. The concrete
PlaintextDevelopmentRpc implementation remains outside that partition.
The [development server reference](RPC-SERVER-FUNCTIONS.md) covers 32 of its 54
declarations: factories, socket reads/writes, listener, assembly/dispatch,
responder, cancellation and connection tracking. The [client reference](RPC-CLIENT-FUNCTIONS.md)
covers the other 22 declarations, including retries, deadline scheduling, response
cleanup and admission snapshots. Together with support files, RPC transport has
65/65 explicit declarations documented; codec has 38/38 and API 33/33.
The [remote routing reference](REMOTE-ROUTING-FUNCTIONS.md) covers 19 declarations
for endpoint snapshots, bounded inflight pooling, retry decisions and principal
metadata. The [facade reference](REMOTE-FACADE-FUNCTIONS.md) adds the other 27
declarations for read/write/scan attempts, typed adaptation and results, completing
46/46 coverage in aether-client. The [client protocol contract reference](CLIENT-PROTOCOL-FUNCTIONS.md)
adds all 22 explicit declarations across the 11 client API files, covering byte
ownership, command IDs, scan bounds, outcome validation and operation constants.
The [client message codec reference](CLIENT-CODEC-FUNCTIONS.md) covers all 27
declarations in four files, including wire offsets, integrity coverage, padding,
UTF-8 and allocation limits. The remote client, its API and codecs now have
complete explicit-declaration references; distributed server integration is a
separate responsibility.

| Reference | Source coverage | Main questions |
| --- | --- | --- |
| [Sources, preparation and payloads](BENCHMARK-DATA-FUNCTIONS.md) | 27 explicit functions in benchmark_gpu_segmentation.py | Which files/digests are trusted? How are masks and image precision changed, what does decoding reject, and which descriptive labels are not complete identity? |
| [Model, training and devices](BENCHMARK-TRAINING-FUNCTIONS.md) | 25 explicit functions, including four nested model methods | What model actually runs, what does warmup change, and what is included in step/backend timing? |
| [Backend ownership and population](BENCHMARK-BACKEND-FUNCTIONS.md) | All 24 BackendContext methods and two helpers | Who opens/resets/deletes caches, how does reuse work, and what do population/counter reports include? |
| [Utilization and process metrics](BENCHMARK-METRICS-FUNCTIONS.md) | Seven sampler methods and eight parsing/process helpers | Which devices/intervals are observed, what happens when probes fail, and which reported units need caution? |
| [Entry points, run control and validity](BENCHMARK-RUNNER-FUNCTIONS.md) | 13 explicit declarations, including three lazy-reference methods | Which modes train, what is isolated, what is validated, and what does a passed flag actually mean? |
| [Lifecycle, cache accounting and comparisons](BENCHMARK-ACCOUNTING-FUNCTIONS.md) | 18 explicit declarations | How are totals, throughput, counts, outcome signals and projected crossover calculated? |
| [Aggregation and statistics](BENCHMARK-AGGREGATION-FUNCTIONS.md) | 12 explicit declarations | Which fields are representative, summed or distributed, and what do the statistical summaries prove? |
| [Loading and prefetch routing](PYTHON-PIPELINE-FUNCTIONS.md) | prepared_batches and its prepare callback, alongside separate loading/prefetch modules | When does preparation happen and who owns queued work? |

These cover all 138 distinct explicit declarations in the current main benchmark.
Joint qualified AST coverage checks detect omissions and duplicates across the
partitions. Imported helpers and anonymous callbacks have separate boundaries.
This count excludes anonymous lambdas and imported helpers;
it is not repository-wide completion or a runtime correctness percentage.

## Encryption Foundations

The [crypto reference](CRYPTO-FUNCTIONS.md) covers all **25 explicit declarations**
in seven files: AES-GCM operations, little-endian envelopes, backup AAD/metadata,
local key wrapping and destruction boundaries. It distinguishes metadata from
authenticated inputs and primitive availability from actual engine integration.
The [security reference](SECURITY-FUNCTIONS.md) adds **49 declarations across all
22 API/core files**, covering RBAC, audit storage/formatting, redaction and node
certificate identity matching. These do not imply automatic endpoint enforcement,
trust-chain validation or a concrete key-provider service.

## Replication Foundations

The [replication contract reference](REPLICATION-CONTRACT-FUNCTIONS.md) covers
**34 explicit declarations across eight files**: the complete replication API,
sequence planner, and applied-state value. It distinguishes log position, logical
mutation sequence, durable index, and applied progress, and explains validation,
hash ownership, equality, and the responsibilities left to concrete storage and
consensus integration.
The [replicated format reference](REPLICATION-FORMAT-FUNCTIONS.md) covers all
**43 declarations in five codec/format files**: exact byte layouts, CRC/hash
coverage, ownership, parsing checks and allocation/validation asymmetries.
The [concrete store reference](REPLICATED-STORE-FUNCTIONS.md) adds all **43
declarations** in ReplicatedLogStoreV1: process ownership, prevalidation, append,
force, rotation, recovery/repair, guarded suffix truncation and failure lifecycle.
Together these cover every explicit declaration in aether-replicated-log.
The [Raft core reference](RAFT-CORE-FUNCTIONS.md) covers **25 declarations across
all 11 API/core files**: vote record validation, reason/role vocabulary, tip
freshness, follower progress, strict-majority commit tracking and settings.
These modules supply helpers, not a complete election/AppendEntries/apply loop.
The [Raft storage reference](RAFT-STORAGE-FUNCTIONS.md) covers all **17 declarations
in three files**: big-endian vote messages, little-endian state slots, integrity
coverage, parser acceptance and persistent-state value validation. No slot manager
or complete durable election loop is supplied by those codecs.
The [cluster membership reference](CLUSTER-MEMBERSHIP-FUNCTIONS.md) covers **73
declarations across all 12 API/core files**: endpoint canonicalization, identity
and member validation, stable/joint configurations and independent-majority
calculations. These do not provide a membership-change service.
The [cluster codec reference](CLUSTER-CODEC-FUNCTIONS.md) covers **53 declarations
across all four codec files**: exact identity/stable/joint layouts, nested member
records, SHA/CRC coverage, parser behavior and encode/decode asymmetries.
Broader application integration remains separate work.

## Verification Scope

The [workbench workspace reference](WORKBENCH-WORKSPACE-FUNCTIONS.md) covers 38
declarations across database editing, table-model and two local inspector files.
The [universal typed-value reference](WORKBENCH-TYPED-VALUE-FUNCTIONS.md) adds all
20 declarations in the schema-independent editor. The [record dialog reference](WORKBENCH-RECORD-DIALOG-FUNCTIONS.md)
covers all 14 dialog/editor declarations, including syntax selection and text
acceptance versus downstream encoding. The [window/action reference](WORKBENCH-WINDOW-FUNCTIONS.md)
adds 14 declarations for startup, ownership, toolbar dispatch and grouped rendering.
These four references span all 86 explicit workbench declarations across seven
implementation files; desktop interaction coverage remains limited.

The [Gradle plugin reference](GRADLE-PLUGIN-FUNCTIONS.md) covers all five consumer
plugin methods, schema-task actions and seven shared build conventions, including
publishing helpers. Root/module-specific build actions remain separate scope.

The [release certification reference](RELEASE-CERTIFICATION-FUNCTIONS.md) covers
22 declarations across all nine release implementation files: evidence/blocker
validation, immutable manifests, properties parsing, readiness rules and JSON.
It distinguishes operator-declared evidence from independently verified artifacts.

The [Java benchmark runner reference](JAVA-BENCHMARK-RUNNER-FUNCTIONS.md) covers
41 declarations across six profile/runner files, including load/read timing,
child-process halts, report provenance, cache controls and metadata-only profiles.
This is separate from the Python ML benchmark and lifecycle references above.

The [Java benchmark evidence reference](JAVA-BENCHMARK-EVIDENCE-FUNCTIONS.md)
covers 27 declarations across eight report/artifact/baseline files. It explains
immutable validation, deterministic output, escaping limits and synthetic baseline
context. The [comparison and gate reference](JAVA-BENCHMARK-COMPARISON-FUNCTIONS.md)
adds all 14 declarations across the remaining eight comparison/manifest files,
including thresholds, median selection and limits of semantic equivalence checks.

`scripts/InventoryJavaFunctions.java` uses compiler syntax trees to inventory
explicit method and constructor declarations, including private/nested helpers.
`scripts/tests/test_contributor_docs.py` checks that inventoried names occur in
their selected reference pages and that generated HTML matches Markdown.

Name occurrence is an omission check, not proof that each overload's behavior is
accurate. Implicit record accessors and constructors may need manual explanation;
compiler-tree inventory does not substitute for reading implementations and tests.
Manual source review establishes contracts. Website checks establish links and
layout. None of those checks certifies runtime correctness or a production release.
