# Offline Bulk Publication Functions

[Function index](FUNCTION-INDEX.md) | [Training cache](TRAINING-CACHE-AND-PYTHON.md) | [Manifest versions](MANIFEST-VERSION-FUNCTIONS.md)

This reference covers every explicit constructor and method in
[BulkArtifactWriter.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/BulkArtifactWriter.java)
and [EmptyStoreBulkLoader.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/EmptyStoreBulkLoader.java).
[bulk_population.py](../../scripts/bulk_population.py) adds the Python pipe owner,
publication adapter and diagnostic driver, including both nested helpers.
It describes the current experimental, offline, never-populated-store path,
not the normal online cache API or a replacement for its write coordinator.
The artifact wrapper has eight declarations; the engine loader has seven; the
Python module has twelve. These are 27 explicit functions/constructors in total.

## Architecture and Ownership

`BulkArtifactWriter` converts immutable cache entries into the same inline
integrity envelope used by the online cache. `EmptyStoreBulkLoader` owns the
exclusive database lock, recovered manifest, sorted staging map, unpublished
SSTable files, and final manifest edit. The ordinary daemon must not hold that
database open while the loader runs. Close the loader before opening the daemon,
even after `finish()` succeeds: finalization does not release its lock.

```text
CacheEntry values and physical storage keys
  -> owned payload copy, SHA-256, inline envelope and CRC32C
  -> exclusive loader: cloned key/value pairs in an unsigned-key TreeMap
  -> sorted partitions: SSTable build, file force, atomic rename
  -> new empty WAL header: file force, directory barrier
  -> VersionSet: candidate version, full inventory verification
  -> one manifest record: encode, append, force, publish current version
  -> obsolete WAL cleanup and directory barrier
  -> successful receipt, then close the offline owner
  -> ordinary TrainingCache/daemon reopen and online updates
```

The loader avoids bulk WAL **payload** duplication and native memtable insertion.
It does not eliminate WAL files, checksums, manifest durability, input copies,
or database recovery. Its whole input is retained in a bounded in-memory map;
the *verifier* streams block entries, but this loader is not an unbounded streaming
sorter. The Python subprocess owner and population driver below connect this
Java path to an exploratory diagnostic, not the full H2 lifecycle campaign.

## Artifact Writer Functions

| Declaration | Behavior, ownership, and failure boundary |
| --- | --- |
| `BulkArtifactWriter(Path directory)` | Delegates to the target-taking constructor with the loader's default 32 MiB table target. Does not inspect or preload Python artifacts itself. |
| `BulkArtifactWriter(Path directory, long targetSstableBytes)` | Creates an engine loader with the 512 MiB encoded-byte staging budget and supplied table target. This prototype explicitly uses the development security profile and disables engine disk-pressure policy; do not treat that as a production recommendation. If bulk JFR is enabled, starts a population event **after** loader bootstrap, not before database creation/recovery. |
| `BulkArtifactWriter.addAll(Iterable<CacheEntry> entries)` | Synchronized. Rejects a previously failed wrapper, then processes entries one at a time: copy `entry.value()`, enforce inline size, hash payload, encode envelope, and admit the physical key/value to the loader. Increments artifact/payload counters only after successful admission. Runtime failures, including an iterator failure or duplicate key rejected by the loader, poison this wrapper and rethrow. Earlier admitted entries remain staged, not published. |
| `BulkArtifactWriter.addTiming(String stage, long started)` | Private helper merges elapsed monotonic nanoseconds into the stage's accumulated total. It does not create a nested trace, synchronize independently, or subtract instrumentation overhead. Calls are inside the synchronized admission method. |
| `BulkArtifactWriter.finish()` | Synchronized. Rejects wrapper admission failure, calls the loader's one-shot commit, marks a population event successful and ends it, then returns `status=committed`, counts, admission timings and the nested storage receipt. `sha256Calls` is the admitted-artifact count, not an independently sampled hash-call counter. An engine commit exception propagates; the loader's finalized/failed state prevents an unsafe retry even though this method does not set the wrapper's `failed` field. |
| `BulkArtifactWriter.finishEvent()` | Private, no-op when there is no event. Copies admitted counts, ends/commits the JFR event, then clears its reference. Does not publish database state or assert durability. Unless `finish()` set success, the event's success field stays false. |
| `BulkArtifactWriter.close()` | Closes the engine owner and ends the event in a `finally` block, including on close failure. Does **not** call `finish()` or acknowledge staged artifacts. Unlike admission/finalization, this wrapper method is not synchronized; callers should serialize close with use, not infer unrestricted thread safety from the other two methods. |
| `BulkArtifactWriter.main(String[] args)` | Offline subprocess entry point. Accepts a store path and optional decimal table-target byte count. Opens writer/stdin, emits `READY`, reads framed batches, decodes entries, admits them, and emits `STAGED n`. Only a zero-length frame calls `finish()` and prints its JSON receipt. Any failure or EOF exits through resource cleanup; EOF is not a commit request. |

