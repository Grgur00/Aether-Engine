package io.aetherdb.training.cache.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("aether.BulkPopulation")
@Label("Aether Bulk Population")
@Category({"Aether", "Training Cache"})
@StackTrace(false)
public final class BulkPopulationEvent extends Event {
    public long artifactCount;
    public long payloadBytes;
    public long targetSstableBytes;
    public int sstableCount;
    public boolean success;
}
