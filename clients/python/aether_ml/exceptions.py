class AetherMLError(Exception):
    """Base exception for Aether ML integration failures."""


class IdentityError(AetherMLError):
    pass


class TransformIdentityError(IdentityError):
    pass


class ArtifactDecodeError(AetherMLError):
    pass


class ArtifactSchemaMismatch(ArtifactDecodeError):
    pass


class CacheMissError(AetherMLError):
    pass


class CacheUnavailableError(AetherMLError):
    pass


class PublishError(CacheUnavailableError):
    pass


class WorkerLifecycleError(AetherMLError):
    pass


class ProtocolCompatibilityError(AetherMLError):
    pass
