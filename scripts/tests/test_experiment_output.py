import json

import pytest

from experiment_output import exclusive_output, freeze_metadata


def test_resume_keeps_original_environment_and_refuses_changed_host(tmp_path):
    plan = {"fixture": True}
    environment = {"measurementIdentity": {"host": "TEST FIXTURE"}, "sourceSha256": {"fixture": "fixture"}, "capturedAt": 1}
    freeze_metadata(tmp_path, plan, "fixture", environment, False)
    original = (tmp_path / "environment-fixture.json").read_bytes()
    freeze_metadata(tmp_path, plan, "fixture", {**environment, "capturedAt": 2}, True)
    assert (tmp_path / "environment-fixture.json").read_bytes() == original
    with pytest.raises(ValueError, match="environment"):
        freeze_metadata(tmp_path, plan, "different-host", environment, True)
    assert not (tmp_path / "environment-different-host.json").exists()
    with pytest.raises(FileExistsError, match="--resume"):
        freeze_metadata(tmp_path, plan, "fixture", environment, False)
    with pytest.raises(ValueError, match="protocol"):
        freeze_metadata(tmp_path, {"changed": True}, "fixture", environment, True)
    assert json.loads((tmp_path / "protocol.json").read_text()) == plan


def test_output_lock_cannot_be_owned_twice(tmp_path):
    with exclusive_output(tmp_path):
        with pytest.raises(RuntimeError, match="another runner"):
            with exclusive_output(tmp_path):
                pytest.fail("two owners acquired output")
    with exclusive_output(tmp_path):
        pass
