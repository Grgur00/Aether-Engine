"""Offline driver contracts; no JVM or real process is killed."""
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace

import concurrency_matrix
import fault_injection
from paper_common import sha256


def test_payload_repeats_index_digest_and_truncates():
    digest = hashlib.sha256(b"3").digest()
    assert concurrency_matrix.payload(3, 35) == digest + digest[:3]
    assert concurrency_matrix.payload(3, 0) == b""


def test_fault_trial_reaches_marker_kills_waits_then_verifies(tmp_path, monkeypatch):
    calls = []

    class Writer:
        returncode = None

        def __init__(self, command, **kwargs):
            calls.append("write")
            Path(command[-3], "boundary.txt").write_text(command[-2])

        def poll(self):
            return self.returncode

        def kill(self):
            calls.append("kill")
            self.returncode = -9

        def wait(self, timeout):
            calls.append("wait")
            return self.returncode

    def verify(command, **kwargs):
        assert command[-4] == "verify"
        calls.append("verify")
        return SimpleNamespace(stdout='probe output\n{"passed": true}\n', stderr="")

    monkeypatch.setattr(fault_injection, "java_classpath", lambda: "fixture")
    monkeypatch.setattr(fault_injection.subprocess, "Popen", Writer)
    monkeypatch.setattr(fault_injection.subprocess, "run", verify)
    root = tmp_path / "trial"
    report = fault_injection.run_trial(root, "after-ack", "batch")
    assert calls == ["write", "kill", "wait", "verify"]
    assert report["boundaryReached"] is True
    assert report["contract"] == "process-crash; not power-loss"
    assert report["resultSha256"] == sha256(root / "result.json")
    assert json.loads((root / "result.json").read_text())["passed"] is True
