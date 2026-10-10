# Java Benchmark Evidence and Baseline Functions

[Function index](FUNCTION-INDEX.md) | [Benchmark execution](JAVA-BENCHMARK-RUNNER-FUNCTIONS.md) | [Research workflows](EXPERIMENTS-AND-PROFILING.md)

This reference covers **27 explicit declarations in eight complete files** in
[aether-benchmarks](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks).
Generated record accessors, equality and string representations are not counted.
These are Java benchmark report utilities, not the Python H2 campaign protocol.

## Evidence Architecture

```text
Measured workload + caller-supplied metadata
    -> BenchmarkResultV1 (immutable validated report)
    -> BenchmarkArtifacts (path references, optional JFR reference)
    -> BenchmarkResultJsonV1.encode -> caller writes JSON

BenchmarkResultV1 -> BenchmarkBaselineEntry.fromResult
    -> BenchmarkBaselineStore.encode -> caller persists text
    -> decode -> find(profileId)
    -> toResultLike(candidate) -> synthetic comparison input
```

Construction validates shape and selected bounds. It does not verify measurement
accuracy, commit provenance, workload equivalence, artifact integrity or durability.
None of these functions starts recording, runs a benchmark, uploads evidence or
atomically publishes a result. [Comparison and gate policy](JAVA-BENCHMARK-COMPARISON-FUNCTIONS.md)
are separate consumers.

## Canonical Result and Numeric Summaries

Sources: [BenchmarkResultV1.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/BenchmarkResultV1.java),
[BenchmarkCounters.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/BenchmarkCounters.java),
[BenchmarkLatencyHistogram.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/BenchmarkLatencyHistogram.java).

| Function | Behavior and ownership | Validation and limits |
| --- | --- | --- |
| `BenchmarkResultV1.BenchmarkResultV1(...)` | Stores benchmark ID, commit, dirty flag, four metadata maps, outcome counters, throughput, latency summary and artifacts. Strips identity strings; copies maps and artifact list. Schema version is 1. | IDs capped at 128 UTF-16 units; counters and latency required; throughput finite and nonnegative. Null artifacts becomes empty; list capped at 64, null elements rejected by copy. No profile/commit lookup or cross-check between counters, latency count and workload. |
| `BenchmarkResultV1.requireText(value, field, maximumLength)` | Checks then returns `strip()`; field name appears in the exception. | Rejects null, blank, overlength, NUL and ISO control characters with IllegalArgumentException. Length applies before stripping. Does not validate a Git hash or Unicode normalization. |
| `BenchmarkResultV1.copyMap(values, field)` | Validates every entry and returns immutable `Map.copyOf`. Used for environment, aetherConfig, workload and storage. | Required, at most 256 entries; key text capped at 128. The stripped key returned by requireText is discarded, so original spaced keys remain. Values required, capped at 4096, no NUL; other control characters are allowed. No required metadata fields or semantic schema. |
| `BenchmarkCounters.BenchmarkCounters(...)` | Stores submitted, acknowledged, rejectedBeforeAck, uncertain and failed totals. | All nonnegative; outcome sum must not exceed submitted. Equality is not required, so unaccounted submissions are allowed. Sum uses unchecked long arithmetic: overflow can bypass the bound. Does not establish that acknowledgement means durable commit. |
| `BenchmarkLatencyHistogram.BenchmarkLatencyHistogram(...)` | Stores count and p50/p95/p99/max nanoseconds; despite its name, this is a summary, not buckets or samples. | Nonnegative count and p50; p50 <= p95 <= p99 <= max. Zero count may have nonzero percentiles. No percentile recomputation, sample provenance or count-to-counter validation. |

Record-generated accessors expose immutable strings and copied containers here.
The numeric summaries contain caller-provided numbers, not an instrument that
observes writes or computes percentiles. Map iteration order is not preserved;
the JSON encoder sorts it explicitly.

## JSON Serialization

Source: [BenchmarkResultJsonV1.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/BenchmarkResultJsonV1.java).

