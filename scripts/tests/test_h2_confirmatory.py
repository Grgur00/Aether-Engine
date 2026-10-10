import copy
import math
from collections import Counter
import pytest
from scipy.stats import ttest_1samp

import h2_protocol as protocol
import h2_confirmatory as runner
from h2_analysis import analyze, plot
from evidence import digest
from longitudinal_state import cumulative, save_receipt
from paper_common import write_json


def config():
    return protocol.read(protocol.ROOT / "configs/paper/oct5k-h2-confirmatory.json")


def bulk(count=1200):
    return dict(status="committed", servicePid=1234, artifacts=count, sha256Calls=count, storage=dict(entries=count,
        tables=8, targetSstableBytes=33554432, walPayloadBytes=0, memtableInsertions=0,
        verificationPolicy="bulk-deferred-inventory-v2",
        verification=dict(inventoryCalls=1, tablesFullyVerified=8, bytesFullyVerified=800),
        sstableFinishes=[dict(bytes=100) for _ in range(8)],
        streamingVerification=dict(implementation="streaming-v1", entries=count, tables=8)))


def reference():
    return dict(referenceHashes=[str(i) * 64 for i in range(5)], transformIdentity={"passes": 4},
                manifests=dict(manifestSha256=[str(i + 5) * 64 for i in range(5)]))


def frozen():
    cfg = config()
    return dict(config=cfg, sourceHash="f" * 64, schedule=protocol.schedule(cfg["orderSeed"]),
                training=protocol.TRAINING, storage=protocol.STORAGE, boundary=protocol.BOUNDARY,
                pageCache="no cache clearing", resourceLimitations="sampled counters",
                maxAttemptsPerBlock=3)


def stage(backend, version, block=0):
    cfg = config()
    count = cfg["versions"][version]
    previous = cfg["versions"][version - 1] if version else 0
    phases = dict(startup=1., modelSetup=2., scanAdmission=3., training=100., drain=4., close=5.)
    result = dict(preprocessCalls=count - previous, newSamples=count - previous, reusedSamples=previous,
        uniqueArtifacts=count, trainingCacheMisses=0, trainingPreprocessCalls=0,
        trainingSampleRequests=count * 20, tensorSha256=reference()["referenceHashes"][version],
        sampleOrderSha256=reference()["manifests"]["manifestSha256"][version],
        transformIdentity=reference()["transformIdentity"], modelSeed=cfg["seed"] + block + version,
        initialModelSha256="a" * 64, modelSha256="b" * 64,
        epochs=[dict(epoch=i + 1, sampleRequests=count, losses=[.1] * math.ceil(count / 16), wallMs=5.) for i in range(20)],
        timingsMs=phases, fullLifecycleMs=sum(phases.values()), device="cuda", traceEnabled=False,
        trainingState="fresh-model-and-optimizer-per-version", serviceLifecycle="persistent-per-block",
        engineInfo=None, backgroundDrains=[])
    if backend == "aether":
        result.update(engineInfo=dict(pid=1234, durability="DURABLE", cacheEntries=count,
            integrityPolicy=dict(version="immutable-inline-admission-v1"), backgroundCompaction=dict(enabled=True)),
            serviceStartCount=int(version == 0), serviceStopCount=int(version == 4),
            backgroundDrains=[dict(drained=True, backgroundCompaction=dict(state="IDLE", debtBytes=0, failed=0))])
        if version == 0:
            result["bulkCommit"] = bulk()
    return result


def block(index=0):
    plan = frozen()
    assignment = plan["schedule"][index]
    results = {}
    for backend in protocol.BACKENDS:
        stages = [stage(backend, v, index) for v in range(5)]
        results[backend] = dict(stages=stages, cumulativeMs=cumulative(stages))
    return dict(experiment="H2-confirmatory", blockIndex=index, seed=assignment["seed"],
        backendOrder=assignment["backendOrder"], correctnessPassed=True, technicalFailure=False,
        sourceHash=plan["sourceHash"], frozenProtocolHash=digest(plan), backendResults=results,
        measurementRole="confirmatory")


