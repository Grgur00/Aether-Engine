# H4 Implementation Status

Independent experiment tooling. No H2 protocol, worker, dataset binding or Java
storage implementation is changed here. `protocol.json` is an exploratory draft,
not a frozen confirmatory protocol.

The approved H4-only storage revision is tracked separately in
[`FEASIBILITY.md`](FEASIBILITY.md) and `storage-revision/`; candidate patches do
not alter the root production sources or H2 archives. Diagnostic profiling is
available through `h4.storage_preflight --diagnostic-jfr --exception-log` and
`h4.profile_admission RECORDING --output SUMMARY.json`. A profiled run cannot
unlock a capacity gate.

The H4-only population driver now waits for healthy compaction quiescence every
256 artifacts (sixteen PUT_MANY batches). This bounds ingestion pressure without
raising engine stop thresholds or retrying outcome-unknown writes. The pause
cost belongs to secondary population time, not measured read performance.

Reproduce the isolated candidate with `h4.prepare_storage_revision --bundle
VERIFIED_V3.bin --patch scripts/h4/storage-revision/bounded-storage-v1.patch
--output NEW_DIRECTORY`. The tool verifies the baseline and patch, rejects
undeclared changes, includes regression sources and inventories the candidate.
Build Java and run its tests inside that directory. Do not apply the patch to
the H2 snapshot. Current results and the CPU-only Linux continuation are recorded
in `FEASIBILITY.md`; full H4 qualification is still pending.

## Resource Discovery

For an administrator-delegated Linux VM, use
`python -m h4.linux_readiness --scratch SCRATCH --output NEW_REPORT_DIRECTORY`
inside the built isolated candidate. This read-only, fail-closed check captures
source/build provenance, block-backed scratch and safe capacity. It cannot
qualify primary retrieval. The concrete delegation layout and next commands are
in [the Linux execution plan](LINUX-EXECUTION-PLAN.md).

From the repository's `scripts` directory:

```powershell
..\.venv\Scripts\python.exe -m h4.kaggle_preflight --output ../build/h4-local-discovery --scratch ../build
```

On the Kaggle Linux host, with this directory on Python's module path:

```bash
python -m h4.kaggle_preflight --output /kaggle/working/h4-discovery --scratch /tmp --attempt-memory-control
```

The command writes hardware, disk, memory-control and preflight reports plus
SHA-256 checksums. Output must be empty so earlier evidence cannot be replaced.
Exit code 2 means the primary H4 eligibility gate is not passed. Missing commands,
files and permissions are retained explicitly in evidence.

The optional memory probe creates a uniquely named child of the current visible
cgroup only when memory control is already delegated. It never enables controllers
or changes limits on the parent. It sets child memory.max, disables child swap,
joins a Python probe, starts a diagnostic Java 21 JVM with a 512 MiB heap in that
group, writes/reads a bounded 64 MiB temporary file, checks both PIDs' membership,
captures file-cache accounting and OOM events, and attempts child removal. This
does not allocate dummy memory to exhaust the host. Scratch probe files are removed.
Cleanup failures remain in the report.

`assess_read_window` evaluates snapshots collected by a future actual-workload
runner. It requires unchanged memory/swap limits, exact recorded process
membership, no OOM counters, increasing scan/steal/file-refault counters, and
attributable cgroup physical reads of at least 1/16 of the memory limit (128 MiB
at 2 GiB). This is a draft feasibility threshold, not a performance endpoint.
Missing or regressing counters fail closed. Passing resource counters never
sets primary eligibility or establishes artifact correctness on its own.

This capability probe deliberately cannot set `primary_h4_eligible=true`. Actual
Aether service/worker membership, official dataset provenance, measured storage amplification and
sustained larger-than-budget reads/reclaim still need separate workload evidence.
A file-cache charge increase after writing is not proof of physical read I/O.
Only diagnostic processes are launched; no benchmark service or stores are launched.

## PCam Source Preparation

Complete the 1 GiB online-admission gate below before acquiring the full image
archive. Source preparation is not a substitute for storage scalability evidence.

Install the independent H4 dependencies with `python -m pip install -r
h4/requirements.lock` from `scripts`. Then:

```bash
python -m h4.prepare_pcam_manifest --data ../build/h4-pcam/data --download --output ../build/h4-pcam/manifest
```