### Admission Copies and Inline Boundary

[CacheEntry](TRAINING-IDENTITY-FUNCTIONS.md) already owns a defensive copy;
`value()` makes another. Hashing reads that payload, the inline envelope owns
its encoded bytes, and `loader.add(...)` clones both the physical storage key
and envelope for staging. Later table encoding has its own copies. The removal
of verification-side value materialization does not remove these admission copies.

The wrapper rejects payloads **greater than** 256 KiB. Exactly 256 KiB is admitted
inline here, whereas the online cache's automatic storage selector uses the segment
path at **greater than or equal to** that threshold. Bulk does not use the segment
store, eviction index, online batch coordinator, or online capacity-eviction policy.
The final ordinary cache reconstructs its own state on reopen. See
[cache operations](TRAINING-CACHE-FUNCTIONS.md) for those distinct contracts.

The `INTEGRITY` JFR phase spans copying, hashing, envelope construction, loader
admission, and iteration overhead, not just checksum work. Its records/bytes deltas
are filled only after the loop completes normally; an interrupted phase is not a
complete count of every successful preceding entry. `REQUEST_DECODE` is measured
separately by the subprocess entry point.

### Local Pipe, Not Daemon RPC

The frame length is a signed, big-endian Java `int`. Nonzero lengths must be
between 6 bytes and 64 MiB inclusive; the process allocates that frame and reads
it fully before calling
[TrainingCacheProtocol.decodeBulkEntries](TRAINING-PROTOCOL-FUNCTIONS.md).
That parser supplies the batch's additional wire and entry checks. The 64 MiB
frame limit is not the aggregate staging budget or an online protocol opcode.

`READY` means the offline owner opened. `STAGED n` means that batch was admitted
to volatile staging. Neither is a durable artifact acknowledgement. Zero commits;
truncated length/payload reads throw rather than silently finalizing. A subprocess
that dies after publication can leave all artifacts committed without delivering
the JSON acknowledgement. The pipe is a local trusted-process interface, not an
authenticated/TLS network service.

## Python Pipe Owner and Publication Adapter

`BulkPipeWriter` owns one Java child, its stdin/stdout streams, a stderr log,
stdout reader thread and response queue. `BulkPublicationClient` reuses the
low-level client's PUT_MANY encoding and the diagnostic parent's publication
batch splitting, but overrides transport to send those bodies to the pipe.
It does not establish an online daemon connection for this bulk publication.

