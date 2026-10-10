"""Synthetic CPU/mmap contracts with Java transport replaced by a test double."""
from contextlib import contextmanager
import json

import pytest

import transform_evolution
from paper_common import sha256


@pytest.fixture
def fake_java(monkeypatch):
    stores = []

    class Store:
        def __init__(self, **kwargs):
            self.data = {}
            self.closed = False
            stores.append(self)

        def commit_bytes_many(self, rows):
            self.data.update((row["cache_key"], row["data"]) for row in rows)

        def load_cached_bytes_many(self, keys):
            return {key: self.data[key] for key in keys if key in self.data}

        def close(self):
            self.closed = True

    @contextmanager
    def daemon(directory):
        yield {"port": 12345}

    monkeypatch.setattr(transform_evolution, "JavaArtifactStore", Store)
    monkeypatch.setattr(transform_evolution, "java_daemon", daemon)
    monkeypatch.setattr(transform_evolution, "environment", lambda: {"fixture": True})
    return stores


def test_six_scenarios_match_real_mmap_bytes(tmp_path, fake_java):
    report = transform_evolution.run_once(tmp_path)
    assert [(row["scenario"], row["hits"], row["misses"]) for row in report["scenarios"]] == [
        ("unchanged", 8, 0), ("source-content", 7, 1), ("normalize", 0, 8),
        ("resize", 0, 8), ("implementation-version", 0, 8), ("artifact-codec", 0, 8)]
    assert all(row["passed"] for row in report["scenarios"])
    assert len(fake_java) == 6 and all(store.closed for store in fake_java)
    assert not list(tmp_path.glob("evolution-*"))
    assert json.loads((tmp_path / "transform-evolution.json").read_text())["allPassed"]


def test_campaign_records_trial_report_hash_and_refuses_overwrite(tmp_path, fake_java):
    report = transform_evolution.run(tmp_path)
    trial = tmp_path / report["trials"][0]["report"]
    assert report["trials"][0]["sha256"] == sha256(trial)
    with pytest.raises(FileExistsError, match="new output directory"):
        transform_evolution.run(tmp_path)


def test_campaign_requires_positive_repeat_count(tmp_path):
    with pytest.raises(ValueError, match="positive repetition"):
        transform_evolution.run(tmp_path / "unused", repeats=0)
    assert not (tmp_path / "unused").exists()
