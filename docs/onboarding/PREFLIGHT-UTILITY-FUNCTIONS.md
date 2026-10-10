# Preflight and Smoke Utility Functions

[Function index](FUNCTION-INDEX.md) | [Benchmark training](BENCHMARK-TRAINING-FUNCTIONS.md) | [Research process ownership](RESEARCH-PROCESS-FUNCTIONS.md)

Sources: [checksums.py](../../scripts/checksums.py),
[validate_gpu.py](../../scripts/validate_gpu.py), and
[artifact_smoke.py](../../scripts/artifact_smoke.py).
This guide covers **all 4 explicit functions across three complete files**.
File-qualified names disambiguate CLI entry points.

## Architecture and Evidence Roles

```text
local artifact directory -> SHA256SUMS -> recorded-file verification
CUDA PyTorch host -> capability check -> small matmul -> optional DALI import
temporary cache workspace -> real Java daemon
    -> synthetic backend tensors + accounting + worker observations
    -> CPU segmentation optimization and final-model parity
    -> generated RGB classification optimization
    -> correctness reports, never paper block timing
```

These utilities answer different questions. Checksums detect recorded-file drift;
GPU validation checks basic host usability; smoke exercises small CPU workflows.
None independently establishes a complete frozen experiment, authenticates
provenance, proves GPU training convergence or certifies crash consistency.

## Function Reference

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `checksums.main()` | Parses root and optional `--verify`, resolves root and uses `root/SHA256SUMS`. Recording sorts recursive paths, hashes regular non-symlink files except the manifest, and writes digest plus relative POSIX path lines. Verification splits each line at two spaces, resolves the target, rejects escape outside root, and compares delegated SHA-256. Prints count or success. | Recording overwrites the manifest directly; no atomic replacement or root creation. Verification checks only recorded files, not extra files or completeness. Empty manifest verifies vacuously; a newline-only manifest from an empty recording fails line parsing. Malformed lines and missing files propagate errors. No signature or independent trusted digest. Recording excludes file symlinks; verification can follow a contained symlink. |
| `validate_gpu.main(argv)` | Imports Torch, requires CUDA availability and a CUDA build, obtains current-device properties and requires compute capability major >= 7. Executes a 256x256 CUDA ones matmul and synchronization. Builds version/device/VRAM/capability/sum report; optionally imports DALI and records its version. Queries `nvidia-smi` and prints JSON. | Missing Torch/CUDA, old capability, matmul runtime failure and missing required DALI terminate with messages. No expected numeric-sum assertion, memory-capacity minimum, multi-GPU coverage or full model test. DALI import is not pipeline execution. Nonzero `nvidia-smi` exit becomes `unavailable`; missing executable raises instead. No explicit subprocess timeout or receipt file. |
| `artifact_smoke.smoke(output, workers, scratch_root, training_epochs)` | Requires positive training epochs, creates output, obtains an owned cache workspace and starts a Java daemon. For each worker count, builds a 12-sample 8x8 synthetic workload with batch 4, two epochs, durable Java/mmap and initial 50% hits. Compares scheduled tensors from every backend with raw checksums, requires cache-accounting invariants, and for worker mode requires transport requests and observed lookup counts. Runs CPU segmentation optimization, then generated tiny ImageNet/COCO classification optimization, writing individual correctness reports and final smoke summary with environment. | Default workers `(0, 2)`, one training epoch. No GPU training or paper block schema. Does not refuse existing output reports. Successful intermediate reports remain if later stages fail; final success is written only after contexts exit. No automatic resume, rollback or retry. Temporary stores/fixtures are not preserved as evidence. |
| `artifact_smoke.main()` | Parses output (default `build/artifact-smoke`), comma-separated worker counts (`0,2`), optional scratch root and training epochs, converts workers to integers and invokes `smoke`. | Conversion errors propagate; this parser does not deduplicate or explicitly bound worker values. An empty list string fails conversion. Training epoch validation is inside `smoke`. No separately selectable stage. |

## Smoke Stages and Ownership

Tensor/accounting cases use a distinct namespace and mmap directory per worker
count. The raw reference follows the same scheduled batches; worker mode uses
`worker_batches`, while zero workers call the backend context directly. Checksums
must match as an ordered list. Aether and mmap dynamics must both report passing
invariants. Worker observations require nonzero request and lookup counts, not a
specified latency or performance improvement. Each constructed backend context
closes in `finally`; construction failure happens before that block.

The optimizer stage sets Torch CPU threads to one without restoring the previous
process-wide setting. It runs four synthetic samples with batch two, configured
training epochs, no warmup and disabled GPU sampling through the shared training
runner on `torch.device('cpu')`. Final-model parity is delegated to that runner;
this wrapper reports CPU correctness rather than independently comparing model
states itself. Its report is `cpu-training-correctness.json`.

Classification fixtures contain two classes with two generated RGB PNGs each.
ImageNet preparation uses class directories; COCO preparation uses generated
instance JSON with category IDs 1 and 5. Both classification runs select only
raw/Aether/mmap, using the bounded-reference path. COCO sets artifact codec to
`zlib`. Their reports explicitly say generated-fixture correctness, not real
dataset evaluation. Final `smoke.json` records worker cases and training epoch
count; optimizer details remain in the separate correctness reports.

The workspace context owns scratch cleanup and the daemon context owns Java
startup/stop. Output reports remain outside temporary storage. Repeated worker
counts reuse their namespace/directory names within a single invocation, so the
default distinct worker list should not be inferred to support arbitrary repeated
entries with identical accounting expectations.

## Verification

The documentation AST test compares all four file-qualified function declarations
with the table. Offline contract tests exercise checksum drift/extra-file/escape
behavior and CUDA preflight branches with a fake Torch module. They do not run
CUDA kernels, DALI pipelines or the real Java artifact smoke campaign. Running
the latter needs the built Java runtime plus the Python workload dependencies;
its CPU result remains excluded from research timing regardless of success.