| Declaration | Behavior, ownership, and failure boundary |
| --- | --- |
| `BulkPipeWriter.__init__(store, *, crash_point=None, timeout=120, target_bytes=32 * 1024 ** 2, jfr_file=None, jfr_settings="profile")` | Stores the path/options, initializes committed/closed flags and an unbounded response queue. Does not start Java, validate the target/timeout, open a database or allocate the engine staging buffer. |
| `BulkPipeWriter.__enter__()` | Creates the store parent and obtains the candidate Java classpath. Optionally reserves a fresh JFR file/repository and adds recording flags; optionally selects the test-only crash probe/classpath. Opens a sibling stderr log and starts Java with preview enabled, pipes and the table-target argument, hidden on Windows. Starts the stdout reader, waits for exact `READY`, and returns itself. Failures inside the launch/readiness try invoke exit cleanup and rethrow. JFR-directory preparation occurs before that try and is not rolled back. |
| `BulkPipeWriter.__enter__.reader()` | Nested daemon-thread function iterates child stdout. In JFR mode, redirects recognized JFR startup log lines to the stderr file so they do not masquerade as protocol responses. Queues every other line, then a `None` sentinel on normal EOF. It does not parse JSON or match response/request IDs. A reader exception is not explicitly caught to guarantee a sentinel. |
| `BulkPipeWriter.line()` | Waits for one queue item using the configured timeout; wraps queue exhaustion as TimeoutError and EOF sentinel as an acknowledgement failure instructing the caller to retain/inspect the store. Strips response whitespace. It does not independently kill a process, recover the database or certify a queued acknowledgement. |
| `BulkPipeWriter.exchange(body)` | Rejects an already acknowledged commit. Starts a timer that can kill the child on timeout, writes and flushes an unsigned big-endian 32-bit byte length plus body, then reads one response. Cancels and joins the timer in `finally`. A zero-byte body sends the Java commit frame. There is no independent 64 MiB check here, no closed-state guard, no reconnection or automatic replay; Java enforces its frame limit. |
| `BulkPipeWriter.stage(body)` | Reads the expected entry count from the body at offset 2, then requires exact `STAGED count`. Returns an empty byte payload to satisfy the inherited client's successful-response decoder. Bad acknowledgement raises; a short body can fail before exchange. This method does not validate all batch entries or set `committed`. |
| `BulkPipeWriter.finish()` | Exchanges an empty body, parses the response as JSON, requires `status=committed`, sets the local committed flag, and returns the report. It checks the status, not every nested receipt field; the driver supplies additional cardinality checks. Lost/malformed acknowledgement can leave the local flag false even if Java already committed. |
| `BulkPipeWriter.__exit__(exc_type, exc, traceback)` | Idempotent once marked closed. Closes stdin to send EOF without auto-commit, waits up to 15 seconds, kills/waits again on timeout, joins the reader for up to 5 seconds, and closes output/log handles. Raises for nonzero child exit only when no outer exception exists and a commit was acknowledged. An uncommitted crashed child does not by itself raise from this method. Stores/logs/JFR files are retained; partial cleanup failures are not aggregated and can interrupt later cleanup steps. |
| `BulkPublicationClient.__init__(writer, put_batch=16)` | Holds the pipe owner and initializes PublicationClient with the requested publication chunk size and tracing disabled. The inherited client machinery owns encoding/metrics; the adapter does not own the writer's lifetime or commit it automatically. |
| `BulkPublicationClient._exchange(body, *args, **kwargs)` | Requires opcode 6 in body byte 1, delegates to `writer.stage`, then increments successful request/opcode counters. Returns its empty success payload. Other opcodes fail; short bodies can raise IndexError. Extra exchange arguments are ignored. Counter increments happen after stage acknowledgement, not after durable publication. |

The pipe is serial: response queue entries have no correlation identifier, and
write/read ownership is not protected by an exchange lock. Do not share one writer
across concurrent publishers. The parent PublicationClient chunks each supplied
publication batch; a `put_batch` larger than the driver's 16-item fetch batch does
not merge multiple calls into one larger request. Its reported protocol/publication
counts here describe staged pipe batches, not network RPCs or committed transactions.

## Python Population Diagnostic