This uses the official Zenodo mirror, checks published gzip MD5 values and exact
download sizes, and records SHA-256 identities. Downloads use bounded range
requests. Network failures retain a `.partial` file for resumption, but only a
complete, checksum-verified archive is published. Extraction is size-bounded and
existing HDF5 files must match the verified gzip stream. No PCam subset is used
as a substitute for the complete official training input.

Manifest preparation checks training shapes and all binary labels, then hashes
the largest selected subset one image at a time. It produces `manifest.json`,
`sample_hashes.json`, `transform_identity.json` and a checksum inventory. The
seeded PCG64 subsets are nested; sequential and shuffled request IDs are frozen
for each condition. Labels and original training indices are retained. The
0.25x condition has 4,854 samples (flooring avoids exceeding the payload target).
There is no store creation or retrieval timing in this command.

The default transfer deadline is 900 seconds per archive. A slow mirror can be
resumed with the same command and a larger `--max-download-seconds`; no partial
archive counts as verified data. The official image download is approximately
6.4 billion bytes, followed by extraction and manifest hashing.

Local manifests alone do not establish cross-backend correctness or versioned
Kaggle dataset provenance. Source acquisition requires approximately 17 GiB free
initially including a conservative extraction bound and 2 GiB reserve; this is
separate from the larger backend scratch guard. Large sources remain in ignored
`build/`, not git or results ZIPs.

## Online Admission Amendment

H4 stores use existing bounded durable `putMany` admission, in batches of 16,
instead of direct bulk import. The direct loader stages the whole import and has
a default 512 MiB key-plus-value ceiling. Its 32 MiB bulk SSTable target is not
a configurable target for the online path; actual online SSTable sizes are recorded.
The production Java implementation and H2 protocol remain unchanged.

Primary retrieval timing must begin only after population, healthy compaction
drain, clean close, fresh-JVM reopen and complete artifact identity/hash validation.
Population cost and sampled peak disk usage are separate secondary measurements.
The diagnostic's disk sampler reports a lower bound, not an exact physical peak.
The read experiment does not demonstrate bulk-loader scalability.

```bash
python -m h4.storage_preflight --scratch ../build --output ../build/h4-online-1gib --gib 1
```

This uses deterministic unique synthetic 108 KiB tensors, not official PCam,
with a bounded 512 MiB Java heap. It performs full readback before and after a
fresh-JVM restart and a shuffled all-byte-consuming pass. Use `--samples 32` for
a small harness smoke; it does not qualify the GiB gate. After success, repeat
at 2, 4 and 8 GiB, supplying successful smaller `preflight.json` files through
repeatable `--prior-preflight` arguments. Larger attempts fail closed without
the earlier size gates. No JFR or exception logging is enabled by default.
`--exception-log` is a separate failure diagnostic and is never a primary timing.

### Current Local Evidence

The 32-artifact smoke passed admission, clean close, fresh-JVM reopen and all
hash checks. The 1 GiB Windows diagnostic failed after 1,776 artifacts were
reported admitted (196,411,392 logical payload bytes). JVM exception logging
identified `OutOfMemoryError: Java heap space` in `RestartBlock.decode`, propagating
through `SSTableReader.verify`, SSTable publication and online flush/admission.
The RPC disconnect alone did not identify that cause; the separate exception
log did. This is a failure of this tested 512 MiB-heap configuration, not proof
of a universal database-size ceiling or a measured Linux/Kaggle result.

Evidence is retained in ignored `build/h4-online-smoke-v3/` and
`build/h4-online-1gib-diagnostic/`. No 2/4/8 GiB Aether attempt or successful
1 GiB recovery/read result exists. Do not enlarge the heap or change the engine
silently to turn this failure into a passing result. The online scalability gate
and the independent Kaggle memory-control/page-cache feasibility gate both remain
unpassed. The mmap adapter can be developed independently of these gates.

The source audit also found an existing reader-retention limitation:
[`SSTableReader.finishOpen`](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/SSTableReader.java)
calls full ordinary verification, which loads each data block into a strong
`DataBlock.entries` list. The online engine retains the table readers. This is
consistent with the observed heap exhaustion and also affects recovery and reads,
not only admission. No existing public cache-disable control was found. This
source observation is not a Linux capacity measurement, but it means that simply
switching to bounded online writes does not establish an out-of-core reader.
The proposed no-application-cache-shortcut requirement remains unqualified; the
H4 harness does not clear caches through reflection or modify the frozen engine.