| Function | Output responsibility | Boundaries |
| --- | --- | --- |
| `BenchmarkResultJsonV1.BenchmarkResultJsonV1()` | Private utility constructor. | No instance state. |
| `BenchmarkResultJsonV1.encode(result)` | Builds a string in fixed order: schemaVersion, benchmarkId, gitCommit, dirty, environment, aetherConfig, workload, counters, throughputOpsPerSecond, latencyHistogram, storage, artifacts. | Throughput uses Locale.ROOT with three decimal places, losing sub-mill precision. No trailing newline, parser or file write. Null result causes NullPointerException. Deterministic formatting is not cryptographic canonicalization. |
| `BenchmarkResultJsonV1.map(json, name, values, last)` | Sorts keys by natural String order, quotes values and emits an object with two indentation levels. | `last` controls the closing comma. Sorting is case-sensitive; metadata strings remain strings, not numbers or booleans. |
| `BenchmarkResultJsonV1.counters(json, counters, last)` | Emits the five outcome fields in fixed order as long literals. | No accounting computation or additional validation. |
| `BenchmarkResultJsonV1.latency(json, latency, last)` | Emits count, p50Nanos, p95Nanos, p99Nanos and maxNanos as long literals. | Does not serialize actual histogram buckets. |
| `BenchmarkResultJsonV1.artifacts(json, result)` | Emits ordered kind/URI objects; URI comes from `toString()`. Closes the final report field. | Does not open or verify referenced objects. Artifact ordering follows the input list. |
| `BenchmarkResultJsonV1.field(json, indent, name, value, last)` | Writes two spaces per indent, quoted field name and an already formatted JSON value. | Value is appended literally; callers must quote string values before passing them. |
| `BenchmarkResultJsonV1.quote(value)` | Escapes backslash, double quote, LF and CR, then surrounds the result with quotes. | Does not escape tab, backspace, form feed or all other U+0001..U+001F controls. These are accepted in metadata values, so some valid result records produce invalid JSON. No surrogate validation. |

For ordinary printable metadata the encoder provides stable ordering. Do not infer
that all accepted record inputs yield standards-valid JSON: the constructor and
escaping policies differ. Existing tests assert substrings and ordering, not a
strict JSON parser round-trip. This guide records that gap; it does not change the
runtime serializer.

## Artifact References

Sources: [BenchmarkArtifact.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/BenchmarkArtifact.java),
[BenchmarkArtifacts.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/BenchmarkArtifacts.java).

| Function | Behavior | What it does not guarantee |
| --- | --- | --- |
| `BenchmarkArtifact.BenchmarkArtifact(kind, uri)` | Requires nonblank kind, caps it at 64 units, rejects NUL/ISO controls and strips it. Requires URI object with text length <= 2048. | No scheme allowlist, absolute-URI requirement, existence check, hash or trust policy. Relative and empty URI objects can pass. |
| `BenchmarkArtifacts.BenchmarkArtifacts()` | Private utility constructor. | No recording lifecycle. |
| `BenchmarkArtifacts.forResult(output)` | Requires output Path; emits result-json from absolute normalized file URI, followed by optional jfr-recording. Returns copied immutable list. | Does not create files or directories, resolve symlinks with toRealPath, measure size or verify hashes. |
| `BenchmarkArtifacts.jfrUri()` | Uses nonblank system property `aether.benchmark.jfr.path`, otherwise environment `AETHER_JFR_PATH`; neither configured returns empty. Interprets the string as a filesystem Path and converts to absolute normalized URI. | Not a URL parser; does not strip configured path text. Invalid path may throw. A JFR reference does not start recording or prove that recording succeeded. |

Recording enablement belongs to the launcher/build configuration, separately from
these artifact declarations. Preserve both the actual recording and its provenance
when using a report as experimental evidence.

## Compact Baseline Entries

Source: [BenchmarkBaselineEntry.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/BenchmarkBaselineEntry.java).

| Function | Behavior | Information loss or validation |
| --- | --- | --- |
| `BenchmarkBaselineEntry.BenchmarkBaselineEntry(...)` | Stores profile ID, result URI text, commit, throughput, three latency percentiles and acknowledged count; strips the three strings. | Strings nonblank; URI text <= 2048, but not parsed as URI. ID/commit have no length or control-character limits. Throughput finite/nonnegative, latencies monotonic/nonnegative, acknowledgements nonnegative. |
| `BenchmarkBaselineEntry.fromResult(result, resultUri)` | Extracts ID, commit, throughput, p50/p95/p99 and acknowledged count into the compact entry. | Drops dirty flag, all metadata maps, other counters, latency count/max and artifact list. Null result fails on dereference. Result URI supplied separately by caller. |
| `BenchmarkBaselineEntry.toResultLike(candidate)` | Builds a synthetic BenchmarkResultV1 with saved metrics and commit, dirty=false, submitted=acknowledged, remaining outcomes zero, histogram count=acknowledged and max=p99. Borrows candidate metadata through the result constructor's copies; artifacts empty. | Environment, config, workload and storage are candidate context, not historical baseline evidence. Stricter result ID/commit validation may reject an entry accepted by its own constructor. Null candidate fails. |