| Declaration | Behavior and timing boundary |
| --- | --- |
| `run_bulk_case(request)` | Requires a fresh nonexistent store, prepares OCT5K source arguments with trusted manifest hashes, loads sources, wraps canonical preprocessing/codec timing and launches disk sampling. Times writer startup separately, then preprocesses/encodes and stages source batches of 16, explicitly finishes and checks admitted counts plus zero bulk WAL payload/memtable insertion. Times offline-owner close, verifies an optional JFR dump exists, stops population sampling, and validates the artifacts in a genuinely new ordinary daemon. Returns the diagnostic receipt; restores the preprocessing hook and attempts sampler/owner cleanup in `finally`. Does not create a model or train epochs. |
| `run_bulk_case.capture(*a, **kw)` | Nested wrapper calls the saved preprocessing-with-timing function, adds its successful counters into a Counter, and returns the same value/counters. It does not swallow preprocessing failures. The population counter snapshot is taken before ordinary-reader validation, even though the hook remains installed until the driver's final cleanup. |

The request supplies the manifest, sample limit, image size, expected prepared
tensor hash, case publication batch and optional table/JFR/layout settings. This
is a trusted harness request, not a public validated configuration parser. The
preprocessing arguments fix four passes, batch 16 and prefetch 0 in `workload_args`.
Using trusted manifest hashes assumes prior input validation; this function does
not independently certify all source bytes before its timed work.

### Measured Work and Exclusions

Source loading, argument/transform/codec construction and initial sampler setup
precede the startup timer. `startup` includes pipe/JVM setup through READY.
`population` includes preprocessing, encoding, publication batches and the synchronous
Java commit. `close` includes offline-owner exit. The diagnostic reports
`totalMs = startup + population + close`, with `quiescence=0` because the offline
writer has no queued online flush/compaction work. That zero does **not** exclude
table, WAL-header or manifest forces: they are inside population.

Ordinary-reader reopen/readback/drain and optional layout regression have their
own excluded `validationMs`. The driver requires the prepared tensor digest to
match `referenceHash`, zero validation-transform calls (no cache misses), and
the reopened cache's entry count to equal the loaded source count. It closes that
dataset in `finally`. Optional regression checks exercise warm reads and later
online updates; they do not become measured initial population.

The preprocessing hook is module-global, so concurrent diagnostic invocations
in one Python interpreter would interfere; use isolated workers. Population disk
and optional memory sampling stop before reader validation, but Python process
usage spans later validation too. `sourceLoadAndPreprocessMs` is derived from the
timed transform's elapsed calls, **not** an independently timed source-loading
phase. Java process usage is reported as null here. Sampled memory/disk counters
are not exact hardware write amplification or allocation totals. The result's
`model=None` and `trainingSampleRequests=0` deliberately distinguish this V0-only
diagnostic from a persistent-daemon longitudinal ML experiment.

## Engine Loader Functions

| Declaration | Behavior, ownership, and failure boundary |
| --- | --- |
| `EmptyStoreBulkLoader(Path directory, AetherConfiguration configuration)` | Delegates with defaults: 512 MiB encoded-byte budget and 32 MiB table target. The caller supplies engine bootstrap configuration; the generic loader does not itself set the artifact wrapper's security/pressure choices. |
| `EmptyStoreBulkLoader(Path directory, AetherConfiguration configuration, long maxBytes, long tableBytes)` | Rejects nonpositive limits or a target exceeding the budget. If DB-IDENTITY is absent, opens/closes the canonical engine to bootstrap it. Validates the absolute managed root, acquires its OS database lock, decodes identity, recovers VersionSet and rechecks the empty-store invariants under that lock. On a failure inside recovery/validation, closes the opened manifest and lock, adding cleanup failures as suppressed exceptions. Bootstrap may already have created canonical empty-store files. |
| `EmptyStoreBulkLoader.add(byte[] key, byte[] value)` | Synchronized. Requires staging state, rejects null arrays/oversized keys, checks overflow-safe encoded-byte budget and the 100,000-entry cap, rejects duplicate unsigned-byte keys, then clones both arrays into the sorted map. Empty keys are not rejected here. It does not apply WriteBatch's independent maximum value size. Runtime admission errors poison this loader; state-guard errors occur before that catch. |
| `EmptyStoreBulkLoader.finish()` | Synchronized, one-shot build/verify/publication operation described below. An empty transaction throws **before** finalizing, so adding entries remains possible. With entries, marks finalized before I/O. Success returns diagnostics but keeps the owner/lock open. A failure inside the commit body marks failed and wraps its cause in an IOException requiring reopen/recovery. The staging map is cleared in `finally`, whether publication succeeded or not. |
| `EmptyStoreBulkLoader.requireStaging()` | Private guard rejects a closed, failed, or finalized loader. Does not recover files, reserve resources, or distinguish a published commit from a failed prepublication attempt. |
| `EmptyStoreBulkLoader.syncDirectory(Path root)` | Private helper opens the directory read-only and forces it. Ignores AccessDeniedException only when the OS name starts with Windows; other errors propagate. This is an explicit platform accommodation, not proof of identical directory durability on every filesystem. |
| `EmptyStoreBulkLoader.close()` | Synchronized, idempotent after the closed flag is set. Marks closed, clears staging, closes VersionSet, and releases the lock in `finally`. Does not commit, recursively remove outputs, or retry a failed close. A lock-close failure can replace a manifest-close failure because this path does not combine them as suppressed exceptions. |