The H4-only lifecycle helper now checks the actual JVM maximum heap, Java major
version and absence of an active JFR recording at startup, rather than trusting
the command line alone. A new 32-artifact smoke with these runtime checks passed
clean close, fresh-JVM reopen and all hashes (`build/h4-online-runtime-smoke/`).

The independent 1 GiB mmap persistence diagnostic passed 9,710 artifacts,
complete validation before close and after reopen, and a shuffled pass consuming
all 1,073,848,320 payload bytes. Its receipt is in `build/h4-mmap-1gib/`.
This local Windows run neither enforced a memory budget nor established physical
read I/O; directory fsync is implemented only on POSIX. It is not a crash-durability
qualification or a primary H4 performance result.

```bash
python -m h4.mmap_storage_preflight --scratch ../build --output ../build/h4-mmap-size-check --gib 1
```

## Retrieval And Paired Pilot Runner

`retrieval.py` uses identical batch-16 lookups and a full-byte SHA-256 consumer
for Aether and mmap. Every sample is hash-validated outside timing to establish
the expected ordered consumer digest. One warm-up precedes three chronological
measured passes; their throughput mean is one block summary, not three independent
statistical replicates. A separate single-artifact pass samples up to 128 evenly
spaced request positions for latency. It excludes consumer hashing/release from
lookup latency and is excluded from throughput timing. Full hashes are checked
again afterward. "Sequential" means the frozen logical/population order; it is
contiguous physical order for mmap, not a claim about Aether's hashed-key layout.

`inputs.py` verifies the prepared evidence inventory, official source archives and
HDF5 hashes, current transform implementation, all nested subsets/request orders,
and source/artifact identity binding. Population rechecks each image, label and
transformed payload against the manifest while streaming it. These checks are
outside measured retrieval windows.

`h4_runner.py` implements exploratory assigned complete paired blocks only.
It refuses to run until earlier online size gates pass. Each backend uses a fresh
delegated child cgroup containing its Python worker and inherited JVM/compiler
processes; the launcher never changes parent controls or falls back to unrestricted
memory. Store construction, validation, clean reopen and read passes occur before
store deletion. Partial progress, controller errors and raw process/cgroup evidence
are saved separately from immutable completed block records. Missing source,
hardware, cgroup, disk or cleanup evidence fails closed.

Example future matrix smoke (not currently eligible to run):

```bash
python -m h4.h4_runner --data /kaggle/input/<versioned-source>/data \
  --prepared /kaggle/input/<versioned-source>/manifest --scratch /tmp \
  --output /kaggle/working/h4-pilot --dataset-version <owner/slug/version> \
  --conditions 0 1 2 3 4 --blocks 0 \
  --prior-preflight /path/to/successful-1gib/preflight.json \
  --prior-preflight /path/to/successful-2gib/preflight.json \
  --prior-preflight /path/to/successful-4gib/preflight.json \
  --estimated-block-seconds <conservative-complete-pair-duration>
```

Use `--conditions 4 --blocks 0 1 2 3 4` for the five-block large-condition pilot
in a separate output directory. A soft nine-hour deadline prevents starting a
pair without the conservative duration estimate plus a ten-minute reserve.
Resume requires the same immutable source/data/protocol/assignment and predefined
host class; failed or incomplete blocks are not silently retried. Host matching
uses CPU model/architecture, available core count, Java/Python/package versions,
memory limit, filesystem type and stable mount options. Raw hardware observations
are retained, but varying CPU clock readings, CPU affinity numbering and overlay
session paths do not define incompatible hosts.
Dataset version is a required recorded binding, not proof that a Kaggle upload
has happened. No source dataset has been uploaded by these tools yet.

`validate_h4.py` rechecks workload cardinality, digest equality, pass chronology,
throughput arithmetic, latency quantiles and raw cgroup resource verdicts.
Standalone retrieval records cannot establish primary eligibility. Confirmatory
execution requires a separately qualified and frozen design; no design or primary
result has been certified for the current failed scalability preflight.

The shared mmap lifecycle and both patterns have been tested with small injected
fixtures. The controlled launcher has fail-closed tests, but has not yet been
executed on a delegated Kaggle cgroup. There is no completed official-PCam pair.

## Analysis And Figures

```bash
python -m h4.analyze_h4 --campaign /path/to/raw-session-one \
  --campaign /path/to/raw-session-two --prepared /path/to/manifest \
  --output /path/to/h4-analysis
python -m h4.generate_h4_figures --analysis /path/to/h4-analysis/analysis.json \
  --output /path/to/h4-figures
```

