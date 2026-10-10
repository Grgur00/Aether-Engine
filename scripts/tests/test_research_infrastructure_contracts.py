"""Documentation contracts on local fixtures/doubles, never research measurements."""
import contextlib
import copy
import io
import json
import math
import os
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace

import pytest

import evidence
import longitudinal_state as stages
import paper_common as common
import persistent_service as persistent
import system_campaign as campaigns


@pytest.fixture
def environment_fixture(monkeypatch):
    report = dict(platform="fixture", python="fixture", cpuCount=1, sourceSha256={"source": "one"},
        performanceEnvironment={"OMP_NUM_THREADS": None}, capturedAt=1, executable="fixture",
        commands=dict(cpu=dict(stdout="Model: fixture\nCPU MHz: 3\nBogoMIPS: 4\nCPU(s) scaling: 5"),
                      java=dict(returncode=0, stdout="java", stderr=""),
                      packages=dict(returncode=0, stdout="fixture==1", stderr=""), gpu=dict(stdout="GPU one")))
    monkeypatch.setattr(campaigns, "java_classpath", lambda: "fixture")
    monkeypatch.setattr(campaigns, "environment", lambda: copy.deepcopy(report))
    monkeypatch.setattr(campaigns.platform, "node", lambda: "fixture-host")
    return report


@pytest.mark.parametrize("value", [math.nan, math.inf, -math.inf])
def test_json_serialization_rejects_nonfinite_without_replacing_prior_file(tmp_path, value):
    path = tmp_path / "receipt.json"
    path.write_text("previous", encoding="utf-8")
    with pytest.raises(ValueError):
        common.write_json(path, {"value": value})
    assert path.read_text(encoding="utf-8") == "previous"
    assert not path.with_suffix(".json.tmp").exists()


def test_json_replace_failure_preserves_destination_but_leaves_fixed_temp(tmp_path, monkeypatch):
    path = tmp_path / "receipt.json"
    path.write_text("previous", encoding="utf-8")
    def fail(*args):
        raise OSError("fixture replace")
    monkeypatch.setattr(common.os, "replace", fail)
    with pytest.raises(OSError, match="fixture replace"):
        common.write_json(path, {"value": 2})
    assert path.read_text(encoding="utf-8") == "previous"
    assert json.loads(path.with_suffix(".json.tmp").read_text()) == {"value": 2}


def test_digest_is_key_order_stable_but_not_numeric_or_list_normalization():
    assert evidence.digest({"a": 1, "b": 2}) == evidence.digest({"b": 2, "a": 1})
    assert evidence.digest(1) != evidence.digest(1.)
    assert evidence.digest([1, 2]) != evidence.digest([2, 1])
    assert isinstance(evidence.digest(math.nan), str)


def test_capture_records_nonzero_exit_and_strips_output(monkeypatch):
    calls = []
    def run(command, **kwargs):
        calls.append((command, kwargs))
        return SimpleNamespace(returncode=7, stdout=" out \n", stderr=" error \n")
    monkeypatch.setattr(common.subprocess, "run", run)
    assert common.capture(["fixture"]) == dict(returncode=7, stdout="out", stderr="error")
    assert calls[0][1] == dict(cwd=common.ROOT, capture_output=True, text=True, timeout=30)


@pytest.mark.parametrize("error", [OSError("fixture unavailable"), subprocess.TimeoutExpired("fixture", 30)])
def test_capture_translates_only_supported_probe_failures(monkeypatch, error):
    def fail(*args, **kwargs):
        raise error
    monkeypatch.setattr(common.subprocess, "run", fail)
    assert "unavailable" in common.capture(["fixture"])


def test_environment_archive_verification_is_subset_and_clean_flag_is_separate(tmp_path, monkeypatch):
    monkeypatch.setattr(common, "ROOT", tmp_path)
    monkeypatch.setattr(common, "capture", lambda command: {"unavailable": "fixture"})
    file = tmp_path / "clients/python/source.py"
    file.parent.mkdir(parents=True)
    file.write_text("fixture", encoding="utf-8")
    extra = tmp_path / "undeclared.dat"
    extra.write_text("not in source patterns", encoding="utf-8")
    archive = dict(sourceClean=True, gitCommit="a" * 40,
                   files={"clients/python/source.py": common.sha256(file)})
    common.write_json(tmp_path / "artifact-provenance.json", archive)
    report = common.environment()
    assert report["archiveProvenance"]["verified"] is True
    assert all("undeclared.dat" not in path for path in report["sourceSha256"])
    file.write_text("changed", encoding="utf-8")
    report = common.environment()
    assert report["archiveProvenance"]["verified"] is False
    assert report["archiveProvenance"]["sourceClean"] is True
    assert report["commands"]["java"] == {"unavailable": "fixture"}


