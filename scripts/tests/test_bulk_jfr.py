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
    assert {"SSTABLE_FORCE", "SSTABLE_VERIFY", "MANIFEST_FORCE", "QUIESCE"} <= set(phases)
    reports = [dict(samples=17, timingsMs=dict(population=100)) for _ in range(3)]
    analysis = analyze(path, tmp_path, reports)
    assert analysis["profilerRatio"] == 1
    assert (tmp_path / "jfr-summary.md").is_file()


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
