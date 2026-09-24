# TPDS implementation and evidence ledger

Source: the user's attached TPDS preparation plan, read on 2026-09-05. Updated 2026-09-08. This records implementation and its actual validation; it is not submission approval.

| Requirement | Implementation | Evidence / remaining work |
|---|---|---|
| Actual Java measurement path | `java_store.py`, binary daemon metadata/presence/batch operations; primary runner requires Java DURABLE | Actual Java round trips, tensor equality and CPU optimization smoke passed. GPU measurements pending Kaggle. |
| True incremental mmap | `persistent_mmap.py`: mapped payload reads, checksummed batch index journal, immutable keys, process locks | Tests cover reopen/remap, payload/journal corruption, torn journal tail, immutable batch conflict, incremental replay. Two clients × two workers passed. |
| Complete mmap dynamics | Initial indices, lookups/hits/misses, measured bytes/time/appends/remaps and invariants | CPU smoke verifies 24 lookups, 18 hits, 6 misses for 12 samples with 50% initial reuse and two epochs. Per-mapping faults remain unavailable; no fabricated zero. |
| Source and transformation evolution | `transform_evolution.py`; source, normalize, resize, implementation version; final-artifact reuse | Source/normalize/resize/implementation and real none-to-zlib codec evolution are implemented. Stage-level materialization is not implemented or claimed. Full ten-repeat campaign pending; one-repeat correctness evidence recorded locally. |
| Correctness | Input tensors and deterministic final model state equality across selected backends | Four-backend segmentation CPU optimization, three-backend RGB classification and workers 0/2 passed. Real dataset/CUDA parity pending. |
| Datasets | OCT adapter; COCO multi-label / ImageNet single-label RGB adapters; `prepare_vision.py`, `prepare_evolution.py` | Tiny generated RGB fixtures validate adapters and training only. No real COCO/ImageNet experiment has run locally. |
| Frozen paired experiments | `run_matrix.py`: fresh V1/V2 stores, Java restart, seeded randomized backend order, explicit page-cache conditions, source/manifests/environment hashes | Orchestration tests and plan generation; real 24-block primary and separate pilot remain pending. Resume and analysis reject altered underlying evidence. |
| Dataset/reuse/cost/worker/GPU scaling | `configs/paper/evaluation.json` and matrix CLI; optional bounded reference without RAM | Implemented drivers. Large real manifests, CUDA and one/two-GPU runs remain pending. Two GPUs mean single-node DataParallel. |
| Scratch capacity and GPU output ownership | `cache_workspace.py`, `experiment_output.py`; separate temporary stores, capacity floor, exclusive output and immutable resume metadata | Cleanup/retention/capacity tests passed; actual Java/mmap CPU smoke used separate scratch. Real large-dataset disk requirements remain host dependent. |
| Concurrent clients | `concurrency_matrix.py`, real spawned processes sharing Java/mmap stores | Small 2×2 process trial passed. Full 1/2/4 clients × 0/2/4/8 workers × 10 paired repeats pending. This is explicitly a storage microbenchmark. |
| Durability and recovery | Five Java publication hooks; external kill; single writes and two-artifact batches; checksums, acknowledgment witness, atomic batch visibility, recovery and orphan bytes | Ten local process-crash trials passed (one per point/mode). Full 100 per point/mode and POSIX SIGKILL campaign pending. No power-loss claim. |
| Resource counters | Parent/Java process counters and worker batch-window CPU, I/O, faults and RSS on Linux; GPU allocation hours and per-device peak allocation | Windows reports unavailable Linux counters. Parser tested with explicit fixtures; actual Linux counters must be checked on Kaggle. Worker measurements exclude startup/IPC/idle time. |
| Cumulative workflow cost | `--workflow-experiments N`: persist stores, restart Java, reset model per experiment; population plus cumulative lifecycle cost and each underlying report hash | Orchestration/accumulation test passed. Actual multi-experiment GPU campaign pending. Setup/validation command walls are separate. |
| Statistics | Log-ratio t intervals/tests, TOST, Holm, paired bootstrap, Wilcoxon, separate-pilot planning; optional mixed model | Core tests match SciPy/statsmodels. No empirical estimate or scientific equivalence claim without remote data. Mixed model needs a sufficiently varied actual matrix. |
| Figures/tables | `figures.py`, `systems_figures.py`: standalone PDF/600-dpi PNG, paired ratio/band, sweeps, workflow, concurrency, recovery, CSV/LaTeX tables | Renderer tested on explicitly artificial temporary fixtures. Publication figures await measured inputs. |
| External optimized baselines | Primary references collected for DALI, FFCV, HyCache, Seneca | Canonical DALI direct-vs-Java/mmap integration, generated-fixture CUDA smoke, dedicated analysis and receipts are implemented. CUDA execution remains unverified locally. FFCV is optional and not implemented. Related-work citations are not measured baselines. |
| Reproducibility package | Makefile, Python entry point, dependency lock, Java source/runtime hash receipt, Dockerfile, checksums, source archive provenance, Kaggle setup/notebook | Archive verification is recorded separately in `build/aether-paper-artifact.validation.json`, tied to the archive SHA. Docker daemon unavailable; container build and independent clean-room reproduction remain unverified. |
| Manuscript | IEEEtran manuscript/supplement sources, related-work and verified dataset bibliography, explicit pending-evidence markers | Draft, not a final 12-page paper. TeX layout, actual results, author/ORCID details, journal-specific policy review and AI disclosure remain pending. |
| Submission gate | Evidence hashes, repetition/scenario coverage, proposed statistical claims; human review separate | Intentionally cannot certify independent reproduction or author approval using a generated boolean. Missing evidence keeps readiness false. |

