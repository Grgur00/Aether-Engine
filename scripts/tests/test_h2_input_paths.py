import ast
import csv
import hashlib
from pathlib import Path
from types import SimpleNamespace

import pytest

from h2_input_paths import binding, input_paths, resolve_path
from longitudinal_manifests import read_rows


def pilot_parser():
    candidate = Path(__file__).resolve().parents[2] / "build/h2-streaming/source/clients/python/benchmark_gpu_segmentation.py"
    tree = ast.parse(candidate.read_text(encoding="utf-8"))
    names = {"load_oct5k_sources", "resolve_manifest_path", "file_sha256"}
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in names]
    assert {node.name for node in functions} == names
    namespace = dict(csv=csv, hashlib=hashlib, Path=Path)
    exec(compile(ast.Module(body=functions, type_ignores=[]), str(candidate), "exec"), namespace)
    class Workload:
        @property
        def resolve_manifest_path(self):
            return namespace["resolve_manifest_path"]
        @resolve_manifest_path.setter
        def resolve_manifest_path(self, value):
            namespace["resolve_manifest_path"] = value
        def load_sources(self, args):
            return namespace["load_oct5k_sources"](args)
    return Workload()


def test_original_pilot_parser_reads_nested_mount_with_unchanged_csv_and_hash_checks(tmp_path):
    logical, physical = tmp_path / "logical-oct5k", tmp_path / "datasets/owner/oct5k"
    physical.mkdir(parents=True)
    rows = []
    for i in range(2):
        image, mask = f"image-{i}.png", f"mask-{i}.png"
        (physical / image).write_bytes(image.encode())
        (physical / mask).write_bytes(mask.encode())
        image_sha = hashlib.sha256(image.encode()).hexdigest()
        mask_sha = hashlib.sha256(mask.encode()).hexdigest()
        rows.append(dict(sample_id=str(i), image_path=str(logical / image), mask_path=str(logical / mask),
            disease="fixture", image_sha256=image_sha, mask_sha256=mask_sha,
            source_identity=hashlib.sha256(f"{image_sha}:{mask_sha}".encode()).hexdigest()))
    manifest = physical / "manifest.csv"
    with manifest.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    original_bytes = manifest.read_bytes()
    args = SimpleNamespace(dataset_manifest=str(manifest), samples=2, dataset_split="train", trust_manifest_hashes=False)
    workload, comparison = pilot_parser(), SimpleNamespace(read_rows=read_rows)
    resolver, reader = workload.resolve_manifest_path, comparison.read_rows
    mapping = binding(physical, logical)
    with input_paths(workload, mapping, comparison=comparison):
        sources = workload.load_sources(args)
        mapped_rows = comparison.read_rows(manifest)[1]
        assert [source["source_identity"] for source in sources] == [row["source_identity"] for row in rows]
        for row, source in zip(mapped_rows, sources):
            assert Path(row["image_path"]).resolve() == source["image_path"]
            assert Path(row["mask_path"]).resolve() == source["mask_path"]
        (physical / "image-0.png").write_bytes(b"corrupt")
        with pytest.raises(ValueError, match="image_sha256 mismatch"):
            workload.load_sources(args)
    assert workload.resolve_manifest_path is resolver and comparison.read_rows is reader
    assert manifest.read_bytes() == original_bytes
    assert not logical.exists()


@pytest.mark.parametrize("kind", ["outside", "traversal", "relative", "schema"])
def test_binding_rejects_unsafe_or_undeclared_paths(tmp_path, kind):
    physical = tmp_path / "physical"
    physical.mkdir()
    mapping = binding(physical, tmp_path / "logical")
    path = tmp_path / "logical/file.png"
    if kind == "outside":
        path = tmp_path / "other/file.png"
    elif kind == "traversal":
        path = tmp_path / "logical/../outside.png"
    elif kind == "relative":
        path = "file.png"
    else:
        mapping["schema"] = "different"
    with pytest.raises(ValueError):
        resolve_path(path, mapping)


def test_resolver_is_restored_when_preflight_raises(tmp_path):
    original = lambda value, root: Path(root) / value
    workload = SimpleNamespace(resolve_manifest_path=original)
    comparison = SimpleNamespace(read_rows=read_rows)
    with pytest.raises(RuntimeError, match="failed preflight"):
        with input_paths(workload, binding(tmp_path, tmp_path / "logical"), comparison=comparison):
            raise RuntimeError("failed preflight")
    assert workload.resolve_manifest_path is original and comparison.read_rows is read_rows