def test_matching_absent_runtime_record_is_not_independently_prohibited(tmp_path, monkeypatch):
    monkeypatch.setattr(common, "ROOT", tmp_path)
    path = tmp_path / "modules/aether-training-cache/build/paper-runtime-classpath.txt"
    path.parent.mkdir(parents=True)
    absent = tmp_path / "absent.jar"
    path.write_text(str(absent), encoding="utf-8")
    assert common.java_runtime_record(absent) == {"kind": "absent"}
    common.write_json(path.with_name("paper-runtime-build.json"), dict(schema="aether-java-build-v1",
        sources=common.java_build_sources(tmp_path), classpathSha256=common.sha256(path),
        runtime={str(absent.absolute()): {"kind": "absent"}}))
    assert common.java_classpath() == str(absent)


def test_identity_filters_dynamic_cpu_lines_and_omits_gpu_and_timestamp(environment_fixture):
    live = campaigns.measurement_identity(environment_fixture)
    assert live["packages"] is environment_fixture["commands"]["packages"]
    assert live["performanceEnvironment"] is environment_fixture["performanceEnvironment"]
    first = copy.deepcopy(live)
    assert first["cpu"] == "Model: fixture"
    environment_fixture["commands"]["gpu"] = {"stdout": "different GPU"}
    environment_fixture["capturedAt"] = 999
    environment_fixture["executable"] = "different path"
    assert campaigns.measurement_identity(environment_fixture) == first
    environment_fixture["commands"]["packages"]["stdout"] = "fixture==2"
    assert campaigns.measurement_identity(environment_fixture) != first


def test_campaign_validates_build_before_environment_and_releases_after_body_error(tmp_path, monkeypatch, environment_fixture):
    calls = []
    monkeypatch.setattr(campaigns, "java_classpath", lambda: calls.append("build"))
    monkeypatch.setattr(campaigns, "environment", lambda: (calls.append("environment"), copy.deepcopy(environment_fixture))[1])
    with pytest.raises(RuntimeError, match="body fixture"):
        with campaigns.campaign(tmp_path, {"fixture": True}):
            raise RuntimeError("body fixture")
    assert calls == ["build", "environment"]
    assert (tmp_path / ".campaign.lock").exists()
    with campaigns.campaign(tmp_path, {"fixture": True}, resume=True):
        pass


def test_saved_campaign_loader_does_not_probe_current_environment(tmp_path, monkeypatch, environment_fixture):
    with campaigns.campaign(tmp_path, {"fixture": True}) as meta:
        pass
    def fail():
        raise AssertionError("no current probes during load")
    monkeypatch.setattr(campaigns, "environment", fail)
    monkeypatch.setattr(campaigns, "java_classpath", fail)
    assert campaigns.load_campaign(tmp_path) == meta


def test_system_result_two_write_failure_leaves_new_report_with_old_receipt(tmp_path, monkeypatch):
    path = tmp_path / "trial.json"
    meta = dict(protocolHash="p", environmentId="e")
    campaigns.save_result(path, {"value": 1}, meta)
    original = campaigns.write_json
    def write(path, value):
        if Path(path).name.endswith(".receipt.json"):
            raise OSError("receipt fixture")
        original(path, value)
    monkeypatch.setattr(campaigns, "write_json", write)
    with pytest.raises(OSError, match="receipt fixture"):
        campaigns.save_result(path, {"value": 2}, meta)
    assert json.loads(path.read_text())["value"] == 2
    with pytest.raises(ValueError, match="altered"):
        campaigns.load_result(path, meta)


