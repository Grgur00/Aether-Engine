package io.aetherdb.training.cache;

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Server-side protocol counters for proving batched cache operation usage. */
public final class TrainingCacheProtocolMetrics {
    private final LongAdder connections = new LongAdder();
    private final LongAdder requests = new LongAdder();
    private final LongAdder get = new LongAdder();
    private final LongAdder put = new LongAdder();
    private final LongAdder getMany = new LongAdder();
    private final LongAdder getManyRefs = new LongAdder();
    private final LongAdder putMany = new LongAdder();

    void connection() { connections.increment(); }
    void request(int operation) {
        requests.increment();
        switch (operation) {
            case 1 -> get.increment();
            case 2 -> put.increment();
            case 4 -> getManyRefs.increment();
            case 5 -> getMany.increment();
            case 6 -> putMany.increment();
            default -> { }
        }
    }

    public Map<String, Long> snapshot() {
        return Map.of("connections", connections.sum(), "requests", requests.sum(), "get", get.sum(),
                "put", put.sum(), "getMany", getMany.sum(), "getManyRefs", getManyRefs.sum(),
                "putMany", putMany.sum());
    }
}
