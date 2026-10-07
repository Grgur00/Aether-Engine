import json
import os
from pathlib import Path

import pytest

from longitudinal_comparison import validate_config, validate_stage


def test_frozen_h2_protocol_rejects_storage_and_training_drift():
    config = json.loads((Path(__file__).resolve().parents[2] / "configs/paper/oct5k-h2-streaming-pilot.json").read_text())
    validate_config(config)
    for field, value in (("trainV0", False), ("targetSstableBytes", 67108864),
                         ("storageCommit", "different"), ("serviceLifecycle", "restart-per-version")):
        with pytest.raises(ValueError, match="frozen H2"):
            validate_config({**config, field: value})


def test_h2_v0_cannot_omit_training():
    report = dict(preprocessCalls=1200, trainingCacheMisses=0, uniqueArtifacts=1200,
                  reusedSamples=0, trainingSampleRequests=0, epochs=[])
    with pytest.raises(ValueError, match="accounting"):
        validate_stage(report, dict(versions=[1200], epochs=20, trainV0=True), 0)


@pytest.mark.skipif(os.environ.get("AETHER_JAVA_TEST") != "1", reason="real same-JVM bulk/training lifecycle")
def test_real_h2_bulk_training_same_pid_all_versions_and_resume(tmp_path):
    from test_longitudinal_comparison import exercise_real_campaign
    exercise_real_campaign(tmp_path, "persistent-per-block", h2=True)


@pytest.mark.skipif(os.environ.get("AETHER_JAVA_TEST") != "1", reason="real bootstrap abort")
def test_bootstrap_disconnect_does_not_publish_staged_values(tmp_path):
    from h2_bootstrap import BootstrapWriter, h2_daemon
    from bulk_population import BulkPublicationClient
    from paper_common import java_daemon
    from aether_training_cache.client import AetherTrainingCache, CacheKey, TransformationFingerprint
    store = tmp_path / "store"
    key = CacheKey("h2-abort", "one", TransformationFingerprint.from_descriptor("v1"))
    with h2_daemon(store) as service:
        with BootstrapWriter(service) as writer:
            BulkPublicationClient(writer).put_many([(key, b"staged")])
    with java_daemon(store) as daemon:
        with AetherTrainingCache(port=daemon["port"]) as client:
            assert client.get_many([key]) == {}
