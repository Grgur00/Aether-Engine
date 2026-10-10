"""Offline checks; fake Torch does not establish actual CUDA behavior."""
import json
import sys
from types import SimpleNamespace

import pytest

import checksums
import validate_gpu


def invoke_checksums(monkeypatch, root, verify=False):
    monkeypatch.setattr(sys, "argv", ["checksums", str(root)] + (["--verify"] if verify else []))
    checksums.main()


def test_checksums_accept_extra_files_but_reject_recorded_drift(tmp_path, monkeypatch):
    source = tmp_path / "recorded.txt"
    source.write_bytes(b"original")
    invoke_checksums(monkeypatch, tmp_path)
    (tmp_path / "extra.txt").write_bytes(b"not recorded")
    invoke_checksums(monkeypatch, tmp_path, True)
    source.write_bytes(b"changed")
    with pytest.raises(ValueError, match="checksum mismatch"):
        invoke_checksums(monkeypatch, tmp_path, True)


def test_checksums_reject_escape_and_empty_recording_parse(tmp_path, monkeypatch):
    invoke_checksums(monkeypatch, tmp_path)
    with pytest.raises(ValueError):
        invoke_checksums(monkeypatch, tmp_path, True)
    (tmp_path / "SHA256SUMS").write_text("unused  ../escape.txt\n")
    with pytest.raises(ValueError, match="checksum mismatch"):
        invoke_checksums(monkeypatch, tmp_path, True)


def test_gpu_preflight_requires_available_cuda(monkeypatch):
    monkeypatch.setitem(sys.modules, "torch", SimpleNamespace(
        cuda=SimpleNamespace(is_available=lambda: False), version=SimpleNamespace(cuda="fake")))
    with pytest.raises(SystemExit, match="cannot access"):
        validate_gpu.main([])


def test_gpu_preflight_reports_fake_matmul_without_numeric_assertion(monkeypatch, capsys):
    class Tensor:
        def __matmul__(self, other):
            return self

        def sum(self):
            return 123.0

    torch = SimpleNamespace(__version__="fake", version=SimpleNamespace(cuda="fake"),
        ones=lambda shape, device: Tensor(), cuda=SimpleNamespace(is_available=lambda: True,
        current_device=lambda: 0, get_device_properties=lambda device: SimpleNamespace(
            major=7, minor=5, total_memory=1024), synchronize=lambda: None,
        get_device_name=lambda device: "fixture"))
    monkeypatch.setitem(sys.modules, "torch", torch)
    monkeypatch.setattr(validate_gpu.subprocess, "run", lambda *a, **kw: SimpleNamespace(returncode=1, stdout=""))
    validate_gpu.main([])
    report = json.loads(capsys.readouterr().out)
    assert report["matmulSum"] == 123.0
    assert report["nvidiaSmi"] == "unavailable"
