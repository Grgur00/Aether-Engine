"""Local evidence for implementation boundaries described in contributor guides."""
import importlib
import json
from pathlib import Path
from types import SimpleNamespace

import pytest


ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture
def ml(monkeypatch):
    monkeypatch.syspath_prepend(str(ROOT / "clients/python"))
    return importlib.import_module("aether_ml")


def test_environment_configuration_retains_unvalidated_namespace_and_ranges(ml, monkeypatch):
    for name in ("AETHER_HOST", "AETHER_PORT", "AETHER_TIMEOUT", "AETHER_NAMESPACE"):
        monkeypatch.delenv(name, raising=False)
    assert ml.AetherConfig.from_environment() == ml.AetherConfig()
    monkeypatch.setenv("AETHER_PORT", "-1")
    monkeypatch.setenv("AETHER_TIMEOUT", "-2")
    monkeypatch.setenv("AETHER_NAMESPACE", "")
    config = ml.AetherConfig.from_environment()
    assert (config.port, config.timeout, config.namespace) == (-1, -2.0, "")
    monkeypatch.setenv("AETHER_PORT", "not-a-port")
    with pytest.raises(ValueError):
        ml.AetherConfig.from_environment()


def test_namespace_normalization_is_opt_in_and_not_a_path_guard(ml):
    assert ml.normalize_namespace("  team//images/  ") == "team/images"
    assert ml.normalize_namespace("../images") == "../images"
    with pytest.raises(ml.IdentityError):
        ml.normalize_namespace("team/my images")
    assert ml.artifact_key("team//images", "sample", "v1") != ml.artifact_key("team/images", "sample", "v1")


def test_capabilities_return_original_info_and_create_client_cleans_failure(ml, monkeypatch):
    low = importlib.import_module("aether_training_cache")
    info = {"protocol": 1, "features": ["get-many", "put-many", "contains-many"]}
    assert ml.validate_capabilities(SimpleNamespace(engine_info=lambda: info)) is info
    instances = []

    class Client:
        def __init__(self, **kwargs):
            self.arguments = kwargs
            self.closed = False
            instances.append(self)

        def engine_info(self):
            return {"protocol": 1, "features": ["get-many"]}

        def close(self):
            self.closed = True

    monkeypatch.setattr(low, "AetherTrainingCache", Client)
    with pytest.raises(ml.ProtocolCompatibilityError):
        ml.create_client(ml.AetherConfig(namespace="not-forwarded"))
    assert instances[0].closed
    assert instances[0].arguments == {"host": "127.0.0.1", "port": 9484, "timeout": 30.0}


def test_metric_export_omits_latency_and_missing_counters(ml):
    emitted = []
    ml.export_metrics({"hits": 4, "bytesPublished": 32, "lookupNs": 17,
                       "latency": {"lookup": {"count": 1}}},
                      lambda name, value: emitted.append((name, value)), prefix="local")
    assert emitted == [("local/cache_hits", 4.0), ("local/bytes_written", 32.0)]


def test_cli_runs_trusted_python_configuration_and_closes_loaded_dataset(ml, tmp_path, capsys):
    cli = importlib.import_module("aether_ml.cli")
    marker = tmp_path / "closed.txt"
    config = tmp_path / "dataset_config.py"
    config.write_text(
        "from pathlib import Path\n"
        "class Dataset:\n"
        "    def plan(self): return {'total': 3}\n"
        "    def populate(self, workers): return {'workers': workers}\n"
        "    def stats(self): return {}\n"
        f"    def close(self): Path({str(marker)!r}).write_text('closed')\n"
        "dataset = Dataset()\n", encoding="utf-8")
    cli.main(["populate", str(config), "--workers", "2"])
    assert json.loads(capsys.readouterr().out) == {"workers": 2}
    assert marker.read_text() == "closed"


def test_cli_cleanup_covers_operation_failure(ml, monkeypatch):
    cli = importlib.import_module("aether_ml.cli")
    closed = []

    def fail():
        raise ValueError("operation failed")

    dataset = SimpleNamespace(plan=fail, close=lambda: closed.append(True))
    monkeypatch.setattr(cli, "load_dataset", lambda path: dataset)
    with pytest.raises(ValueError, match="operation failed"):
        cli.main(["plan", "ignored.py"])
    assert closed == [True]


def test_cli_loader_does_not_require_close_or_callable_operations(ml, tmp_path):
    cli = importlib.import_module("aether_ml.cli")
    config = tmp_path / "unchecked.py"
    config.write_text("from types import SimpleNamespace\ndataset = SimpleNamespace(plan=1, populate=2, stats=3)\n",
                      encoding="utf-8")
    dataset = cli.load_dataset(str(config))
    assert dataset.plan == 1
    assert not hasattr(dataset, "close")


def test_monai_identities_are_descriptors_not_content_checks(ml, tmp_path, monkeypatch):
    identity = importlib.import_module("aether_ml.monai.identity")
    assert identity.monai_dict_identity({"id": 7, "image": b"old"}) == identity.monai_dict_identity({"id": "7", "image": b"new"})
    assert identity.dicom_study_identity({"StudyInstanceUID": "same"}) == identity.dicom_study_identity({"SOPInstanceUID": "same"})
    monkeypatch.setattr(identity, "Path", lambda path: SimpleNamespace(
        resolve=lambda: "/fixture/image.nii",
        stat=lambda: SimpleNamespace(st_size=8, st_mtime_ns=123)))
    assert identity.nifti_file_identity(tmp_path / "ignored.nii") == ml.hashed_identity("/fixture/image.nii:8:123")


def test_monai_wrapper_inherits_explicit_transform_identity_requirement(ml):
    monai = importlib.import_module("aether_ml.monai")
    with pytest.raises(ml.TransformIdentityError):
        monai.AetherPersistentDataset([], lambda value: value, namespace="test",
                                     identity_fn=lambda value, index: str(index))


def test_optional_torch_worker_init_only_opens_exposed_worker_cache(ml, monkeypatch):
    torch_data = pytest.importorskip("torch.utils.data")
    worker = importlib.import_module("aether_ml.torch.worker")
    opened = []
    cache = SimpleNamespace(_client_for_process=lambda: opened.append(True))
    monkeypatch.setattr(torch_data, "get_worker_info", lambda: None)
    worker.aether_worker_init(100)
    assert opened == []
    monkeypatch.setattr(torch_data, "get_worker_info", lambda: SimpleNamespace(dataset=SimpleNamespace(cache=cache)))
    worker.aether_worker_init(100)
    assert opened == [True]
