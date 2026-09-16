"""Explicit provenance fixtures and failure-path tests, never research measurements."""
import copy
import queue
import threading

import pytest

import system_campaign as campaigns
import concurrency_matrix as concurrency


@pytest.fixture
def fixture_environment(monkeypatch):
    report = {"platform": "TEST FIXTURE", "python": "fixture", "cpuCount": 1,
              "performanceEnvironment": {}, "sourceSha256": {"fixture.py": "fixture"},
              "commands": {"cpu": {"stdout": "Model name: TEST FIXTURE\nCPU MHz: 1"},
                           "java": {"stdout": "fixture"}, "packages": {"stdout": "fixture"}}}
    monkeypatch.setattr(campaigns, "environment", lambda: copy.deepcopy(report))
    monkeypatch.setattr(campaigns, "java_classpath", lambda: "TEST FIXTURE")
    return report


def test_resume_preserves_environment_and_rejects_protocol_or_source_change(tmp_path, fixture_environment):
    protocol = {"kind": "TEST FIXTURE"}
    with campaigns.campaign(tmp_path, protocol) as first:
        campaigns.save_result(tmp_path / "trial.json", {"testFixture": True}, first)
    original = (tmp_path / "environment.json").read_bytes()
    with pytest.raises(FileExistsError):
        with campaigns.campaign(tmp_path, protocol):
            pass
    with campaigns.campaign(tmp_path, protocol, resume=True) as resumed:
        assert resumed == first
        assert campaigns.load_result(tmp_path / "trial.json", resumed)["testFixture"] is True
    with pytest.raises(ValueError, match="same protocol"):
        with campaigns.campaign(tmp_path, {"kind": "CHANGED"}, resume=True):
            pass
    fixture_environment["sourceSha256"]["fixture.py"] = "changed"
    with pytest.raises(ValueError, match="same protocol"):
        with campaigns.campaign(tmp_path, protocol, resume=True):
            pass
    assert (tmp_path / "environment.json").read_bytes() == original


def test_result_and_environment_tampering_rejected(tmp_path, fixture_environment):
    with campaigns.campaign(tmp_path, {"kind": "TEST FIXTURE"}) as metadata:
        campaigns.save_result(tmp_path / "trial.json", {"value": 1}, metadata)
    (tmp_path / "trial.json").write_text('{}')
    with pytest.raises(ValueError, match="altered"):
        campaigns.load_result(tmp_path / "trial.json", metadata)
    (tmp_path / "environment.json").write_text('{}')
    with pytest.raises(ValueError, match="altered"):
        campaigns.load_campaign(tmp_path)


def test_existing_output_never_has_metadata_overwritten(tmp_path, fixture_environment):
    path = tmp_path / "environment.json"
    path.write_bytes(b"previous run metadata")
    with pytest.raises(FileExistsError, match="unversioned"):
        with campaigns.campaign(tmp_path, {"kind": "TEST FIXTURE"}):
            pass
    assert path.read_bytes() == b"previous run metadata"


def test_campaign_lock_rejects_second_owner_and_releases(tmp_path, fixture_environment):
    protocol = {"kind": "TEST FIXTURE"}
    with campaigns.campaign(tmp_path, protocol):
        with pytest.raises(RuntimeError, match="another process"):
            with campaigns.campaign(tmp_path, protocol, resume=True):
                pass
    with campaigns.campaign(tmp_path, protocol, resume=True):
        pass


def test_worker_initialization_failure_notifies_start_barrier(monkeypatch):
    def fail(**kwargs):
        raise OSError("explicit failed connection fixture")
    monkeypatch.setattr(concurrency, "JavaArtifactStore", fail)
    ready, results = queue.Queue(), queue.Queue()
    concurrency.worker("aether", "unused", 0, [0], 1, 8, ready, threading.Event(), results)
    assert "failed connection fixture" in ready.get_nowait()["error"]
    assert results.get_nowait()["passed"] is False
