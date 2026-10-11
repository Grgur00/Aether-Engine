import hashlib
import json

import pytest

from h4.mmap_backend import MmapBackend
from h4.prepare_pcam_manifest import PAYLOAD_BYTES, canonical_hash


def sample(index):
    payload = bytes([index % 256]) * PAYLOAD_BYTES
    return {"sample_id": index, "artifact_id": hashlib.sha256(str(index).encode()).hexdigest(),
            "artifact_sha256": hashlib.sha256(payload).hexdigest()}, payload


def test_durable_reopen_direct_offsets_and_complete_validation(tmp_path):
    samples = [sample(index) for index in [4, 2, 7]]
    with MmapBackend(tmp_path) as backend:
        result = backend.append(samples)
        assert result["appended"] == 3
        assert backend.offsets == {4: 0, 2: PAYLOAD_BYTES, 7: 2 * PAYLOAD_BYTES}
    with MmapBackend(tmp_path) as backend:
        assert backend.validate([row for row, _ in samples])["validated_samples"] == 3
        with backend.get(7) as view:
            assert view.readonly
            assert bytes(view) == samples[2][1]
        with pytest.raises(KeyError):
            backend.get(99)


def test_incremental_reuse_never_overwrites_committed_payload(tmp_path):
    with MmapBackend(tmp_path) as backend:
        backend.append([sample(1), sample(2)])
        prefix = (tmp_path / "tensors.bin").read_bytes()
        result = backend.append([sample(2), sample(3)])
        assert result["reused"] == result["appended"] == 1
        assert (tmp_path / "tensors.bin").read_bytes()[:len(prefix)] == prefix
        assert backend.validate([sample(i)[0] for i in [1, 2, 3]])["correctness_passed"]


def test_failed_append_preserves_publication_and_recovers_orphan_tail(tmp_path):
    with MmapBackend(tmp_path) as backend:
        backend.append([sample(1)])
        previous = (tmp_path / "index.json").read_bytes()
        bad_row, _ = sample(3)
        with pytest.raises(ValueError, match="checksum"):
            backend.append([sample(2), (bad_row, bytes(PAYLOAD_BYTES))])
        assert (tmp_path / "index.json").read_bytes() == previous
    with MmapBackend(tmp_path) as backend:
        assert backend.metrics()["unpublished_tail_bytes"] == PAYLOAD_BYTES
        assert backend.validate([sample(1)[0]])["correctness_passed"]
        backend.append([sample(3)])
        assert backend.metrics()["unpublished_tail_bytes"] == 0
        assert backend.validate([sample(1)[0], sample(3)[0]])["correctness_passed"]


def test_corrupt_payload_is_not_hidden_by_valid_index(tmp_path):
    with MmapBackend(tmp_path) as backend:
        backend.append([sample(1)])
    with (tmp_path / "tensors.bin").open("r+b") as stream:
        stream.write(b"x")
    with MmapBackend(tmp_path) as backend:
        with pytest.raises(ValueError, match="checksum"):
            backend.validate([sample(1)[0]])


def test_duplicate_manifest_membership_is_rejected(tmp_path):
    with MmapBackend(tmp_path) as backend:
        backend.append([sample(1)])
        with pytest.raises(ValueError, match="duplicate"):
            backend.validate([sample(1)[0], sample(1)[0]])


def test_changed_identity_duplicate_and_short_payload_rejected(tmp_path):
    with MmapBackend(tmp_path) as backend:
        backend.append([sample(1)])
        changed, payload = sample(1)
        changed["artifact_id"] = "0" * 64
        with pytest.raises(ValueError, match="identity changed"):
            backend.append([(changed, payload)])
        with pytest.raises(ValueError, match="duplicate"):
            backend.append([sample(2), sample(2)])
        with pytest.raises(ValueError, match="size/checksum"):
            backend.append([(sample(2)[0], b"short")])


def test_writer_lock_fails_closed_without_removing_other_lock(tmp_path):
    lock = tmp_path / "writer.lock"
    lock.write_text("existing owner")
    with MmapBackend(tmp_path) as backend:
        with pytest.raises(FileExistsError):
            backend.append([sample(1)])
    assert lock.read_text() == "existing owner"


def test_corrupt_and_truncated_index_fail_before_mapping(tmp_path):
    with MmapBackend(tmp_path) as backend:
        backend.append([sample(1)])
    (tmp_path / "tensors.bin").write_bytes(b"short")
    with pytest.raises(ValueError, match="shorter"):
        MmapBackend(tmp_path)
    publication = json.loads((tmp_path / "index.json").read_text())
    publication["sha256"] = "0" * 64
    (tmp_path / "index.json").write_text(json.dumps(publication))
    with pytest.raises(ValueError, match="integrity"):
        MmapBackend(tmp_path)
