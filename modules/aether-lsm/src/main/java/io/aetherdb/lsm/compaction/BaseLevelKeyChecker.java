package io.aetherdb.lsm.compaction;

/** Conservatively proves that no level below an output level can contain a key. */
public final class BaseLevelKeyChecker {
    public boolean isBaseLevelForKey(byte[] userKey, int outputLevel, VersionInventory version) {
        if (userKey == null || outputLevel < 1 || outputLevel > 6) throw new IllegalArgumentException("invalid base-level query");
        for (int level = outputLevel + 1; level <= 6; level++)
            for (CompactionFile file : version.level(level)) if (file.contains(userKey)) return false;
        return true;
    }
}