### Eligibility and Memory Accounting

The recovered version must have no SSTables, no assigned sequence, no persisted
sequence watermark, and a minimum WAL whose size is exactly its header block.
This means a **never-populated** database, not one whose keys were subsequently
deleted, and not a merge into an existing cache. Recovery is still used to establish
the authoritative inventory and remove eligible orphan output.

The TreeMap compares keys using `Arrays.compareUnsigned`; distinct caller arrays
with identical bytes are duplicate keys, even when their values match. The first
100,000 entries are allowed; admission of a 100,001st is rejected. The staging budget
charges key bytes plus encoded value bytes, not TreeMap nodes, array headers,
intermediate copies, JFR objects or builder memory. `peakBufferedBytes` reports that
monotonically accumulated accounting value, **not** measured peak JVM heap or RSS.
An out-of-memory Error is not covered by `add()`'s RuntimeException catch.

### What Finish Actually Publishes

1. Iterate the map in unsigned-key order. Assign one increasing sequence per key
   and value type 1; these sequences follow sorted order, not incoming batch order.
   Feed consecutive entries to a builder until its accumulated key/value bytes
   reach the target. Every partition has at least one entry. The target is a soft
   partition threshold, not a hard SSTable file-size cap: it can overshoot by one
   entry and excludes table framing, index and filter overhead.
2. Finish the temporary SSTable using
   [BulkInstallSupport.finishUnpublished](SSTABLE-BUILD-FUNCTIONS.md), which rechecks
   the empty inventory. The builder finishes and forces its file while deferring
   full inventory verification. Atomically rename it to its managed final name,
   then collect level-1 metadata and per-table finish timing. All partitions remain
   unpublished; creating a final-name file does not install it in the manifest.
3. Create the next WAL with `CREATE_NEW`, carrying database identity, previous WAL
   number and next sequence in its header. Write the header fully, force the file,
   and attempt the directory barrier. No staged values are written to that WAL.
4. Call `VersionSet.logAndApplyMeasured` with **one** DELTA containing all additions,
   no deletions, both sequence watermarks at the final staged sequence, updated next
   SSTable number and the new minimum WAL. Build the candidate version and verify
   every added table's path, file size and full contents before encoding any record.
   [SSTableVerifier](SSTABLE-READ-FUNCTIONS.md) is the authoritative streaming verifier;
   deferred construction does not mean skipping table/block checksums.
5. Encode, append and force the existing append-only manifest record. Only then
   assign VersionSet's current in-memory version to the candidate. This edit does
   not create/rename a temporary manifest or replace CURRENT. Its directory barriers
   belong to surrounding file publication/cleanup, not a fictitious manifest rename.
6. Delete the old empty WAL and attempt the final directory barrier. Return the
   storage receipt. Cleanup errors can occur **after** the manifest committed all
   artifacts; the caller must not interpret an exception as evidence of no commit.

## Failure and Recovery Boundaries

