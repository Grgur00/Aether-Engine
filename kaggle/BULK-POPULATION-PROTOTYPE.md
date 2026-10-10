# Empty-Store Bulk Population Prototype

Status: experimental, inline-artifact-only, population diagnostic. No longitudinal
performance or confirmatory claim. The 24-block campaign remains on hold.

## Safety Contract

`BulkArtifactWriter` owns an offline `EmptyStoreBulkLoader`. It is a separate local
pipe interface, not a new behavior of the ordinary daemon or its `putMany` opcode.
The writer holds the standard exclusive database lock and rejects any store with
existing tables, assigned sequences, or WAL records. A historically used/deleted
store is not considered new. Staging never acknowledges durability. Only explicit
`finish()` commits; close, EOF, duplicate keys, excess capacity, or invalid input
cannot implicitly commit a subset. After a commit failure, reopen and verify the
outcome; do not retry blindly.

Artifacts retain the same immutable cache keys, SHA-256, CRC32C, TensorDictCodec and
inline envelope as normal writes. A shared envelope helper is byte-equivalence
tested. Normal publication, WAL, memtable, flush and compaction policies are unchanged.

The prototype buffers and sorts at most 512 MiB of encoded keys/values and 100,000
entries. This is not a hard Java heap bound: ownership copies and the SSTable builder
also allocate. Artifacts above the existing inline threshold (256 KiB) are rejected.
Large-scale streaming/external sort and segment artifacts are future work, not a
claim that this prototype can ingest 50 million objects.

## Publication

1. Bootstrap the ordinary identity, options, manifest and empty WAL if needed.
2. Acquire the exclusive lock and recheck the never-populated-store condition.
3. Stage keys/encoded artifacts in an unsigned-byte sorted map.
4. Build approximately 32 MiB sorted, non-overlapping SSTables using the existing
   builder, force them, rename and verify them with the existing reader.
5. Create and force an empty successor WAL header for future online writes, then
   force the directory. No artifact data is written to WAL or inserted in a memtable.
6. Publish every table at level 1 in a single checksum-protected, forced manifest
   edit with the complete sequence watermark and successor WAL pointer.
7. Remove the obsolete empty WAL and force the directory before acknowledging.

The importer starts no asynchronous storage work, so its commit is also its
quiescence boundary. A restart uses the normal recovery path, which removes only
unreferenced tables/WALs. Possible manifest dependencies are never deleted on an
indeterminate commit. The existing manifest format and recovery rules are reused.
Linux directory fsync is required; Windows directory-open limitations retain the
existing platform caveat. Process-crash tests are not a substitute for power-loss
validation on every deployment filesystem.

## Diagnostic

Kaggle mode: `population-bulk`. The original eleven population arms, their exact
three repetitions, inputs, seed, batch sweep, preprocessing and relative randomized
order are retained. One `aether-bulk16` arm is inserted per repetition, giving 36
fresh full-population trials. The separate 65-sample smoke runs all twelve arms first.
No model, training epochs, or dataset evolution is run.

The bulk arm reuses the production Python `put_many` body encoder at batch 16, so
this change does not optimize online byte concatenation. The new offline transport
uses a local pipe with volatile staging acknowledgements. It skips missing-key scans
and immediate artifact decode because emptiness was verified. Consequently, a total
speed difference is not attributable solely to removing the WAL; report the recorded
client construction, SHA/envelope, sorting, SSTable and manifest stages separately.

The population timer includes preprocessing, encoding, staging transfer, all table
construction/forces and manifest publication. Startup and shutdown are also included
in total time. After the offline writer stops, an independent ordinary Java daemon
must read and hash every expected artifact without preprocessing a miss. That
restart correctness check is outside the population endpoint and is reported
separately. A future longitudinal pilot must charge the transition into the online
service; this diagnostic does not establish that lifecycle's performance.

## Next Gate

Inspect the three-repetition diagnostic without choosing a favorable subset. If the
prototype is promising and correct, integrate it into a fresh five-run longitudinal
pilot, including online-service startup after bulk initialization. Freeze a new
implementation/protocol only after that pilot, then consider fresh confirmation.
Do not pool old pilot measurements with the changed implementation.
