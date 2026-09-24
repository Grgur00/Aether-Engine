from .config import AetherConfig
from .exceptions import ProtocolCompatibilityError


REQUIRED_FEATURES = frozenset({"get-many", "put-many", "contains-many"})


def validate_capabilities(client, *, required_features=REQUIRED_FEATURES) -> dict:
    """Reject daemons that cannot preserve Aether ML batch semantics."""
    try:
        info = client.engine_info()
    except Exception as error:
        raise ProtocolCompatibilityError("unable to read Aether daemon capabilities") from error
    if info.get("protocol") != 1:
        raise ProtocolCompatibilityError(f"Aether ML requires protocol 1; daemon reported {info.get('protocol')!r}")
    missing = set(required_features) - set(info.get("features", ()))
    if missing:
        raise ProtocolCompatibilityError("Aether daemon lacks required features: " + ", ".join(sorted(missing)))
    return info


def create_client(config: AetherConfig | None = None):
    """Create one daemon client; pass this factory to obtain worker-local connections."""
    from aether_training_cache import AetherTrainingCache
    config = config or AetherConfig.from_environment()
    client = AetherTrainingCache(host=config.host, port=config.port, timeout=config.timeout)
    try:
        validate_capabilities(client)
    except Exception:
        client.close()
        raise
    return client