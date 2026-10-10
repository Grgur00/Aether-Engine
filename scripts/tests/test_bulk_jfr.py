import json
import os
import shutil
import subprocess

import pytest

from bulk_jfr_analyze import analyze, seconds
from bulk_population import BulkPipeWriter, BulkPublicationClient
from aether_training_cache.client import CacheKey, TransformationFingerprint


def test_jfr_durations():
    assert seconds("PT1M0.25S") == 60.25
    assert seconds("PT0.000012S") == .000012
    with pytest.raises(ValueError):
        seconds("bad")


def test_summary_requires_successful_unique_population(tmp_path):
    path = tmp_path / "events.json"
    path.write_text(json.dumps(dict(recording=dict(events=[]))))
    with pytest.raises(ValueError, match="exactly one"):
        analyze(path, tmp_path, [])


def test_candidate_analysis_scopes_allocation_to_verifying_thread(tmp_path):
    start = "2026-10-01T00:00:00Z"
    def event(kind, **values):
        return dict(type=kind, values=dict(startTime=start, **values))
    stack = dict(frames=[dict(method=dict(type=dict(name="java/util/Arrays"), name="copyOfRange"))])
    population = event("aether.BulkPopulation", success=True, artifactCount=1200,
                       payloadBytes=236158800, duration="PT2S")
    marker = event("aether.BulkPhase", phase="SSTABLE_VERIFY", duration="PT1S", eventThread=dict(javaThreadId=7))
    allocation = event("jdk.ObjectAllocationSample", eventThread=dict(javaThreadId=7),
                       objectClass=dict(name="[B"), weight=1024, stackTrace=stack)
    unrelated = event("jdk.ObjectAllocationSample", eventThread=dict(javaThreadId=8),
                      objectClass=dict(name="[B"), weight=9999, stackTrace=stack)
    path = tmp_path / "events.json"
    events = [population, marker, allocation, unrelated]
    path.write_text(json.dumps(dict(recording=dict(events=events))))
    result = analyze(path, tmp_path, [dict(samples=1200, timingsMs=dict(population=17000))])
    assert result["profilerRatio"] is None
    detail = result["authoritativeVerification"]
    assert detail["weightedAllocationBytes"] == {"[B": 1024}
    assert detail["allocationStacks"] == [("java.util.Arrays.copyOfRange", 1024)]
    events.append(marker)
    path.write_text(json.dumps(dict(recording=dict(events=events))))
    with pytest.raises(ValueError, match="exactly one authoritative"):
        analyze(path, tmp_path, [dict(samples=1200)])


def test_candidate_only_orchestration_launches_one_profile(tmp_path, monkeypatch):
    import contextlib
    from pathlib import Path
    import profile_bulk_jfr as runner
    manifest = tmp_path / "v0.csv"
    manifest.write_text("frozen")
    monkeypatch.setattr(runner.shutil, "which", lambda name: "jfr")
    monkeypatch.setattr(runner, "workload_args", lambda *args: None)
    monkeypatch.setattr(runner.base.workload, "load_sources", lambda args: [1])
    monkeypatch.setattr(runner.base, "CanonicalTransform", lambda args: lambda item: item)
    monkeypatch.setattr(runner.base, "tensor_digest", lambda items: "reference")
    monkeypatch.setattr(runner, "check_capacity", lambda *args: None)
    import profile_bulk_verification
    validations = []
    monkeypatch.setattr(profile_bulk_verification, "validate_report", lambda *args: validations.append(args))
    @contextlib.contextmanager
    def campaign(output, protocol):
        output.mkdir()
        assert protocol["sequence"] == ["jfr-run"]
        assert protocol["priorPerformanceGate"] == "failed; unchanged"
        yield dict(protocolHash="hash", environmentId="host")
    monkeypatch.setattr(runner, "campaign", campaign)
    calls = []
    def execute(command, **kwargs):
        calls.append(command)
        if "--request" in command:
            request = json.loads(Path(command[command.index("--request") + 1]).read_text())
            assert request["samples"] == 1200 and request["jfrSettings"] == "profile"
            Path(request["jfrFile"]).write_bytes(b"fixture recording")
            Path(command[command.index("--output") + 1]).write_text(json.dumps(dict(samples=1200)))
    monkeypatch.setattr(runner.subprocess, "run", execute)
    monkeypatch.setattr(runner, "analyze", lambda events, output, reports: len(reports) == 1)
    output = tmp_path / "results"
    runner.run(output, manifest, scratch_root=tmp_path / "scratch", candidate_only=True)
    assert len(validations) == 1 and validations[0][1:] == ("candidate", "reference")
    assert len([c for c in calls if "--request" in c]) == 1
    assert json.loads((output / "completion.json").read_text())["runs"] == 1


@pytest.mark.skipif(os.environ.get("AETHER_JAVA_TEST") != "1", reason="real JVM/JFR required")
def test_real_jfr_pipe_preserves_protocol_and_contains_coarse_events(tmp_path):
    recording = tmp_path / "bulk.jfr"
    values = [(CacheKey("jfr-test", str(i), TransformationFingerprint.from_descriptor("same")), bytes([i]) * 1024)
              for i in range(17)]
    with BulkPipeWriter(tmp_path / "store", jfr_file=recording) as writer:
        BulkPublicationClient(writer).put_many(values)
        receipt = writer.finish()
    assert receipt["sha256Calls"] == 17
    result = subprocess.run([shutil.which("jfr"), "print", "--json", "--events", "aether.*", str(recording)],
                            check=True, capture_output=True, text=True)
    path = tmp_path / "events.json"
    path.write_text(result.stdout, encoding="utf-8")
    events = json.loads(result.stdout)["recording"]["events"]
    populations = [e for e in events if e["type"] == "aether.BulkPopulation"]
    assert len(populations) == 1 and populations[0]["values"]["artifactCount"] == 17
    phases = [e["values"]["phase"] for e in events if e["type"] == "aether.BulkPhase"]
    assert phases.count("INTEGRITY") == 2
    assert phases.count("REQUEST_DECODE") == 2
    assert phases.count("SSTABLE_VERIFY") == 1
    verification = receipt["storage"]["verification"]
    assert verification["inventoryCalls"] == 1
    assert verification["tablesFullyVerified"] == receipt["storage"]["tables"]
    assert {"SSTABLE_FORCE", "SSTABLE_VERIFY", "MANIFEST_FORCE", "QUIESCE"} <= set(phases)
    reports = [dict(samples=17, timingsMs=dict(population=100)) for _ in range(3)]
    analysis = analyze(path, tmp_path, reports)
    assert analysis["profilerRatio"] == 1
    assert (tmp_path / "jfr-summary.md").is_file()
    single = analyze(path, tmp_path, reports[:1])
    assert single["profilerRatio"] is None
    assert single["authoritativeVerification"]["durationSeconds"] >= 0


@pytest.mark.skipif(os.environ.get("AETHER_JAVA_TEST") != "1", reason="real JVM/JFR sequence required")
def test_control_profile_control_uses_same_fixture_and_retains_recording(tmp_path):
    from test_monai_comparison import fixture_data
    from profile_bulk_jfr import run
    fixture_data(tmp_path / "inputs")
    output = tmp_path / "results"
    run(output, tmp_path / "inputs/v2.csv", samples=5, scratch_root=tmp_path / "scratch", smoke=True)
    assert json.loads((output / "completion.json").read_text())["recordings"] == 1
    assert (output / "aether-bulk-32.jfr").stat().st_size > 1000
    assert len(list(output.glob("*.jfr"))) == 1
    assert (output / "checksums.sha256").is_file()