def test_all_permutations_are_balanced_and_seeded():
    orders = protocol.schedule(42)
    assert orders == protocol.schedule(42) and orders != protocol.schedule(43)
    assert len({tuple(o["backendOrder"]) for o in orders}) == 24
    for position in range(4):
        assert Counter(o["backendOrder"][position] for o in orders) == {b: 6 for b in protocol.BACKENDS}
    assert [o["seed"] for o in orders] == list(range(20260926, 20260926 + 24))


@pytest.mark.parametrize("key,value", [("epochs", 1), ("pairedBlocks", 5), ("batchSize", 32),
    ("targetSstableBytes", 64 * 1024 ** 2), ("serverTrace", True), ("jfr", True),
    ("prefetchDepth", 1), ("trainV0", False), ("confirmatory", 1),
    ("initialPopulation", "online"), ("serviceLifecycle", "restart-per-version")])
def test_configuration_drift_is_rejected(key, value):
    cfg = config()
    protocol.validate_config(cfg)
    cfg[key] = value
    with pytest.raises(ValueError, match="drift"):
        protocol.validate_config(cfg)


@pytest.mark.parametrize("kind", ["duplicate-pass", "wal-duplication", "old-verifier", "different-size"])
def test_bulk_integrity_and_storage_freeze(kind):
    value = bulk()
    protocol.validate_bulk(value, 1200)
    storage = value["storage"]
    if kind == "duplicate-pass":
        storage["verification"]["inventoryCalls"] = 2
    elif kind == "wal-duplication":
        storage["walPayloadBytes"] = 236000000
    elif kind == "old-verifier":
        storage["streamingVerification"]["implementation"] = "reader-v2"
    else:
        storage["targetSstableBytes"] *= 2
    with pytest.raises(ValueError, match="bulk"):
        protocol.validate_bulk(value, 1200)


@pytest.mark.parametrize("kind", ["v0-not-trained", "missing-batch", "miss", "wrong-seed", "checksum", "pid", "bulk-pid", "debt", "timing", "state", "cpu"])
def test_mandatory_correctness_rejects_invalid_block(kind):
    value = block()
    plan = frozen()
    runner.validate_block(value, plan, reference(), plan["schedule"][0])
    stages = value["backendResults"]["aether"]["stages"]
    if kind == "v0-not-trained":
        stages[0]["epochs"] = []
    elif kind == "missing-batch":
        stages[1]["epochs"][0]["losses"].pop()
    elif kind == "miss":
        stages[2]["trainingCacheMisses"] = 1
    elif kind == "wrong-seed":
        stages[3]["modelSeed"] += 1
    elif kind == "checksum":
        stages[1]["tensorSha256"] = "c" * 64
    elif kind == "pid":
        stages[3]["engineInfo"]["pid"] += 1
    elif kind == "bulk-pid":
        stages[0]["bulkCommit"]["servicePid"] += 1
    elif kind == "debt":
        stages[4]["backgroundDrains"][0]["backgroundCompaction"]["debtBytes"] = 1
    elif kind == "timing":
        stages[0]["timingsMs"]["training"] = float("nan")
    elif kind == "state":
        stages[1]["trainingState"] = "continued"
    else:
        stages[0]["device"] = "cpu"
    with pytest.raises(ValueError):
        runner.validate_block(value, plan, reference(), plan["schedule"][0])


def test_paired_model_equivalence():
    value, plan = block(), frozen()
    value["backendResults"]["mmap"]["stages"][4]["modelSha256"] = "c" * 64
    with pytest.raises(ValueError, match="paired"):
        runner.validate_block(value, plan, reference(), plan["schedule"][0])


def test_worker_binding_must_match_the_hash_verified_preflight():
    value, plan, ref = block(), frozen(), reference()
    ref["inputBinding"] = dict(schema="aether-h2-input-binding-v1", physicalRoot="nested", logicalRoot="legacy")
    with pytest.raises(ValueError, match="input mount binding"):
        runner.validate_block(value, plan, ref, plan["schedule"][0])
    for result in value["backendResults"].values():
        for stage in result["stages"]:
            stage["inputBinding"] = ref["inputBinding"]
    runner.validate_block(value, plan, ref, plan["schedule"][0])


