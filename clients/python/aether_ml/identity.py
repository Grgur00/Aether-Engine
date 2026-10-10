import hashlib
import json
from typing import Any

from aether_training_cache import CacheKey, TransformationFingerprint

from .exceptions import IdentityError, TransformIdentityError


def canonical_identity(value: str | bytes, *, field: str) -> str:
    if isinstance(value, bytes):
        return value.hex()
    if isinstance(value, str) and value:
        return value
    raise IdentityError(f"{field} must be a non-empty str or bytes")


def canonical_json(value: Any) -> str:
    try:
        return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True, allow_nan=False)
    except (TypeError, ValueError) as error:
        raise TransformIdentityError("transform configuration must be canonical JSON data") from error


def transform_fingerprint(identity: str | bytes | dict[str, Any]) -> TransformationFingerprint:
    if isinstance(identity, dict):
        identity = canonical_json(identity)
    try:
        descriptor = canonical_identity(identity, field="transform_identity")
    except IdentityError as error:
        raise TransformIdentityError(str(error)) from error
    return TransformationFingerprint.from_descriptor(descriptor)


def fingerprint_transform(transform: Any) -> str:
    """Canonicalize supported framework transforms without trusting arbitrary code."""
    module = type(transform).__module__
    if not (module.startswith("torchvision.transforms") or module.startswith("monai.transforms")):
        raise TransformIdentityError("automatic fingerprint unavailable; provide transform_identity explicitly")

    def describe(value: Any) -> Any:
        if value is None or isinstance(value, (str, int, float, bool)):
            return value
        if isinstance(value, (list, tuple)):
            return [describe(item) for item in value]
        if isinstance(value, dict):
            return {str(key): describe(item) for key, item in value.items()}
        value_module = type(value).__module__
        if value_module.startswith("torchvision.transforms") or value_module.startswith("monai.transforms"):
            state = getattr(value, "__dict__", {})
            fields = {key: describe(item) for key, item in state.items()
                      if not key.startswith("_") and not callable(item)}
            return {"type": f"{value_module}.{type(value).__qualname__}", "parameters": fields}
        raise TransformIdentityError(f"unsupported transform parameter type: {type(value).__name__}")

    return canonical_json(describe(transform))


def artifact_key(namespace: str, source_identity: str | bytes, transform_identity: str | bytes | dict[str, Any],
                 artifact_schema_version: str = "1") -> CacheKey:
    if not isinstance(namespace, str) or not namespace:
        raise IdentityError("namespace must be a non-empty string")
    source = canonical_identity(source_identity, field="source_identity")
    schema = canonical_identity(artifact_schema_version, field="artifact_schema_version")
    transform = transform_fingerprint({"transform": transform_identity, "artifactSchemaVersion": schema})
    return CacheKey(namespace, source, transform)


def hashed_identity(value: str | bytes) -> str:
    return hashlib.sha256(canonical_identity(value, field="identity").encode("utf-8")).hexdigest()