| Injection boundary | What has happened; what recovery evidence means |
| --- | --- |
| `bulk.after_table_force` | A temporary table was completed/forced; there is no bulk manifest edit yet. |
| `bulk.after_table_rename`, `bulk.after_table` | One or more final-name tables exist, but remain outside the authoritative inventory. |
| `bulk.before_manifest` | All tables and the new WAL header exist; the measured VersionSet call, including inventory verification, has not started. This hook is **not** after verification. |
| `bulk.before_verification`, `bulk.after_verification` | Inside VersionSet, immediately around the single inventory pass. After the second hook, content passed validation but the bulk record has not been appended. |
| `bulk.manifest.after_append` | The complete record has been written, but its force has not occurred. A process-death test may replay that intact record because the OS remains alive; that is not a power-loss durability guarantee. |
| `bulk.manifest.after_force` | The record was forced, before publishing VersionSet's in-memory candidate. Recovery can establish the committed inventory without a delivered acknowledgement. |
| `bulk.after_manifest` | The measured publication returned; obsolete-WAL cleanup and successful receipt delivery still remain. |

Failure does not trigger deletion of possible manifest dependencies. Reopen using
ordinary recovery and validate whether **all** staged identities are present before
deciding what to retry. Do not reuse this finalized loader or overwrite files based
on a missing receipt. An unfinalized close/EOF abandons volatile staging, not a
partially visible artifact subset.

## Receipts and JFR Interpretation

The wrapper's `admissionTimingsNs` accumulates owned payload copy, payload SHA-256,
envelope/CRC32C construction and sorted-buffer admission. The storage receipt has
`timingsNs` for build/finish/rename, empty-WAL preparation, measured manifest call
and obsolete-WAL cleanup; `manifest` splits candidate construction, inventory
verification, encoding, write, force and in-memory installation. These are elapsed
nanoseconds of differently nested scopes, **not additive disjoint buckets**.

`sstableFinishes` contains file numbers, actual sizes/counts and builder finish
traces. `verification` and `streamingVerification` come from the scoped verifier
trace; the expected successful path is one inventory call and a full verification
of each added table. The literal `verificationPolicy=bulk-deferred-inventory-v2`
describes that path. It is not a format version or a weaker checksum mode.

[BulkPopulationEvent.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/jfr/BulkPopulationEvent.java)
has no explicit methods: it inherits JFR Event lifecycle and exposes artifact count,
payload bytes, target SSTable bytes, SSTable count and success. Its event name is
`aether.BulkPopulation`, with stack traces disabled. The wrapper ends its event on
successful `finish()` or on `close()`; constructor/bootstrap and successful
post-finish close are outside its duration. Event success is receipt-path evidence,
not an independent filesystem consistency validator.

## Source-Backed Tests and Scope

[EmptyStoreBulkLoaderTest](../../modules/aether-engine/src/test/java/io/aetherdb/engine/EmptyStoreBulkLoaderTest.java)
has eight tests covering sorted multi-table reopen, subsequent online updates,
abort/duplicate rejection, memory budget/exclusive lock, selected publication
faults, corrupt committed tables, target variation, and corrupted/truncated final
inventory rejection before manifest append. Its injected exceptions are not an
actual machine power failure.

[BulkArtifactWriterTest](../../modules/aether-training-cache/src/test/java/io/aetherdb/training/cache/BulkArtifactWriterTest.java)
has two tests comparing the bulk/online envelope and integrity policy and rejecting
an oversized payload without publishing earlier staging. It does not establish
an exhaustive size-boundary or concurrent-close test matrix.

[test_bulk_population.py](../../scripts/tests/test_bulk_population.py) adds opt-in
real-Java subprocess death/abort, ordinary-reader reopening, deterministic
preprocessing and layout/warm-update checks. Its crash probe halts the process at
named hooks; the OS and storage device remain alive. These tests, documentation
inventory checks, allocation profiles and the frozen H2 experiment are different
forms of evidence. None alone proves general power-loss correctness or production
readiness.