def test_cli_forwards_read_only_input_mount(monkeypatch, tmp_path):
    captured = []
    monkeypatch.setattr(runner, "run", lambda *args, **kwargs: captured.append(kwargs))
    physical = tmp_path / "input/datasets/owner/oct5k"
    runner.main(["--candidate-root", str(tmp_path / "candidate"), "run",
        "--frozen", str(tmp_path / "frozen"), "--output", str(tmp_path / "results"),
        "--scratch-root", str(tmp_path / "scratch"), "--input-root", str(physical),
        "--stop-after-blocks", "12"])
    assert captured[0]["input_root"] == physical and captured[0]["stop_after_blocks"] == 12


def test_runner_finishes_backend_lifecycle_before_next_backend(tmp_path):
    plan = frozen()
    assignment = plan["schedule"][0]
    calls = []
    class Service:
        def __init__(self, scratch, reports, identity):
            self.scratch, self.reports, self.identity = scratch, reports, identity
            self.daemon = dict(pid=1234, bulkPort=9000)
        def __enter__(self):
            return self
        def __exit__(self, *args):
            pass
        def run_stage(self, backend, version, worker):
            value = worker(backend, version, self.scratch / backend / "live")
            if backend == "aether" and version == 0:
                self.daemon = dict(pid=1234, port=9001)
            return save_receipt(self.reports / f"v{version}-{backend}.json", value,
                                {**self.identity, "backend": backend, "version": version})
    def job(request, reports, name):
        calls.append((request["backend"], request["version"]))
        if request["backend"] == "aether":
            assert request["service"]["pid"] == 1234
            assert ("bulkPort" in request["service"]) == (request["version"] == 0)
        assert not request["cpuFixture"]
        return stage(request["backend"], request["version"])
    value = runner.run_attempt(assignment, tmp_path / "reports", tmp_path / "scratch", plan,
        reference(), list(range(5)), {"measurementRole": "confirmatory"}, job=job, service_factory=Service)
    assert calls == [(backend, v) for backend in assignment["backendOrder"] for v in range(5)]
    assert len(calls) == 20 and value["correctnessPassed"]
    runner.verify_seal(tmp_path / "reports")


def test_service_failure_is_technical_not_a_restart_fallback(tmp_path):
    class FailedService:
        def __init__(self, *args):
            pass
        def __enter__(self):
            raise RuntimeError("bulk bootstrap lost its PID")
        def __exit__(self, *args):
            pass
    with pytest.raises(runner.TechnicalFailure, match="lost its PID"):
        runner.run_attempt(frozen()["schedule"][0], tmp_path / "reports", tmp_path / "scratch",
                           frozen(), reference(), [], {}, service_factory=FailedService)


def test_worker_lease_precedes_heavy_ml_imports(monkeypatch, tmp_path):
    import h2_worker
    from types import SimpleNamespace
    import os
    import sys
    monkeypatch.setattr(sys, "path", sys.path.copy())
    lease = tmp_path / "worker.lease.json"
    write_json(tmp_path / "request.json", dict(lease=str(lease)))
    def failing_import(module):
        assert protocol.read(lease)["pids"] == [os.getpid()]
        raise RuntimeError("ML import failure")
    spec = SimpleNamespace(loader=SimpleNamespace(exec_module=failing_import))
    monkeypatch.setattr(h2_worker.importlib.util, "spec_from_file_location", lambda *a: spec)
    monkeypatch.setattr(h2_worker.importlib.util, "module_from_spec", lambda spec: SimpleNamespace())
    with pytest.raises(RuntimeError, match="ML import"):
        h2_worker.main(["--candidate-root", str(protocol.ROOT), "--request", str(tmp_path / "request.json"),
                        "--output", str(tmp_path / "output.json")])
    assert not lease.exists()


def test_parent_launch_lease_covers_worker_startup(monkeypatch, tmp_path):
    lease = tmp_path / "v0-mmap.launch.lease.json"
    class Process:
        pid = 12345
        args = ["worker"]
        def __init__(self, *a, **k):
            pass
        def wait(self):
            assert protocol.read(lease)["pids"] == [self.pid]
            return 1
        def poll(self):
            return 1
    monkeypatch.setattr(runner.subprocess, "Popen", Process)
    with pytest.raises(runner.TechnicalFailure, match="worker crash"):
        runner.subprocess_job({}, tmp_path, "v0-mmap")
    assert not lease.exists()


def test_analysis_requires_completed_excluded_preflight(tmp_path):
    with pytest.raises(ValueError, match="exactly one"):
        runner.archived_attempt(tmp_path, {}, frozen(), reference(), frozen()["schedule"][0],
                                measurement_role="excluded preflight")