Analysis verifies archive inventories, source/host identities, workload records,
paired digests and block assignments. Duplicate block identities, incompatible
archives and modified records are rejected. Failed pairs are listed explicitly
and never counted as successful blocks. Pilot and confirmatory records cannot
be pooled. Within-block throughput is the mean of its three pass throughputs;
paired log ratios, not the passes themselves, are the statistical observations.
Per-session descriptive estimates are preserved alongside pooled compatible-host
paired estimates. These do not remove possible temporal/session dependence.

Outputs include `analysis.json`, `paired-observations.csv`, `limitations.md`, and
checksums. Missing I/O, fault, allocation or memory measurements remain unavailable,
with coverage counts. Population physical writes are separate from read-pass I/O.
Process CPU/RSS summaries use archived Linux units; summed endpoint RSS is neither
an exact peak nor unique physical memory. Read amplification must not be inferred
from payload bytes alone. The five figure pairs (PNG/PDF) cover relative throughput,
absolute throughput, tail latency, physical I/O/reclaim and storage footprint.
Allocated bytes and apparent file lengths remain distinct.

Descriptive confidence intervals use Student t intervals on paired-block log
throughput ratios. One block has no estimable interval. Pilot intervals are not
confirmatory evidence of superiority. The implementation uses the documented
[SciPy one-sample t test](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.ttest_1samp.html)
and [Student t distribution](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.t.html).

## Confirmatory Freeze

Only after five valid official-PCam 4x paired pilot blocks qualify both backends'
out-of-core reads may `freeze_h4.py` create a design. The current source must still
match the pilot. The caller must supply an even sample size and the claim/margin;
neither is chosen from favorable results by the tool.

```bash
python -m h4.freeze_h4 --campaign /path/to/qualified-pilot \
  --prepared /path/to/manifest --output /path/to/h4-design \
  --sample-size <predeclared-even-n> --claim noninferiority \
  --null-ratio <predeclared-relative-throughput-margin>
```

The separate `confirmatory-design.json` freezes shuffled 4x retrieval, 2 GiB
memory, balanced global backend ordering, the paired-log one-sided Student t test,
alpha and an explicit no-silent-replacement policy. The artifact manifest and
base workload protocol stay unchanged. Pass `--design /path/to/confirmatory-design.json`
to `h4_runner.py`, assign only condition 4, and shard distinct frozen block IDs
across sessions. Each shard retains the full design. Independent confirmatory
blocks are rebuilt, never copied from the pilot.

Analysis performs the predeclared test only when the entire required qualified
paired-block set is present with no exclusions. Incomplete or degenerate data
does not produce a confirmatory verdict. Eligibility and rejection of the null
are separate outputs: a valid study can fail to demonstrate noninferiority or
superiority. No confirmatory design or hypothesis-test result has been generated
from real H4 data yet. Figure tests use explicitly labeled fixtures, not invented
experiment observations.

## CPU-Only Kaggle Preflight

`kaggle_cpu_preflight.py` prepares an allowlisted worktree snapshot, uploads it
privately, downloads and checks the uploaded transport, and launches a private
CPU-only notebook with its source dataset version pinned. Credentials, local
stores and PCam downloads are excluded. It does not alter the H2 deployment.

```powershell
.venv/Scripts/python.exe scripts/h4/kaggle_cpu_preflight.py prepare
.venv/Scripts/python.exe scripts/h4/kaggle_cpu_preflight.py upload
.venv/Scripts/python.exe scripts/h4/kaggle_cpu_preflight.py run
.venv/Scripts/python.exe scripts/h4/kaggle_cpu_preflight.py status
```

The diagnostic installs Java 21, builds the recorded runtime classpath, checks
memory-control permissions, runs a 32-artifact lifecycle smoke, then attempts
1 GiB online admission with a 512 MiB Java heap and exception logging. The latter
is an explicitly diagnostic, uncontrolled capacity check, not a primary timed
read experiment. Even successful admission would not certify beyond-RAM reads.
There is no GPU request, full PCam benchmark or automatic larger-size attempt.
Commands stream to notebook output and `execution.log`; reports and checksums
are packaged into `aether-h4-preflight-results.zip` on success or failure.