The comparison skeleton is a convenience for numeric gates, not reconstruction of
the original run. Equal metadata in this skeleton cannot prove that baseline and
candidate actually ran on equivalent hosts or workloads. Retain the original
result artifact for that audit.

## Baseline Store and Text Format

Source: [BenchmarkBaselineStore.java](../../modules/aether-benchmarks/src/main/java/io/aetherdb/benchmarks/BenchmarkBaselineStore.java).

| Function | Behavior | Failure and parser policy |
| --- | --- | --- |
| `BenchmarkBaselineStore.BenchmarkBaselineStore(entries)` | Requires <= 512 entries, rejects duplicate exact profile IDs, sorts by profile ID and retains an immutable list. | Null list rejected with IllegalArgumentException; null entry fails on dereference. Does not load resultUri or verify commit. |
| `BenchmarkBaselineStore.find(profileId)` | Linear exact String equality lookup returning Optional. | No stripping or normalization; null search yields no match. |
| `BenchmarkBaselineStore.encode()` | Writes schemaVersion=1 and entries count, then eight entry.i fields per sorted entry: profileId, resultUri, gitCommit, throughputOpsPerSecond, p50Nanos, p95Nanos, p99Nanos, acknowledged. Ends with newline. | Throughput uses normal double string conversion, not JSON's three-place rounding. Returns text only; no IO, lock, checksum or atomic replacement. |
| `BenchmarkBaselineStore.decode(input)` | Splits on Java regex line breaks, skips blank lines, splits each nonblank line at first equals, unescapes values, checks schema 1, parses entry count and required fields, then constructs the sorted store. | Null/invalid line/schema/negative count rejected. Numeric errors propagate NumberFormatException. Duplicate property keys use last value; unknown keys ignored. No input-size limit; 512-entry cap applies only after parsing entries. |
| `BenchmarkBaselineStore.required(values, key)` | Retrieves required field or throws IllegalArgumentException naming missing key. | Checks presence only; entry constructor checks blank strings. |
| `BenchmarkBaselineStore.escape(value)` | Escapes backslash, LF as backslash-n and equals as backslash-e. | CR and other regex line separators are not escaped, despite entry strings allowing them; such values need not round-trip. |
| `BenchmarkBaselineStore.unescape(value)` | Stateful scan: backslash-n becomes LF, backslash-e becomes equals, other escaped character is appended directly. Retains trailing backslash. | Unknown escapes are accepted and lose the slash. This is a custom format, not java.util.Properties. |

Keys are not trimmed, comment lines are not supported and extra declared-index
fields are ignored when outside the entry count. Sorting makes normal encoding
stable, but permissive decoding is not canonical-input validation. Duplicate
profile IDs still fail when constructing the final store.

## Verification Scope

[JSON tests](../../modules/aether-benchmarks/src/test/java/io/aetherdb/benchmarks/BenchmarkResultJsonV1Test.java)
cover stable output substrings/order, throughput rounding and selected counter/
latency rejection.
[Artifact tests](../../modules/aether-benchmarks/src/test/java/io/aetherdb/benchmarks/BenchmarkArtifactsTest.java)
cover default and configured JFR references; the default case can depend on an
inherited AETHER_JFR_PATH environment value.
[Baseline tests](../../modules/aether-benchmarks/src/test/java/io/aetherdb/benchmarks/BenchmarkBaselineStoreTest.java)
cover ordinary round-trip, sorting, duplicate profiles and gate consumer examples.

These tests do not establish strict JSON validity for all accepted strings,
overflow-safe counters, artifact existence, historical baseline-context parity,
all escape edge cases or early bounded parsing. Compiler-tree documentation checks
cover declaration names, not semantic correctness of every possible input.