def test_runtime_guard_rejects_changed_package(monkeypatch):
    expected = dict(python="3.12.13", java=dict(returncode=0, stdout="", stderr="java 21"),
                    packages={name: "1.0" for name in protocol.RUNTIME_PACKAGES})
    from types import SimpleNamespace
    monkeypatch.setattr(protocol.platform, "python_version", lambda: "3.12.13")
    monkeypatch.setattr(protocol.subprocess, "run", lambda *a, **k: SimpleNamespace(returncode=0, stdout="", stderr="java 21"))
    monkeypatch.setattr(protocol.importlib.metadata, "version", lambda name: "1.0")
    protocol.validate_runtime(expected)
    monkeypatch.setattr(protocol.importlib.metadata, "version", lambda name: "2.0" if name == "torch" else "1.0")
    with pytest.raises(RuntimeError, match="versions changed"):
        protocol.validate_runtime(expected)


def save_block(directory, value, identity):
    directory.mkdir(parents=True, exist_ok=True)
    for backend, result in value["backendResults"].items():
        for version, s in enumerate(result["stages"]):
            result["stages"][version] = save_receipt(directory / f"v{version}-{backend}.json", s,
                {**identity, "backend": backend, "version": version})
    save_receipt(directory / "paired.json", value, identity)
    runner.seal(directory)


def test_retry_restarts_whole_block_and_preserves_failed_attempt(tmp_path):
    assignment = frozen()["schedule"][0]
    calls = []
    def execute(assignment, reports, scratch, plan, ref, paths, identity):
        calls.append(copy.deepcopy(assignment))
        reports.mkdir()
        write_json(reports / "attempt-started.json", {**identity, "assignment": assignment})
        if len(calls) == 1:
            write_json(reports / "partial-result.json", {"slowButNotACompleteResult": True})
            raise runner.TechnicalFailure("crashed during third backend")
        value = block()
        save_block(reports, value, identity)
        return value
    args = (tmp_path / "reports", tmp_path / "scratch", assignment, frozen(), reference(), [], {"blockIndex": 0})
    runner.run_with_retries(*args, execute=execute)
    assert calls == [assignment, assignment]
    assert (tmp_path / "reports/attempt-01/partial-result.json").exists()
    assert "whole four-backend" in protocol.read(tmp_path / "reports/attempt-01/failure.json")["policy"]
    runner.run_with_retries(*args, execute=execute)
    assert len(calls) == 2


def test_valid_slow_block_is_never_retried(tmp_path):
    plan, value = frozen(), block()
    for result in value["backendResults"].values():
        for s in result["stages"]:
            s["timingsMs"]["training"] = 10000000.
            s["fullLifecycleMs"] = sum(s["timingsMs"].values())
        result["cumulativeMs"] = cumulative(result["stages"])
    identity = {"attempt": 1}
    save_block(tmp_path / "reports/attempt-01", value, identity)
    def unexpected(*args):
        raise AssertionError("valid slow block must not rerun")
    runner.run_with_retries(tmp_path / "reports", tmp_path / "scratch", plan["schedule"][0],
                            plan, reference(), [], {}, execute=unexpected)


def test_tampered_completed_evidence_fails_without_retry(tmp_path):
    plan = frozen()
    directory = tmp_path / "attempt-01"
    save_block(directory, block(), {"attempt": 1})
    (directory / "v0-aether.json").write_text("{}")
    with pytest.raises(ValueError, match="evidence"):
        runner.run_with_retries(tmp_path, tmp_path / "scratch", plan["schedule"][0], plan, reference(), [], {})
    assert not (tmp_path / "attempt-02").exists()


def test_live_lease_blocks_retry(tmp_path):
    import os
    reports = tmp_path / "attempt-01"
    reports.mkdir()
    write_json(reports / "worker.lease.json", {"pids": [os.getpid()]})
    with pytest.raises(RuntimeError, match="still alive"):
        runner.run_with_retries(tmp_path, tmp_path / "scratch", frozen()["schedule"][0], frozen(), reference(), [], {})