def test_stage_receipt_identity_wins_and_loader_accepts_subset_and_whitespace(tmp_path):
    path = tmp_path / "stage.json"
    saved = stages.save_receipt(path, {"backend": "wrong", "extra": 3}, {"backend": "mmap", "version": 0})
    assert saved["backend"] == "mmap"
    path.write_text(json.dumps(json.loads(path.read_text()), separators=(",", ":")), encoding="utf-8")
    assert stages.load_receipt(path, {"backend": "mmap"}) == saved
    with pytest.raises(ValueError, match="provenance"):
        stages.load_receipt(path, {"backend": "aether"})


def test_rotating_pilot_order_does_not_mutate_global_rng():
    import random
    before = random.getstate()
    order = stages.backend_order(0, 0, 42)
    assert stages.backend_order(1, 0, 42) == order[1:] + order[:1]
    assert stages.backend_order(0, 4, 42) == order
    assert random.getstate() == before


@pytest.mark.parametrize("target", ["owner", "outside"])
def test_owned_deletion_refuses_owner_itself_and_outside(tmp_path, target):
    owner = tmp_path / "owner"
    owner.mkdir()
    path = owner if target == "owner" else tmp_path / "outside"
    with pytest.raises(ValueError, match="outside owned"):
        stages.remove_owned(path, owner)
    assert owner.exists()


def test_inventory_rejects_entry_marked_as_symlink_without_hashing(tmp_path, monkeypatch):
    entry = tmp_path / "unsafe"
    entry.write_text("fixture", encoding="utf-8")
    original = Path.is_symlink
    monkeypatch.setattr(Path, "is_symlink", lambda path: path == entry or original(path))
    with pytest.raises(ValueError, match="unsafe cache entry"):
        stages.inventory(tmp_path)


def test_capacity_formula_and_exact_available_threshold(tmp_path):
    required = stages.capacity_required(2, 16)
    assert required == 4 * 2 * 16 ** 2 * 3 * 12 + 512 * 1024 ** 2
    assert stages.check_capacity(tmp_path, required, free=required)["freeBytes"] == required
    with pytest.raises(RuntimeError, match="insufficient"):
        stages.check_capacity(tmp_path, required, free=required - 1)


def test_committed_stage_fast_path_does_not_check_live_store_or_lease(tmp_path, monkeypatch):
    reports, scratch = tmp_path / "reports", tmp_path / "scratch"
    identity = dict(protocolHash="fixture")
    stages.save_receipt(reports / "v0-mmap.json", {"storeInventory": {"missing": "fixture"}},
                        {**identity, "backend": "mmap", "version": 0})
    common.write_json(reports / "v0-mmap.lease.json", {"pids": [os.getpid()]})
    def fail(*args):
        raise AssertionError("not queried on committed receipt fast path")
    monkeypatch.setattr(stages, "pid_alive", fail)
    assert stages.execute_stage(scratch, reports, "mmap", 0, identity, fail)["storeInventory"] == {"missing": "fixture"}
    assert not scratch.exists()


def test_stage_receipt_can_commit_before_prior_checkpoint_cleanup_fails(tmp_path, monkeypatch):
    reports, scratch = tmp_path / "reports", tmp_path / "scratch"
    reports.mkdir()
    def worker(backend, version, live):
        (live / "data").write_text(str(version), encoding="utf-8")
        return dict(timingsMs={"work": 1.}, fullLifecycleMs=1.)
    stages.execute_stage(scratch, reports, "mmap", 0, {}, worker)
    original = stages.remove_owned
    def fail(path, owner):
        if Path(path).name == "checkpoint-v0":
            raise OSError("cleanup fixture")
        original(path, owner)
    monkeypatch.setattr(stages, "remove_owned", fail)
    with pytest.raises(OSError, match="cleanup fixture"):
        stages.execute_stage(scratch, reports, "mmap", 1, {}, worker)
    assert (scratch / "mmap/checkpoint-v0").exists()
    assert (reports / "v1-mmap.json").exists()
    def forbidden(*args):
        raise AssertionError("committed stage must not rerun")
    assert stages.execute_stage(scratch, reports, "mmap", 1, {}, forbidden)["version"] == 1


def test_cumulative_is_not_a_five_stage_or_fixed_phase_validator():
    assert stages.cumulative([]) == {}
    assert stages.cumulative([dict(timingsMs={"arbitrary": True}, fullLifecycleMs=1.)]) == {"V0": 1.}
    accepted = stages.cumulative([dict(timingsMs={"work": 1.}, fullLifecycleMs=math.nan)])
    assert math.isnan(accepted["V0"])


