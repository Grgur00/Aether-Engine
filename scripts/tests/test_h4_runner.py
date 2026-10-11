import json
from pathlib import Path

import pytest

from h4 import controlled
from h4.common import write_json
from h4.h4_runner import archive_checksums, backend_order, host_class_hash, source_identity, validate_checkpoint


def test_pilot_order_is_counterbalanced_and_recorded_for_fifth_block():
    assert backend_order(0, 42) == backend_order(2, 42) == ["aether", "mmap"]
    assert backend_order(1, 42) == backend_order(3, 42) == ["mmap", "aether"]
    assert backend_order(4, 42) == ["aether", "mmap"]
    assert backend_order(4, 43) == ["mmap", "aether"]
    with pytest.raises(ValueError):
        backend_order(5, 42)


def test_source_identity_binds_engine_and_h4_but_not_generated_outputs():
    result = source_identity()
    assert "scripts/h4/h4_runner.py" in result["files"]
    assert "scripts/h4/requirements.lock" in result["files"]
    assert "scripts/h4/protocol.json" in result["files"]
    assert "settings.gradle.kts" in result["files"]
    assert "gradle.properties" in result["files"]
    assert "modules/aether-engine/src/main/java/io/aetherdb/engine/EmptyStoreBulkLoader.java" in result["files"]
    assert all(not name.startswith("build/") for name in result["files"])
    assert len(result["sha256"]) == 64


def test_source_identity_changes_when_dependency_lock_or_build_settings_change(tmp_path):
    for name in ("scripts/paper_common.py", "scripts/h4/requirements.lock",
                 "scripts/h4/protocol.json", "settings.gradle.kts"):
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("fixture")
    before = source_identity(tmp_path)["sha256"]
    (tmp_path / "scripts/h4/requirements.lock").write_text("changed dependency")
    after = source_identity(tmp_path)["sha256"]
    assert before != after
    (tmp_path / "settings.gradle.kts").write_text("changed build settings")
    assert source_identity(tmp_path)["sha256"] != after


def test_controller_failure_has_evidence_and_never_launches_unrestricted(tmp_path, monkeypatch):
    def fail():
        raise RuntimeError("memory controller not delegated")
    monkeypatch.setattr(controlled, "discover_parent", fail)
    monkeypatch.setattr(controlled.subprocess, "run", lambda *a, **k: pytest.fail("must not launch unrestricted"))
    result = controlled.launch(tmp_path / "task.json", tmp_path / "trial", limit=2 * 1024 ** 3, timeout_seconds=60)
    assert not result["completed"]
    assert not result["parent_modified"]
    assert "not delegated" in result["error"]
    assert json.loads((tmp_path / "trial/controller.json").read_text()) == result


def test_checkpoint_rejects_incomplete_or_modified_evidence(tmp_path):
    write_json(tmp_path / "block.json", dict(passed=False))
    archive_checksums(tmp_path)
    with pytest.raises(ValueError, match="invalid"):
        validate_checkpoint(tmp_path, {}, 0, 0, [], {})
    (tmp_path / "block.json").write_text("changed")
    with pytest.raises(ValueError, match="inventory"):
        validate_checkpoint(tmp_path, {}, 0, 0, [], {})


def host_fixture():
    return dict(platform="Linux", python="fixture", packages={"numpy": "fixture"},
                cpu=dict(stdout=json.dumps(dict(lscpu=[dict(field=key + ":", data=value) for key, value in
                              (("Architecture", "x86_64"), ("CPU(s)", "4"), ("Model name", "Fixture CPU"), ("CPU(s) scaling MHz", "40%"))]))),
                java=dict(returncode=0, stdout="", stderr="fixture Java 21"), cpu_affinity=[0, 1, 2, 3],
                storage=dict(stdout=json.dumps(dict(filesystems=[dict(fstype="overlay", options="rw,relatime,upperdir=/session1,workdir=/scratch1")]))),
                memory_limit_bytes=2 * 1024 ** 3)


def test_host_class_ignores_volatile_clock_and_mount_paths_but_not_model_or_core_count():
    facts = host_fixture()
    expected = host_class_hash(facts)
    changed = json.loads(json.dumps(facts))
    cpu = json.loads(changed["cpu"]["stdout"])
    cpu["lscpu"][-1]["data"] = "90%"
    changed["cpu"]["stdout"] = json.dumps(cpu)
    changed["storage"]["stdout"] = changed["storage"]["stdout"].replace("session1", "session2").replace("scratch1", "scratch2")
    changed["cpu_affinity"] = [4, 5, 6, 7]
    assert host_class_hash(changed) == expected
    changed["cpu_affinity"] = [4, 5]
    assert host_class_hash(changed) != expected