def test_interrupted_attempt_is_archived_not_partially_resumed(tmp_path):
    assignment = frozen()["schedule"][0]
    reports = tmp_path / "reports/attempt-01"
    reports.mkdir(parents=True)
    write_json(reports / "attempt-started.json", {"attempt": 1, "assignment": assignment})
    calls = []
    def execute(a, r, s, p, ref, paths, identity):
        calls.append(identity["attempt"])
        save_block(r, block(), identity)
        return block()
    runner.run_with_retries(tmp_path / "reports", tmp_path / "scratch", assignment, frozen(), reference(), [], {}, execute=execute)
    assert calls == [2] and (reports / "failure.json").exists()


def statistical_blocks():
    blocks = [block(i) for i in range(24)]
    for i, value in enumerate(blocks):
        ratio = math.exp(.03 + (i - 11.5) * .001)
        result = value["backendResults"]["mmap"]
        for s in result["stages"]:
            s["timingsMs"]["training"] = s["fullLifecycleMs"] * ratio - 15.
            s["fullLifecycleMs"] = sum(s["timingsMs"].values())
        result["cumulativeMs"] = cumulative(result["stages"])
    return blocks


def test_primary_statistics_match_scipy_and_direction():
    values = statistical_blocks()
    summary = analyze(values)
    primary = summary["comparisons"]["mmap"]["primaryEndpoint"]
    assert primary["geometricMeanRatio"] == pytest.approx(math.exp(.03))
    assert primary["percentTimeReduction"] == pytest.approx(100 * (1 - math.exp(-.03)))
    assert primary["oneSidedP"] == pytest.approx(ttest_1samp(primary["pairedLogRatios"], 0, alternative="greater").pvalue)
    assert primary["superiority"] is True and primary["wins"] == 24
    assert primary["twoSidedCI95"][0] < primary["geometricMeanRatio"] < primary["twoSidedCI95"][1]
    assert summary["comparisons"]["mmap"]["aggregateBreakEven"] == 0
    assert "oneSidedP" not in summary["comparisons"]["monai_lmdb"]["primaryEndpoint"]


@pytest.mark.parametrize("kind", ["early", "pooled", "duplicate", "order", "failed", "preflight"])
def test_analysis_rejects_wrong_sample(kind):
    values = statistical_blocks()
    if kind == "early":
        values.pop()
    elif kind == "pooled":
        values.append(block(0))
    elif kind == "duplicate":
        values[1]["blockIndex"] = 0
    elif kind == "order":
        values[1]["backendOrder"] = values[0]["backendOrder"]
    elif kind == "failed":
        values[0]["technicalFailure"] = True
    else:
        values[0]["measurementRole"] = "excluded preflight"
    with pytest.raises(ValueError):
        analyze(values)


def test_all_equal_is_inconclusive_not_a_false_win():
    result = analyze([block(i) for i in range(24)])["comparisons"]["mmap"]["primaryEndpoint"]
    assert result["degenerateVariance"] and result["oneSidedP"] is None and not result["superiority"]


def test_all_five_figures_are_raw_result_derived(tmp_path):
    plot(statistical_blocks(), tmp_path)
    assert len(list(tmp_path.glob("*.png"))) == 5 and len(list(tmp_path.glob("*.pdf"))) == 5
    assert all(p.stat().st_size > 1000 for p in tmp_path.iterdir())


def test_archived_campaign_analysis_checks_preflight_and_all_blocks(monkeypatch, tmp_path):
    plan = {**frozen(), **reference()}
    meta = dict(protocol=plan, protocolHash=digest(plan), environmentId="test-host")
    monkeypatch.setattr(runner, "load_campaign", lambda path: meta)
    monkeypatch.setattr(runner, "verify_frozen", lambda *a, **k: plan)
    write_json(tmp_path / "input-preflight.json", reference())
    identity = dict(protocolHash=meta["protocolHash"], frozenProtocolHash=digest(plan),
                    sourceHash=plan["sourceHash"], environmentId=meta["environmentId"], attempt=1)
    for index, value in enumerate(statistical_blocks()):
        value["frozenProtocolHash"] = digest(plan)
        save_block(tmp_path / f"blocks/{index:02d}/attempt-01", value,
                   {**identity, "blockIndex": index, "measurementRole": "confirmatory"})
    with pytest.raises(ValueError, match="preflight"):
        runner.analyze_output(tmp_path)
    preflight = block()
    preflight["frozenProtocolHash"] = digest(plan)
    preflight["backendOrder"] = list(protocol.BACKENDS)
    save_block(tmp_path / "protocol-preflight/attempt-01", preflight,
               {**identity, "blockIndex": 0, "measurementRole": "excluded preflight"})
    runner.analyze_output(tmp_path)
    summary = protocol.read(tmp_path / "analysis.json")
    assert summary["completePairedBlocks"] == 24 and summary["pilotIncluded"] is False
    completion = protocol.read(tmp_path / "campaign-completion.json")
    assert completion["status"] == "complete" and len(list((tmp_path / "figures").glob("*.pdf"))) == 5
    assert "protocol-preflight/attempt-01/completion.json" in completion["files"]


