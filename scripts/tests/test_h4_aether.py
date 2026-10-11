import hashlib
from contextlib import contextmanager

import pytest

from h4.aether_backend import AetherBackend
from h4.prepare_pcam_manifest import PAYLOAD_BYTES


def sample(index):
    payload = bytes([index]) * PAYLOAD_BYTES
    return dict(sample_id=index, artifact_id=hashlib.sha256(str(index).encode()).hexdigest(),
                artifact_sha256=hashlib.sha256(payload).hexdigest()), payload


class Packed:
    def __init__(self, values):
        self.values = values
        self.closed = False

    def value(self, index):
        return memoryview(self.values[index]) if self.values[index] is not None else None

    def close(self):
        self.closed = True


class Client:
    def __init__(self, **kwargs):
        self.values = {}
        self.batches = []
        self.failed = 0
        self.closed = False
        self.drains = 0

    def engine_info(self):
        return dict(durability="DURABLE", pid=123, cacheEntries=len(self.values))

    def put_many(self, batch):
        self.batches.append(len(batch))
        for key, payload in batch:
            self.values[key] = payload

    def get_many_values(self, keys):
        self.packed = Packed([self.values.get(key) for key in keys])
        return self.packed

    def wait_for_background_compaction(self, **kwargs):
        self.drains += 1
        return dict(drained=True, backgroundCompaction=dict(failed=self.failed))

    def close(self):
        self.closed = True


@pytest.fixture
def backend(tmp_path):
    events = []

    @contextmanager
    def daemon(directory):
        try:
            yield dict(port=12345, pid=123)
        finally:
            events.append("closed")

    instance = AetherBackend(tmp_path, "0" * 64, daemon_factory=daemon, client_factory=Client)
    instance.open()
    yield instance, events
    instance.close()


def test_online_population_is_bounded_and_validates_identity(backend):
    instance, _ = backend
    records = [sample(index) for index in range(33)]
    assert instance.populate(iter(records))["samples"] == 33
    assert instance.client.batches == [16, 16, 1]
    assert instance.validate([row for row, _ in records])["correctness_passed"]
    changed = dict(records[0][0], artifact_id="f" * 64)
    with pytest.raises(ValueError, match="identity"):
        instance.validate([changed] + [row for row, _ in records[1:]])


def test_missing_read_releases_packed_batch_and_failed_compaction_is_invalid(backend):
    instance, _ = backend
    instance.populate([sample(1)])
    instance.client.values.clear()
    with pytest.raises(ValueError, match="missing"):
        with instance.get_many([1]):
            pass
    assert instance.client.packed.closed
    instance.client.failed = 1
    with pytest.raises(RuntimeError, match="quiescence"):
        instance.wait_for_quiescence()


def test_daemon_still_closes_if_client_close_fails(backend):
    instance, events = backend

    def failure():
        raise OSError("socket close failed")

    instance.client.close = failure
    with pytest.raises(OSError):
        instance.close()
    assert events == ["closed"]
    assert instance.manager is instance.client is None


def test_population_drains_every_sixteen_batches_without_retry(backend):
    instance, _ = backend
    report = instance.populate(sample(index) for index in range(256))
    assert instance.client.batches == [16] * 16
    assert instance.client.drains == 1
    assert report["drain_interval_artifacts"] == 256
