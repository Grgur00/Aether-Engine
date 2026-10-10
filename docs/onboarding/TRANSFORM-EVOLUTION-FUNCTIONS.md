# Transform Evolution Check Functions

[Function index](FUNCTION-INDEX.md) | [Benchmark backend functions](BENCHMARK-BACKEND-FUNCTIONS.md) | [Dataset preparation](DATASET-PREPARATION-FUNCTIONS.md)

Source: [transform_evolution.py](../../scripts/transform_evolution.py).
This reference covers **all 3 explicit declarations**, including the nested
artifact-construction helper. It is a synthetic correctness check, not the
longitudinal H2 training workload or a stage-level reuse implementation.

## Architecture

```text
temporary store workspace -> one Java daemon -> eight synthetic samples
base keys and packed artifacts
    -> unchanged / source-content / normalize / resize / version / codec cases
    -> separate Java namespace + separate durable mmap directory for each case
    -> publish base -> query evolved keys -> validate expected hits and bytes
    -> publish missing evolved artifacts -> compare complete byte readback
close stores and daemon -> remove temporary stores -> trial JSON
repeat wrapper -> hash each trial report -> campaign JSON
```

Identity is delegated to `BackendContext.cache_key`: sample ID, source identity,
source hash and deterministic parameters are serialized and hashed. Parameters
include pipeline version, artifact codec and normalization scale/offset. A codec
change is deliberately part of key identity; this script does not independently
prove that its packed byte encoding changes with that parameter.

## Function Reference

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `run_once(output)` | Creates an output directory and a temporary workspace, starts a Java daemon, parses an eight-sample 8x8 synthetic workload, and constructs base artifacts. Runs six isolated scenarios against Java and durable mmap stores. Requires expected miss counts, equal Java/mmap hit dictionaries, no stale reused bytes and complete evolved byte parity after publishing missing keys. After resource cleanup, writes `aether-transform-evolution-v1` with scenario results, parameter identities, environment and final-artifact-only reuse caveat. Returns that report. | No training, throughput, GPU requirement or statistical endpoint. One live JVM across scenarios, no restart/crash check. No direct Java durability-mode assertion here; default daemon configuration is delegated. Output JSON may overwrite an existing trial report when this function is called directly. A failure prevents the success report; no failure receipt is written. |
| `run_once.values(config, inputs)` | Allocates a `BackendContext` with `__new__`, sets only `args` and `sources`, then maps each delegated cache key to preprocessed, tensor-converted, packed bytes. | Bypasses backend initialization because only key construction is needed. Computes every input eagerly, including would-be hits, so it cannot measure avoided preprocessing. Dictionary construction would collapse duplicate keys; fixed generated inputs supply distinct identities. Packing and preprocessing are delegated, not independent references. |
| `run(output, repeats)` | Requires a positive repeat count, creates the output directory and refuses an existing campaign report. For each repetition, refuses an existing `trial-NNNN` path, calls `run_once`, hashes its report and appends a relative report receipt. Writes and returns `aether-transform-campaign-v1` with all-passed status and final-artifact reuse granularity. | Default one repeat. Repetitions use the same parsed fixture defaults, not newly randomized conditions. Earlier trial reports survive later failure; there is no resume or automatic retry. Campaign/trial publication is not an all-or-nothing transaction. Report hashes are recorded, not verified by a reader in this module. |

## Scenarios and Assertions

| Scenario | Change | Expected Reuse |
| --- | --- | --- |
| `unchanged` | Same parameters and source objects. | 8 hits, 0 misses. |
| `source-content` | Deep-copies sources, XORs the first byte of sample zero with 255, recomputes that source hash. | 7 hits, 1 miss. |
| `normalize` | Shallow-copies arguments and sets normalization scale to 2.0. | 0 hits, 8 misses. |
| `resize` | Sets resize to 10. | 0 hits, 8 misses. |
| `implementation-version` | Sets pipeline version to `paper-v2`. | 0 hits, 8 misses. |
| `artifact-codec` | Sets artifact codec to `zlib`. | 0 hits, 8 misses. |

Every scenario receives the full base artifact set first. Java isolation uses
`evolution-{name}` namespaces; mmap isolation uses separate directories. Java
publication uses `commit_bytes_many`; base mmap publication is per-key `put`,
while evolved misses use `put_many`. mmap readback strips its four-byte prefix
before comparing application payloads. Java batched lookup consumes the evolved
dictionary as an iterable of keys.

The first assertion checks miss count and exact hit-dictionary equality. The
second compares every reused payload with freshly computed evolved bytes. Both
stores then receive only keys missing from their respective hit dictionaries;
complete evolved readback must equal the expected dictionary. Superseded base
keys may remain stored; this check does not implement eviction, garbage
collection or version-specific inventory counts.

## Ownership and Failures

The temporary-directory context owns the physical stores; the daemon context
owns the JVM. Each scenario opens Java and mmap stores before entering its
`try/finally`. If mmap construction fails, Java cleanup is not reached by that
block. Within `finally`, Java closes before mmap; a Java close exception can
prevent the explicit mmap close. Outer contexts still unwind. A failed scenario
does not roll back already published keys, although the temporary workspace is
subsequently removed on normal context cleanup.

Trial environment collection and report writing occur after stores/daemon have
closed and the temporary directory has exited. The CLI is module-level, with
`--output` defaulting to `build/transform-evolution` and `--repeats` defaulting to
1; it directly calls `run`, with no separate `main` declaration.

## Verification

The AST inventory test checks all three qualified declarations against the
function table. CPU contract tests exercise actual synthetic preprocessing and
durable mmap operations with a fake Java store/daemon, including scenario
counts, repeat receipts and overwrite refusal. They do not establish Java wire
integration, real JVM durability, daemon cleanup failures, GPU training or crash
consistency. Real execution of this check additionally needs the built Java
runtime classpath and an available JVM.