def test_source_inventory_is_path_stable_and_detects_additions(tmp_path):
    (tmp_path / "scripts").mkdir()
    (tmp_path / "scripts/a.py").write_text("# one")
    before = protocol.source_files(tmp_path)
    assert list(before) == ["scripts/a.py"]
    (tmp_path / "scripts/b.py").write_text("# two")
    assert protocol.source_files(tmp_path) != before


def test_uncommitted_source_cannot_be_frozen(tmp_path):
    (tmp_path / "scripts").mkdir()
    (tmp_path / "scripts/a.py").write_text("# source")
    with pytest.raises(ValueError, match="clean"):
        protocol.source_identity(tmp_path)


def test_freeze_binds_pilot_source_and_separate_harness(monkeypatch, tmp_path):
    import zipfile
    cfg = config()
    candidate = tmp_path / "candidate"
    client = candidate / "clients/python"
    client.mkdir(parents=True)
    (client / "adapter.py").write_text("# frozen adapter")
    (client / "pyproject.toml").write_text("# packaging not recorded in pilot environment")
    pilot = tmp_path / "pilot"
    pilot.mkdir()
    commit = "1" * 40
    environment = dict(python="3.12.13 test", archiveProvenance=dict(verified=True, sourceClean=True,
        originatingCommit=dict(stdout=commit)), commands=dict(java=dict(returncode=0, stdout="", stderr="java 21"),
        packages=dict(stdout="\n".join(f"{name}==1.0" for name in protocol.RUNTIME_PACKAGES))))
    write_json(pilot / "environment.json", environment)
    manifests = dict(counts=protocol.COUNTS, seed=cfg["seed"])
    write_json(pilot / "preflight.json", dict(manifests=manifests))
    monkeypatch.setattr(protocol, "ROOT", candidate)
    files = protocol.source_files()
    source = dict(commit=commit, sourceHash=digest(files), files=files)
    monkeypatch.setattr(protocol, "source_identity", lambda: source)
    monkeypatch.setattr(protocol, "load_campaign", lambda path: dict(sourceSha256={
        "clients/python/adapter.py": files["clients/python/adapter.py"]}, protocol=reference()))
    monkeypatch.setattr(protocol, "audit_pilot", lambda path: dict(passed=True,
        evidence={"environment.json": protocol.sha256(pilot / "environment.json")}))
    monkeypatch.setattr(protocol, "verify", lambda path: (manifests, []))
    write_json(tmp_path / "config.json", cfg)
    output = tmp_path / "frozen"
    plan = protocol.freeze(tmp_path / "config.json", pilot, output)
    assert protocol.verify_frozen(output) == plan
    assert plan["commit"] == commit and plan["runtime"]["python"] == "3.12.13"
    with zipfile.ZipFile(output / "source.zip") as archive:
        assert archive.testzip() is None
        assert "artifact-provenance.json" in archive.namelist()
        assert "scripts/h2_confirmatory.py" not in archive.namelist()
    with zipfile.ZipFile(output / "harness.zip") as archive:
        assert set(archive.namelist()) == set(protocol.HARNESS_FILES)
    original = protocol.harness_files()
    monkeypatch.setattr(protocol, "harness_files", lambda: {**original, "scripts/h2_confirmatory.py": "changed"})
    with pytest.raises(ValueError, match="orchestration"):
        protocol.verify_frozen(output)
    monkeypatch.setattr(protocol, "harness_files", lambda: original)
    (client / "adapter.py").write_text("# changed adapter")
    with pytest.raises(ValueError, match="drift"):
        protocol.verify_frozen(output)
    # Offline analysis uses the archived evidence, not whatever the current checkout contains.
    assert protocol.verify_frozen(output, check_source=False) == plan


