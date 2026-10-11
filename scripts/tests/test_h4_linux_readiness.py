import json

import pytest

from h4 import linux_readiness as readiness
from h4.memory_controller import delegated_parent
from h4.common import digest
from h4.prepare_pcam_manifest import canonical_hash


def storage_fixture(fstype="ext4", device_type="part"):
    mount = dict(returncode=0, stdout=json.dumps(dict(filesystems=[dict(
        source="/dev/vda1", fstype=fstype, options="rw,relatime", **{"maj:min": "252:1"})])))
    devices = dict(returncode=0, stdout=json.dumps(dict(blockdevices=[dict(
        name="vda", type="disk", children=[dict(name="vda1", type=device_type, **{"maj:min": "252:1"})])])))
    return mount, devices


def test_block_storage_preserves_guest_cache_limitation():
    result = readiness.block_storage(*storage_fixture())
    assert result["devices"][0]["name"] == "vda1"
    assert "hypervisor" in result["limitation"]


@pytest.mark.parametrize("fstype", ["tmpfs", "ramfs", "overlay", "nfs", "cifs"])
def test_unattributable_filesystem_is_rejected(fstype):
    with pytest.raises(ValueError, match="block-backed"):
        readiness.block_storage(*storage_fixture(fstype))


def test_unavailable_or_unsupported_device_is_rejected():
    mount, devices = storage_fixture()
    with pytest.raises(ValueError, match="unavailable"):
        readiness.block_storage(dict(returncode=1), devices)
    with pytest.raises(ValueError, match="attribution"):
        readiness.block_storage(*storage_fixture(device_type="loop"))
    mount["stdout"] = mount["stdout"].replace("rw,relatime", "ro,relatime")
    with pytest.raises(ValueError, match="read-only"):
        readiness.block_storage(mount, devices)


def test_delegated_ancestor_is_selected_without_writes(tmp_path):
    parent = tmp_path / "h4-service"
    leaf = parent / "launcher"
    leaf.mkdir(parents=True)
    (parent / "cgroup.procs").write_text("")
    (parent / "cgroup.subtree_control").write_text("memory io")
    assert delegated_parent(leaf, tmp_path, parent) == parent
    assert (parent / "cgroup.subtree_control").read_text() == "memory io"
    for invalid in (tmp_path, leaf):
        with pytest.raises(RuntimeError, match="ancestor"):
            delegated_parent(leaf, tmp_path, invalid)
    sibling = tmp_path / "unrelated"
    sibling.mkdir()
    with pytest.raises(RuntimeError, match="ancestor"):
        delegated_parent(leaf, tmp_path, sibling)
    (parent / "cgroup.procs").write_text("123")
    with pytest.raises(RuntimeError, match="contains processes"):
        delegated_parent(leaf, tmp_path, parent)
    (parent / "cgroup.procs").write_text("")
    (parent / "cgroup.subtree_control").write_text("io")
    with pytest.raises(RuntimeError, match="no delegated memory"):
        delegated_parent(leaf, tmp_path, parent)


def test_readiness_is_immutable_and_cannot_qualify_primary(tmp_path, monkeypatch):
    monkeypatch.setattr(readiness.platform, "system", lambda: "Linux")
    mount, devices = storage_fixture()
    monkeypatch.setattr(readiness, "command", lambda argv: mount if argv[0] == "findmnt" else devices)
    monkeypatch.setattr(readiness, "candidate_identity", lambda root: dict(patch_sha256="fixture"))
    monkeypatch.setattr(readiness, "delegation_identity", lambda: dict(parent="fixture"))
    monkeypatch.setattr(readiness.shutil, "disk_usage", lambda path: type("Usage", (), {"free": 64 * 1024 ** 3})())
    output = tmp_path / "report"
    result = readiness.run(output, tmp_path)
    assert result["readiness_passed"]
    assert not result["primary_h4_eligible"] and not result["out_of_core_verified"]
    assert not result["parent_modified"]
    assert json.loads((output / "readiness.json").read_text()) == result
    with pytest.raises(FileExistsError):
        readiness.run(output, tmp_path)


def test_missing_candidate_inventory_fails_closed(tmp_path):
    with pytest.raises(FileNotFoundError):
        readiness.candidate_identity(tmp_path)


def test_candidate_readiness_binds_inventory_and_compiled_build(tmp_path, monkeypatch):
    import paper_common
    patch = tmp_path / "scripts/h4/storage-revision/bounded-storage-v1.patch"
    patch.parent.mkdir(parents=True)
    patch.write_text("fixture patch")
    identity = dict(patch_sha256=digest(patch), baseline_transport_sha256="fixture baseline")
    patch.with_suffix(".json").write_text(json.dumps(identity))
    source = tmp_path / "modules/Example.java"
    source.parent.mkdir()
    source.write_text("fixture source")
    files = {source.relative_to(tmp_path).as_posix(): digest(source)}
    inventory = dict(schema="aether-h4-storage-candidate-source-v1", **identity,
                     files=files, sources_sha256=canonical_hash(files))
    (tmp_path / "h4-candidate-inventory.json").write_text(json.dumps(inventory))
    monkeypatch.setattr(paper_common, "ROOT", tmp_path)
    calls = []
    monkeypatch.setattr(paper_common, "java_classpath", lambda: calls.append("validated"))
    monkeypatch.setattr(paper_common, "java_build_sources", lambda root: files)
    assert readiness.candidate_identity(tmp_path)["java_build_sources"] == files
    assert calls == ["validated"]
    source.write_text("changed source")
    with pytest.raises(ValueError, match="source changed"):
        readiness.candidate_identity(tmp_path)
    assert calls == ["validated"]


def test_readiness_preserves_failures_without_launching_anything(tmp_path, monkeypatch):
    monkeypatch.setattr(readiness.platform, "system", lambda: "Linux")
    monkeypatch.setattr(readiness, "command", lambda argv: dict(returncode=1, stderr="unavailable"))
    def fail(*args):
        raise RuntimeError("not provisioned")
    monkeypatch.setattr(readiness, "candidate_identity", fail)
    monkeypatch.setattr(readiness, "delegation_identity", fail)
    monkeypatch.setattr(readiness.shutil, "disk_usage", lambda path: type("Usage", (), {"free": 1024})())
    result = readiness.run(tmp_path / "failure", tmp_path)
    assert not result["readiness_passed"] and not result["primary_h4_eligible"]
    assert len(result["errors"]) == 4
    assert "insufficient" in result["errors"][-1]
