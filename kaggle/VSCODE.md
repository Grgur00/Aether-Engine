# Run Aether on Kaggle from VS Code

The local Kaggle CLI is isolated in `build/kaggle-venv`, pinned by
`env/requirements-kaggle.lock`. VS Code tasks are installed in `.vscode/tasks.json`;
the portable copy is `kaggle/vscode-tasks.json`. Existing editor settings are preserved.

The prepared account is **grgur321**, with a new private notebook
`grgur321/aether-engine-vs-code` and a private source dataset
`grgur321/aether-engine-source`. Preparation creates local files only. The dataset
and notebook are created on Kaggle when their upload/run tasks succeed.

## First run

In VS Code press **Ctrl+Shift+P**, choose **Tasks: Run Task**, then run these tasks
in order, waiting for each to finish:

1. **Kaggle: Login** — complete Kaggle's browser login as `Grgur321`.
2. **Kaggle: Check connection** — makes a read-only request to list your notebooks.
3. **Kaggle: Prepare source** — snapshots the current local code.
4. **Kaggle: Upload source (first time)** — creates the private source dataset.
5. **Kaggle: Source status** — wait until Kaggle reports the dataset ready.
6. **Kaggle: Run notebook** — creates/updates the private notebook and starts it
   with a T4 accelerator and Internet enabled.
7. **Kaggle: Notebook status** or **Kaggle: Notebook logs** — inspect the run.
8. **Kaggle: Download outputs** — after completion, downloads one results ZIP
   into a new `results/kaggle/` subdirectory.

The default mode is `smoke`: it validates a real CUDA operation, builds/tests Java,
and runs CPU correctness, evolution, process-crash and concurrency checks. It does
not produce the 24-block GPU research results. Kaggle account verification, GPU
availability/quota and Internet permissions still apply to execution.

After editing code, repeat **Prepare source**, then **Update source**,
**Source status**, and **Run notebook**. Preparation hashes upload inputs; altered
files require a new preparation. The remote notebook verifies the attached source
against the expected manifest and checks each source file before execution.

To compare the first cold epoch with later cache reuse in the CPU smoke training
fixtures, prepare with `--mode smoke --training-epochs 5`, then update the source
and run the notebook. The saved epoch setting persists on later preparations;
use `--training-epochs 1` to restore the original one-epoch checks. This changes
the three CPU training fixtures; the tensor-accounting, crash, and concurrency
checks keep their existing protocols.

For request diagnosis use `prepare --mode profile`, then **Update source**,
**Source status**, and **Run notebook**. This collects 30 paired tracing-on/off
windows for 4 KiB warm hits, misses, and publications, using correlated server
timing. Results are under `aether-results/request-profile/`. It is exploratory
profiling, not the primary GPU experiment matrix. Use `prepare --mode smoke`
to return to the smoke campaign.
Wait for the previous notebook run to finish before submitting another version;
the status and download tasks target the latest notebook version.

Kaggle stores login credentials through its own authentication mechanism. Do not
put tokens in this repository or notebook. The wrapper never prints a token.
This is a remote job workflow; it does not configure an interactive SSH terminal.

### Kaggle Studio extension on Windows

The installed Kaggle Studio 1.2.4 extension uses POSIX argument quoting even on
Windows. The workspace `kaggle.cliPath` therefore invokes
`scripts/kaggle_studio_windows.py` through the isolated Python interpreter. This
launcher decodes the extension's argument quoting and invokes the CLI without a
shell, fixing errors such as `invalid choice: "'kernels'"`. Project tasks call the
CLI directly and do not need this adapter.

Studio's **Kaggle: Sign In** prompt uses a username/API key stored in VS Code's
secret storage. Canceling either prompt produces `Sign in canceled`. This is
separate from **Tasks: Run Task → Kaggle: Login**, which uses the CLI browser login.
For the prepared workflow, use the project tasks; signing into one mechanism does
not automatically satisfy the other's credential check.

## Equivalent PowerShell commands

Each newly prepared run creates `/kaggle/working/aether-results-only.zip`.
It contains all Aether results (including nested reports and logs), `run.log`
with setup/experiment output, `run-status.json`, runtime metadata when available,
and `SHA256SUMS`. ZIP entries are checked against their hashes before temporary
results, this run's source checkout and Python environment are removed.
Python-level setup or experiment failures also save a ZIP of partial evidence
and retain failed notebook status. A forcibly terminated Kaggle session cannot
be guaranteed to finish packaging.

The **Download outputs** task requests only this ZIP and checks its integrity.
Kaggle's CLI also writes its platform log separately; the task folds that log
into the ZIP as `kaggle-notebook.log`, updates checksums, then removes the separate
log. A direct Kaggle CLI command retains that additional log beside the ZIP.
After updating local code, use **Prepare source**, **Update source**, wait for
**Source status** to be ready, then **Run notebook**. Older notebook versions
are not changed retroactively. The direct download command is:

```powershell
kaggle kernels output grgur321/aether-engine-vs-code -p results/kaggle/latest --file-pattern 'aether-results-only\.zip'
```

From the repository root:

```powershell
# Already installed here; needed on a fresh checkout:
python scripts/kaggle_remote.py setup

build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py prepare --user grgur321
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py login
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py check
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py upload-source
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py source-status
# Once source status reports ready:
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py run
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py status
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py logs
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py outputs
```

On Linux/macOS use `build/kaggle-venv/bin/python`. On a fresh checkout copy or merge
`kaggle/vscode-tasks.json` into `.vscode/tasks.json`; `.vscode` is ignored by Git.

## Primary experiments

Attach your data/configuration datasets when preparing the notebook. For example,
replace `YOUR-DATASET` and `YOUR-CONFIG` below with real Kaggle dataset slugs:

```powershell
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py prepare --user grgur321 --mode primary --dataset-source grgur321/YOUR-DATASET --dataset-source grgur321/YOUR-CONFIG --dataset-config /kaggle/input/YOUR-CONFIG/datasets.json
```

`datasets.json` must follow `configs/paper/datasets.json` and reference paths that
exist in the remote session. Each `--dataset-source` adds an attachment; supplying
these options replaces the previous attachment list. Subsequent preparation keeps
the saved mode/configuration unless explicitly changed. Use `--mode smoke` to
return to smoke checks. `--scratch-root` selects a remote writable directory for
generated caches. See [the Kaggle guide](README.md) for datasets and disk planning.

Confirmatory runs require a clean, frozen source revision. Current uncommitted code
is suitable for smoke checks; the archive records its actual provenance. Full
`--mode all` additionally installs the pinned DALI dependency and runs the broader
matrix, which may need several sessions and substantial storage. Remote execution
and authentication must be verified before treating this setup as tested on Kaggle.

CLI behavior follows the official [Kaggle CLI documentation](https://github.com/Kaggle/kaggle-cli),
[notebook metadata](https://github.com/Kaggle/kaggle-cli/blob/main/docs/kernels_metadata.md)
and [dataset metadata](https://github.com/Kaggle/kaggle-cli/blob/main/docs/datasets_metadata.md).

If an earlier setup created `aether-engine-vs-code` while reporting errors for
`aether-engine-vscode`, run **Prepare source**, **Update source**, **Source status**,
and **Run notebook**. The corrected helper uses the existing `vs-code` notebook
and checks dataset readiness before submitting. Do not repeat the first-time
dataset creation command for an already created dataset.

If submission reports `Maximum batch GPU session count of 2 reached`, wait for
an existing GPU session to finish before running the notebook task again. The
helper treats this CLI response as a failure even when Kaggle returns exit code
zero. Check **Notebook status** before retrying to avoid repeated submissions.
