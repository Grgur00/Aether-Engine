class AetherCacheError(Exception):
    pass


class CacheMiss(AetherCacheError):
    pass


class CorruptCacheEntry(AetherCacheError):
    pass


class StaleSegmentReference(AetherCacheError):
    pass


class ProtocolError(AetherCacheError):
    pass


class ConnectionLost(AetherCacheError):
    pass


class CacheUnavailable(AetherCacheError):
    pass