def test_completed_service_flag_bypasses_freshness_but_loads_no_receipts(tmp_path):
    scratch, reports = tmp_path / "scratch", tmp_path / "reports"
    scratch.mkdir()
    reports.mkdir()
    (reports / "persistent-block-started.json").write_text("not valid JSON", encoding="utf-8")
    with persistent.PersistentService(scratch, reports, {}, completed=True) as service:
        assert service.daemon is None


def test_service_startup_factory_owns_cleanup_when_enter_fails(tmp_path):
    calls = []
    class Manager:
        def __enter__(self):
            calls.append("enter")
            raise OSError("startup fixture")
        def __exit__(self, *args):
            calls.append("exit")
    with pytest.raises(OSError, match="startup fixture"):
        with persistent.PersistentService(tmp_path / "scratch", tmp_path / "reports", {}, factory=lambda path: Manager()) as service:
            service.run_stage("aether", 0, lambda *args: None)
    assert calls == ["enter"]
    assert (tmp_path / "reports/persistent-block-started.json").exists()


def test_service_does_not_suppress_body_error_even_if_manager_exit_requests_it(tmp_path):
    calls = []
    class Manager:
        def __enter__(self):
            return {"pid": 42, "port": 1234}
        def __exit__(self, *args):
            calls.append(args[0])
            return True
    def fail(*args):
        raise RuntimeError("worker fixture")
    with pytest.raises(RuntimeError, match="worker fixture"):
        with persistent.PersistentService(tmp_path / "scratch", tmp_path / "reports", {}, factory=lambda path: Manager()) as service:
            service.run_stage("aether", 0, fail)
    assert calls == [RuntimeError]
    assert not (tmp_path / "reports/persistent-service.lease.json").exists()


@pytest.mark.parametrize("mode", ["normal", "undrained", "failed", "disconnect", "terminate-timeout", "eof"])
def test_daemon_port_drain_export_and_cleanup_with_owned_process_double(tmp_path, monkeypatch, mode):
    events = []
    class Process:
        pid = 42
        stdout = io.StringIO("noise\n12345\n" if mode != "eof" else "noise\n")
        def poll(self):
            return None
        def terminate(self):
            events.append("terminate")
        def kill(self):
            events.append("kill")
        def wait(self, timeout):
            events.append(("wait", timeout))
            if mode == "terminate-timeout" and "kill" not in events:
                raise subprocess.TimeoutExpired("fixture", timeout)
            return 0
    process = Process()
    def launch(command, **kwargs):
        assert command[:3] == ["java", "-Xmx64m", "--enable-preview"]
        assert command[-2:] == ["256", "DURABLE"]
        events.append("launch")
        return process
    @contextlib.contextmanager
    def client(**kwargs):
        assert kwargs == {"port": 12345}
        def drain():
            events.append("drain")
            if mode == "disconnect":
                raise RuntimeError("disconnect fixture")
            return dict(drained=mode != "undrained", backgroundCompaction=dict(failed=int(mode == "failed")))
        yield SimpleNamespace(wait_for_background_compaction=drain)
    monkeypatch.setattr(common, "java_classpath", lambda: "fixture-classpath")
    monkeypatch.setattr(common.subprocess, "Popen", launch)
    monkeypatch.setitem(sys.modules, "aether_training_cache.client", SimpleNamespace(AetherTrainingCache=client))
    def run():
        with common.java_daemon(tmp_path / "live", maximum_bytes=256, jvm_options=("-Xmx64m",)) as daemon:
            assert daemon["pid"] == 42 and daemon["port"] == 12345
            events.append("body")
    if mode in {"undrained", "failed", "disconnect", "eof"}:
        with pytest.raises(RuntimeError):
            run()
    else:
        run()
    assert "terminate" in events and ("wait", 15) in events
    assert process.stdout.closed
    assert ("kill" in events) == (mode == "terminate-timeout")
    if mode == "eof":
        assert "body" not in events and "drain" not in events
    else:
        assert events.index("body") < events.index("drain") < events.index("terminate")
        assert bool(list(tmp_path.glob("live.compaction-*.json"))) == (mode != "disconnect")
