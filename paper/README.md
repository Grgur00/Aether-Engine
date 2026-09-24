# Aether paper artifact

This package implements the evaluation machinery described in the TPDS preparation plan. It is a research artifact under development, not a submission-ready result. See [Kaggle instructions](../kaggle/README.md) for the remote execution workflow.

The measured path is PyTorch → Python client → Java `TrainingCache` → persistent Aether engine and segments. The Python prototype is still selectable for earlier experiments, but primary matrix blocks require the Java daemon. Both persistent backends use explicit matching durability modes. Artifact identity binds sample identity, source content and deterministic parameters, including an explicit pipeline version. A changed normalization/resize/source/version/codec yields a safe miss. The optional zlib artifact codec preserves the decoded tensor representation. Only final artifacts are materialized; no stage-level reuse claim is made.

## Entry points

For the manuscript and supplement PDFs, install [Tectonic 0.17.0](https://github.com/tectonic-typesetting/tectonic/releases/tag/tectonic%400.17.0) and run `make paper-pdf` or `python scripts/build_paper.py`. You may pass `--tectonic PATH`. Outputs, logs and source hashes go to `build/paper-pdf`; downloaded TeX resources stay in `build/tex-cache`. After the initial build, use `--offline` to rebuild from that cache. The build command does not certify submission readiness.

Optional page rendering: install `env/requirements-paper.lock` in a separate document-building environment, then run `python scripts/build_paper.py --offline --render`. The page images and extracted text support visual review; they are not replacements for the vector PDF.

Install JDK 21, Python 3.11, `env/requirements.lock` and the appropriate PyTorch 2.13.0 build. Official accelerator-specific wheel commands are at [PyTorch previous versions](https://pytorch.org/get-started/previous-versions/). Kaggle setup reuses the installed CUDA build and records its exact version.

```bash
python -m pip install -r env/requirements.lock
python -m pip install torch==2.13.0 --index-url https://download.pytorch.org/whl/cpu
make artifact-smoke
make reproduce-primary DATASET_CONFIG=/path/to/datasets.json
make reproduce-all DATASET_CONFIG=/path/to/datasets.json
```

Without Make, use `python scripts/reproduce.py smoke`, `primary`, or `all`. Pass `--output` and `--config` as needed. The smoke target runs Java/Python regression tests, actual Java/mmap tensor and CPU-model parity, two-process loaders, shared-store clients, transformation evolution and one failure per boundary/mode. It is not performance evidence.

The Gradle classpath task also records source and runtime hashes. Every managed Java launch verifies source membership/content, compiled classes, resources and dependency jars against this build receipt. A stale or modified build stops the benchmark with a rebuild command. Rebuild after changing Java sources or build configuration and after extracting an archive on another host.

Use `--scratch-root PATH` with the Python entry point, or `SCRATCH_ROOT=PATH` with Make, to keep large generated Java/mmap stores on a separate filesystem. Measurement reports remain under `--output`. The runner checks a payload capacity lower bound before spending GPU time, preserves interrupted stores and removes only its uniquely owned successful store directory. GPU campaigns also lock their output and preserve the first frozen environment record during resume.

Concurrency and fault campaigns freeze their protocol, source hashes and host/software identity before measuring. Their `--resume` option validates saved result receipts and preserves the original environment record. A kernel lock prevents two runners from owning the same output. Interrupted fault attempts are retained separately; completed failed trials are not silently retried. Changed sources, host or software require a new campaign directory.

## Statistical contract

Each `block.json` identifies its protocol, condition, environment and paired repeat, and hashes its underlying training report. Analysis rejects failed, duplicate, modified or missing measurements. It groups different hardware/protocols separately. Primary comparisons use log throughput ratios, geometric means and Student-t 95% intervals; the equivalence claim uses TOST with bounds 0.97 and 1.03. Holm correction is applied to the RAW comparison and the TOST intersection-union p-value. Paired bootstrap and Wilcoxon are sensitivity analyses. See [SciPy t-test documentation](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.ttest_1samp.html) and [statsmodels paired TOST documentation](https://www.statsmodels.org/stable/generated/statsmodels.stats.weightstats.ttost_paired.html).

`scripts/analyze.py --mixed-effects` fits the stated secondary model only when the collected matrix is sufficiently varied and the fit converges. `--pilot` labels a separate >=10-block pilot and reports approximate sample-size planning without using the observed direction as the assumed effect. Do not pool exploratory sweeps into the confirmatory family.

## Interpretation and limitations

- `mmapDynamics` reports measured reads/appends and exact lifecycle counts. Per-mapping page faults are unavailable and remain null; process measurements must not be represented as faults attributable only to mmap.
- The legacy JSON ID `STATIC_PREPROCESSED_MMAP` now denotes the actual incremental mmap baseline. Its payload path is read-only mapping slices; index batches are appended to a checksummed journal with incomplete-tail recovery. Missing payload batches are appended together; publication does not rewrite historical metadata.
- Process-crash recovery is tested separately from power-loss durability. Windows cannot provide the same directory-fsync semantics as the evaluated Linux filesystem.
- Derived cache keys are immutable. Conflicting writes are rejected. The acknowledged-write contract is evaluated below capacity with no intentional eviction or namespace invalidation.
- Loader and client concurrency are distinct campaigns. The latter explicitly reports synthetic storage-client throughput and client publication attempts, not unique physical engine commits.
- Preflight hashes/reference checks warm source and artifact pages. The current protocol is explicitly uncontrolled with respect to the OS page cache; it is not a cold-storage claim.
- Four-backend runs retain full reference tensors and a RAM baseline with memory proportional to cardinality. Size sweeps can select `--backends raw,aether,mmap`, which computes reference samples incrementally without retaining the whole tensor dataset. Unsupported RAM points fail with an estimated requirement. Full-data claims remain pending suitable runs.
- DALI has a separate canonical RGB workload in `scripts/dali_comparison.py`: direct GPU DALI is paired with Java/mmap caches of the exact same DALI outputs. This is not a claim that DALI and Pillow decode/resize identically. CUDA runtime validation and measured results remain pending. FFCV is optional and is not implemented.

## Artifact and manuscript freeze

Run `python scripts/package_artifact.py` for a source zip with hashes and dirty/clean provenance. Do not describe a dirty archive as a frozen revision. `python scripts/checksums.py RESULTS` freezes result checksums. `scripts/submission_gate.py` audits evidence coverage and leaves authorship, policies, disclosure and submission to explicit review. The manuscript and supplement sources intentionally contain pending-result markers until real remote measurements are available.

Docker source is in `docker/Dockerfile`. Build/run on a host with Docker and NVIDIA Container Toolkit:

```bash
docker build -t aether-paper -f docker/Dockerfile .
docker run --rm --gpus all --ipc=host -v "$PWD/results:/artifact/results" aether-paper make artifact-smoke
```

The local Windows Docker daemon was unavailable during initial implementation, so container reproduction remains a separate validation gate. For confirmatory work, run from a clean frozen checkout or a verified source archive with clean originating provenance.

Build with `--build-arg WITH_DALI=true` to include the optional DALI dependency for `reproduce-all`. The default image supports the core smoke and primary matrix.

## Additional evaluation outputs

`--workflow-experiments 5` measures V1 population plus five successive fresh-model V2 experiments over the same persistent stores, restarting Java between experiments. This is a separate campaign, not five independent primary replicas. `figures.py` exports its cumulative cost curve and paired Aether/mmap ratios with the predefined 0.97?1.03 band.

Use `python scripts/systems_figures.py training --input RESULTS/primary --output RESULTS/primary/tables` for raw cache/resource and hardware/software tables. Replace `training` with `concurrency` or `durability` for those campaign figures. Confidence intervals require at least two independent observations per plotted condition; smoke results are not publication figures.

See [the implementation/evidence ledger](IMPLEMENTATION_STATUS.md) for the complete remaining requirements. The [Kaggle notebook](../kaggle/aether_paper.ipynb) provides archive verification, setup, smoke, pilot and an explicitly disabled-by-default confirmatory cell.

## DALI comparison

On a compatible Linux CUDA 12 host, install `env/requirements-dali.lock` after the main requirements. Run `python scripts/dali_smoke.py --output build/dali-smoke` first. It uses five generated PNG/JPEG fixtures and verifies partial batches, initial reuse, cache counters and exact final-model parity; the analyzer rejects these fixtures as research evidence.

Then run `python scripts/dali_comparison.py --config DATASETS.json --datasets coco,imagenet --repeats 12 --output results/dali --resume` and `python scripts/dali_analyze.py --input results/dali --output results/dali/processed`. The direct baseline uses asynchronous DALI prefetch and passes GPU tensors to PyTorch through DLPack. Missing cache entries execute the same DALI operators on demand. Every cached payload must match canonical bytes and all three backends must produce the same final model. This experiment uses its own frozen protocol and evidence receipts; it must not be pooled with the Pillow primary experiment. Full-source checks and canonical preflight warm files, as explicitly recorded.

The DALI integration is implemented against the [official pipeline API](https://docs.nvidia.com/deeplearning/dali/user-guide/docs/pipeline.html), [external-source contract](https://docs.nvidia.com/deeplearning/dali/user-guide/docs/operations/nvidia.dali.fn.external_source.html), and [DLPack tensor API](https://docs.nvidia.com/deeplearning/dali/user-guide/docs/data_types.html). The optional direct dependency is pinned to [NVIDIA's DALI CUDA 12 package 2.3.0](https://pypi.org/project/nvidia-dali-cuda120/2.3.0/); local Windows CPU tests do not validate its CUDA kernels.
