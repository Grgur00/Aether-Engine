import os
from dataclasses import dataclass


@dataclass(frozen=True)
class AetherConfig:
    host: str = "127.0.0.1"
    port: int = 9484
    timeout: float = 30.0
    namespace: str | None = None

    @classmethod
    def from_environment(cls) -> "AetherConfig":
        return cls(host=os.environ.get("AETHER_HOST", "127.0.0.1"),
                   port=int(os.environ.get("AETHER_PORT", "9484")),
                   timeout=float(os.environ.get("AETHER_TIMEOUT", "30")),
                   namespace=os.environ.get("AETHER_NAMESPACE"))