"""Bind logical frozen input paths to a read-only mount, without rewriting CSVs."""
import contextlib
from pathlib import Path

LOGICAL_ROOT = "/kaggle/input/aether-oct5k-pilot"
POLICY = dict(schema="aether-h2-input-binding-v1", logicalRoot=LOGICAL_ROOT,
              manifestBytes="unchanged; original SHA256 checks required",
              sourceBytes="unchanged; full source hash preflight required",
              scope="resolve input metadata before measured phases; no filesystem aliases or data copies")


def binding(physical_root, logical_root=LOGICAL_ROOT):
    physical = Path(physical_root).resolve()
    if not physical.is_dir():
        raise ValueError("physical input root must be an existing read-only dataset directory")
    logical = Path(logical_root).absolute()
    return dict(schema=POLICY["schema"], logicalRoot=str(logical), physicalRoot=str(physical))


def resolve_path(value, mapping, manifest_root=None):
    if set(mapping) != {"schema", "logicalRoot", "physicalRoot"} or mapping["schema"] != POLICY["schema"]:
        raise ValueError("invalid input binding")
    logical = Path(mapping["logicalRoot"])
    physical = Path(mapping["physicalRoot"]).resolve()
    path = Path(value)
    if ".." in path.parts:
        raise ValueError("input path traversal forbidden")
    if not path.is_absolute():
        if manifest_root is None:
            raise ValueError("relative input path requires its manifest directory")
        path = Path(manifest_root) / path
    if path.is_relative_to(logical):
        path = physical / path.relative_to(logical)
    elif not path.is_relative_to(physical):
        raise ValueError("input path outside the declared dataset")
    resolved = path.resolve()
    if not resolved.is_relative_to(physical):
        raise ValueError("input symlink escapes the declared dataset")
    return resolved


@contextlib.contextmanager
def input_paths(workload, mapping, *, comparison=None):
    """Adapt the existing metadata resolver; image reads use ordinary Paths."""
    original_resolver = workload.resolve_manifest_path
    original_rows = comparison.read_rows if comparison is not None else None
    def resolver(value, manifest_root):
        return resolve_path(value, mapping, manifest_root)
    def rows(path):
        fields, values = original_rows(path)
        return fields, [{**row, **{key: str(resolve_path(row[key], mapping, Path(path).parent))
                                  for key in ("image_path", "mask_path")}} for row in values]
    workload.resolve_manifest_path = resolver
    if comparison is not None:
        comparison.read_rows = rows
    try:
        yield
    finally:
        workload.resolve_manifest_path = original_resolver
        if comparison is not None:
            comparison.read_rows = original_rows