## Local validation record

- Java training-cache Gradle tests passed, including immutable batch conflicts, idempotent publication and eight racing writers. The classpath task hashes build inputs and runtime files; managed launches reject stale sources, classes and dependency jars.
- 95 Python tests passed; the current JUnit record is `build/paper-python-tests.xml`. This includes DALI orchestration with explicit CPU test doubles, not actual CUDA/DALI execution.
- `build/artifact-scratch-smoke/` contains CPU-only correctness reports from the actual Java engine, including workers 0/2, worker transport counters and classification fixtures, using a separate scratch workspace whose successful cleanup was verified.
- `build/artifact-provenance-faults/` contains ten externally terminated Java writers with no lost acknowledged writes or corrupt visible artifacts; batch publication verified two targets. Each result has a receipt and frozen campaign provenance.
- `build/artifact-frozen-concurrency/` contains the completed small Java/mmap shared-store campaign with two clients and two workers each. Kernel campaign locking, immutable environment metadata and verified resume are implemented. Earlier outputs remain separate.

## External work required for the full requested end state

VS Code remote-job setup is implemented in `scripts/kaggle_remote.py`, with task
template `kaggle/vscode-tasks.json` and instructions `kaggle/VSCODE.md`. Kaggle CLI
2.2.4 is installed locally in an isolated environment. A private notebook/source
dataset configuration is prepared for `grgur321`; authentication and actual Kaggle
creation/execution are still pending. Seven focused notebook/submission tests passed,
including changed upload input rejection and source-upload receipt validation.

The user runs GPU tests in a remote Kaggle environment. No remote credentials, accessible CUDA host or real dataset manifests were provided to this workspace. The implementation must therefore be transferred and executed there. Required primary/cross-dataset/scaling/failure results, statistical freeze, independent reproduction and a final evidence-backed manuscript cannot be replaced by local CPU fixtures or the preliminary numbers in the planning report.

The working tree includes pre-existing user changes and untracked work. The source archive records this dirty state. Review and freeze a committed source revision before confirmatory measurement. No work has been committed, published or submitted automatically.