On 2026-10-11, version 1 of
[the CPU preflight](https://www.kaggle.com/code/grgur321/aether-h4-cpu-preflight)
was submitted using private source dataset version 1. The verified transport
SHA256 is `557ea7e7414f7db35bdd656167abe90330aa4fdc140cb89f90c8689e8e4a0a95`.
Version 1 failed during Python `venv` bootstrapping, before any memory probe or
Aether admission test. Its downloaded archive and source inventory were verified;
this is setup evidence, not a scalability failure. Version 2 uses a pinned
`virtualenv==21.14.1` bootstrap and source dataset version 2, with transport SHA256
`5f4bb3c725838944420731c5e461503831dba6a087fafd886333bc04307286fc`.
Version 2 reached Gradle, but failed because the upload omitted its declared
example projects. It did not reach the Aether admission test. Version 3 includes
their build scripts, main sources and schema inputs. The exact extracted upload
passed both the runtime-classpath build and the 32-artifact lifecycle smoke
locally before submission. Source dataset version 3 has transport SHA256
`3488c44729b081061e3e4d92fa66bfbd8956940be3a6660aed3ff92b3f552521`.

Submission is not qualification. Verify the downloaded archive against the
matching local upload inventory before interpreting its reports:

```powershell
$env:PYTHONPATH='scripts;clients/python'
.venv/Scripts/python.exe -m h4.inspect_cpu_preflight /path/to/aether-h4-preflight-results.zip `
  --source-inventory build/h4-kaggle-cpu-v3/source-inventory.json
```

The inspector refuses mismatched source snapshots, missing completed-stage
reports, and unsupported success flags. Successful admission must include full
artifact checks, fresh JVM reopen, quiescence, runtime heap evidence and shuffled
byte consumption. Its output always remains non-primary.

The larger-store launcher also verifies prior admission reports and their local
checksum inventories before unlocking 2/4/8 GiB. A bare `passed: true`, a small
smoke, incomplete validation, pending flushes, a reused JVM, or edited evidence
cannot satisfy the gate. The stricter validator was checked against the real
32-artifact packaged-source smoke as well as rejection fixtures. These checks
do not convert synthetic admission into official-PCam or out-of-core evidence.

## Remaining Work

**Runtime gates failed on Kaggle version 3.** The verified 32-artifact smoke
passed, but memory control was not delegated and the 1 GiB online-admission
diagnostic exhausted Java heap during SSTable verification. See
[the feasibility record](FEASIBILITY.md) for archive identities, raw report
locations and the distinction between notebook completion and qualification.
The subsequently approved isolated H4 candidate passed synthetic 1/2/4 GiB
Aether admission and restart checks; mmap passed through 8 GiB. A CPU-only
Kaggle continuation is testing the candidate through 8 GiB. See the feasibility
record for exact snapshots and outcomes. The H2 storage freeze has not changed.
Official-PCam and controlled beyond-RAM experiments have not started.

New admission receipts preserve their Java build manifest and source hashes.
The retrieval worker requires admission evidence matching its actual Java
sources. Older diagnostic receipts without that binding cannot unlock primary
retrieval runs; their archived results remain unchanged.

- M0 completion: real Kaggle probe and controlled JVM/Python membership; bounded
  larger-than-budget I/O/reclaim qualification and measured temporary disk peak.
- M1 completion: run official source preparation and cross-backend validation;
  attach source data as a versioned Kaggle input and bind its dataset version.
- M2 completion: online-admission scalability gates and actual small/4x controlled
  pairs. Adapters and shared controlled retrieval/paired-pilot runner exist; the
  original Aether 1 GiB gate failed; the separate candidate's 8 GiB gate is pending.
- M3: five-size pilot matrix and five paired blocks at selected conditions.
- M4 completion: a qualified pilot, actual design freeze and execution of the
  predeclared session-safe campaign. Freeze/test/runner tooling exists and has
  unit coverage; no real H4 design is frozen yet.
- M5 completion: analysis and the five figures from actual qualified experiment
  records. Archive aggregation, intervals, figure generation and limitations
  reporting exist; fixture tests do not substitute for experimental evidence.

Optional LMDB and end-to-end training remain after primary storage feasibility.
No performance result or beyond-RAM claim exists yet.

Official references:
[PCam source and download checksums](https://github.com/basveeling/pcam),
[Linux cgroup v2 memory accounting](https://docs.kernel.org/admin-guide/cgroup-v2.html).
