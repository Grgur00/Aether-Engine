import json

from h4.backend_worker import run_backend
from h4.prepare_pcam_manifest import PAYLOAD_BYTES
from h4.storage_preflight import synthetic_sample


def fixture(tmp_path):
    records = [synthetic_sample(index) for index in range(32)]
    condition = dict(sequential_ids=list(range(32)), shuffled_ids=list(reversed(range(32))),
                     samples=32, payload_bytes=32 * PAYLOAD_BYTES)
    binding = dict(manifest=dict(conditions=[condition]), rows=[row for row, _ in records],
                   protocol=dict(memory_limit_bytes=2 * 1024 ** 3, access_patterns=["sequential", "shuffled"]),
                   protocol_sha256="0" * 64, manifest_sha256="1" * 64, dataset_sha256="2" * 64,
                   transform_sha256="3" * 64, source_verified=False)
    scratch = tmp_path / "scratch"
    scratch.mkdir()
    task = dict(backend="mmap", condition=0, scratch=str(scratch), data="injected-test-stream",
                progress=str(tmp_path / "progress.json"))
    return records, binding, task, scratch


def test_real_mmap_lifecycle_both_patterns_reopen_and_cleanup(tmp_path):
    records, binding, task, scratch = fixture(tmp_path)
    result = run_backend(task, binding, stream_factory=lambda *_: iter(records))
    assert result["passed"]
    assert not result["source_verified"]
    assert not result["primary_h4_eligible"]
    assert result["reopen_validation"]["validated_samples"] == 32
    assert set(result["patterns"]) == {"sequential", "shuffled"}
    assert all(record["correctness_passed"] for record in result["patterns"].values())
    assert result["scratch_cleanup_completed"]
    assert not list(scratch.iterdir())
    assert len(json.loads((tmp_path / "progress.json").read_text())["patterns"]) == 2


def test_population_failure_is_retained_without_publishing_success(tmp_path):
    records, binding, task, scratch = fixture(tmp_path)
    row, _ = records[1]
    records[1] = (row, b"corrupt")
    result = run_backend(task, binding, stream_factory=lambda *_: iter(records))
    assert not result["passed"]
    assert result["error_type"] == "ValueError"
    assert result["patterns"] == {}
    assert result["scratch_cleanup_completed"]
    assert not list(scratch.iterdir())