def test_pilot_audit_rejects_old_protocol(monkeypatch, tmp_path):
    monkeypatch.setattr(protocol, "load_campaign", lambda path: dict(protocol=dict(config={"pairedBlocks": 5})))
    write_json(tmp_path / "preflight.json", reference())
    with pytest.raises(ValueError, match="successful"):
        protocol.audit_pilot(tmp_path)


@pytest.mark.parametrize("value", [0, 25, True, 12.0, "12"])
def test_bounded_launch_rejects_invalid_limits(value):
    with pytest.raises(ValueError, match="launch limit"):
        runner.launch_limit(value)


def test_checkpoint_is_verifiable_and_retains_previous_zip_on_failure(monkeypatch, tmp_path):
    import hashlib
    import zipfile
    output = tmp_path / "campaign"
    output.mkdir()
    write_json(output / "progress.json", dict(validPairedBlocks=3, plannedPairedBlocks=24))
    (output / ".campaign.lock").write_text("live lock")
    destination = tmp_path / "results.zip"
    runner.checkpoint(output, destination)
    original = destination.read_bytes()
    with zipfile.ZipFile(destination) as archive:
        receipt = __import__("json").loads(archive.read("checkpoint.json"))
        assert receipt["inventoryHash"] == digest(receipt["files"])
        assert not any(name.endswith(".campaign.lock") for name in archive.namelist())
        assert receipt["files"] == {name: hashlib.sha256(archive.read(name)).hexdigest()
                                     for name in receipt["files"]}
    monkeypatch.setattr(runner, "sha256", lambda path: "wrong")
    with pytest.raises(ValueError, match="digest"):
        runner.checkpoint(output, destination)
    assert destination.read_bytes() == original
    assert not list(tmp_path.glob(".h2-checkpoint-*"))
    with pytest.raises(ValueError, match="outside"):
        runner.checkpoint(output, output / "recursive.zip")


def test_twelve_block_launch_keeps_preflight_and_withholds_inference(monkeypatch, tmp_path):
    import contextlib
    plan = {**frozen(), **reference(), "runtime": {}}
    source = tmp_path / "frozen"
    source.mkdir()
    output = tmp_path / "output"
    calls, snapshots = [], []
    @contextlib.contextmanager
    def campaign(path, protocol, resume=False):
        path.mkdir()
        yield dict(protocolHash=digest(protocol), environmentId="same-host")
    def execute(parent, scratch, assignment, protocol, reference, paths, identity, **kwargs):
        calls.append((assignment, identity))
        return {"blockIndex": assignment["block"]}
    monkeypatch.setattr(runner, "campaign", campaign)
    monkeypatch.setattr(runner, "verify_frozen", lambda *a, **k: plan)
    monkeypatch.setattr(runner, "validate_runtime", lambda *a: None)
    monkeypatch.setattr(runner, "input_preflight", lambda config: ([], reference()))
    monkeypatch.setattr(runner, "write_artifact_manifest", lambda *a: None)
    monkeypatch.setattr(runner, "check_capacity", lambda *a: None)
    monkeypatch.setattr(runner, "remove_owned", lambda *a: None)
    monkeypatch.setattr(runner, "run_with_retries", execute)
    monkeypatch.setattr(runner, "checkpoint", lambda *a: snapshots.append(len(calls)))
    def forbidden(*a):
        raise AssertionError("no 12-block significance analysis")
    monkeypatch.setattr(runner, "analyze_output", forbidden)
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        monkeypatch.delenv(name, raising=False)
    runner.run(source, output, tmp_path / "scratch", stop_after_blocks=12,
               checkpoint_zip=tmp_path / "checkpoint.zip")
    assert calls[0][1]["measurementRole"] == "excluded preflight"
    assert [c[0] for c in calls[1:]] == plan["schedule"][:12]
    assert all(c[1]["measurementRole"] == "confirmatory" for c in calls[1:])
    assert snapshots == list(range(1, 14)) + [13]
    receipt = protocol.read(output / "launch-completion.json")
    assert receipt["status"] == "partial-campaign"
    assert receipt["validPairedBlocks"] == 12 and receipt["plannedPairedBlocks"] == 24
    assert protocol.read(output / "progress.json")["percentComplete"] == 50
