package io.aetherdb.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherdb.api.AetherCursor;
import io.aetherdb.api.AetherDatabase;
import io.aetherdb.api.Snapshot;
import io.aetherdb.api.WriteBatch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

class RandomizedSemanticModelTest {
    private static final int SEEDS = 1_000;
    private static final int OPERATIONS = 100;
    private static final byte[] SCAN_END = {(byte) 0xff, (byte) 0xff, (byte) 0xff};

    @Test
    void oneThousandReproducibleHistoriesMatchReplayModel() {
        for (long seed = 0; seed < SEEDS; seed++) {
            runHistory(seed);
        }
    }

    private static void runHistory(long seed) {
        Random random = new Random(seed);
        ReplayModel model = new ReplayModel();
        try (AetherDatabase database = Aether.openInMemory()) {
            Map<Snapshot, Long> snapshots = new LinkedHashMap<>();
            for (int operation = 0; operation < OPERATIONS; operation++) {
                byte[] key = randomKey(random);
                switch (random.nextInt(7)) {
                    case 0 -> {
                        byte[] value = randomValue(random);
                        database.put(key, value);
                        model.put(key, value);
                    }
                    case 1 -> {
                        database.delete(key);
                        model.delete(key);
                    }
                    case 2 ->
                            assertLookup(
                                    seed,
                                    operation,
                                    database.get(key),
                                    model.get(key, model.sequence));
                    case 3 -> {
                        Snapshot snapshot = database.newSnapshot();
                        snapshots.put(snapshot, model.sequence);
                    }
                    case 4 -> {
                        if (!snapshots.isEmpty()) {
                            int selected = random.nextInt(snapshots.size());
                            Map.Entry<Snapshot, Long> snapshot =
                                    snapshots.entrySet().stream()
                                            .skip(selected)
                                            .findFirst()
                                            .orElseThrow();
                            assertLookup(
                                    seed,
                                    operation,
                                    database.get(key, snapshot.getKey()),
                                    model.get(key, snapshot.getValue()));
                        }
                    }
                    case 5 -> {
                        try (WriteBatch batch = new WriteBatch()) {
                            int count = random.nextInt(5);
                            List<ModelMutation> mutations = new ArrayList<>();
                            for (int index = 0; index < count; index++) {
                                byte[] batchKey = randomKey(random);
                                if (random.nextBoolean()) {
                                    byte[] value = randomValue(random);
                                    batch.put(batchKey, value);
                                    mutations.add(new ModelMutation(batchKey, value));
                                } else {
                                    batch.delete(batchKey);
                                    mutations.add(new ModelMutation(batchKey, null));
                                }
                            }
                            database.write(batch);
                            model.apply(mutations);
                        }
                    }
                    case 6 ->
                            assertThat(scan(database.scan(new byte[0], SCAN_END)))
                                    .as("seed %s operation %s", seed, operation)
                                    .containsExactlyEntriesOf(model.scan(model.sequence));
                    default -> throw new AssertionError("unreachable operation");
                }
            }
            for (Snapshot snapshot : snapshots.keySet()) {
                snapshot.close();
            }
        }
    }

    private static void assertLookup(
            long seed, int operation, io.aetherdb.api.result.LookupResult actual, byte[] expected) {
        assertThat(actual.isFound())
                .as("seed %s operation %s", seed, operation)
                .isEqualTo(expected != null);
        if (expected != null) {
            assertThat(actual.value())
                    .as("seed %s operation %s", seed, operation)
                    .isEqualTo(expected);
        }
    }

    private static Map<Key, byte[]> scan(AetherCursor cursor) {
        Map<Key, byte[]> result = new TreeMap<>();
        try (cursor) {
            while (cursor.next()) {
                result.put(new Key(cursor.key()), cursor.value());
            }
        }
        return result;
    }

    private static byte[] randomKey(Random random) {
        byte[] key = new byte[random.nextInt(3)];
        random.nextBytes(key);
        return key;
    }

    private static byte[] randomValue(Random random) {
        byte[] value = new byte[random.nextInt(6)];
        random.nextBytes(value);
        return value;
    }

    private static final class ReplayModel {
        private final List<ModelMutation> history = new ArrayList<>();
        private long sequence;

        void put(byte[] key, byte[] value) {
            apply(List.of(new ModelMutation(key, value)));
        }

        void delete(byte[] key) {
            apply(List.of(new ModelMutation(key, null)));
        }

        void apply(List<ModelMutation> mutations) {
            for (ModelMutation mutation : mutations) {
                sequence++;
                history.add(new ModelMutation(mutation.key, mutation.value));
            }
        }

        byte[] get(byte[] key, long visibleSequence) {
            long inspected = 0;
            byte[] result = null;
            for (ModelMutation mutation : history) {
                inspected++;
                if (inspected > visibleSequence) {
                    break;
                }
                if (Arrays.equals(key, mutation.key)) {
                    result = mutation.value == null ? null : mutation.value.clone();
                }
            }
            return result;
        }

        Map<Key, byte[]> scan(long visibleSequence) {
            Map<Key, byte[]> state = new TreeMap<>();
            long inspected = 0;
            for (ModelMutation mutation : history) {
                inspected++;
                if (inspected > visibleSequence) {
                    break;
                }
                Key key = new Key(mutation.key);
                if (mutation.value == null) {
                    state.remove(key);
                } else {
                    state.put(key, mutation.value.clone());
                }
            }
            return state;
        }
    }

    private record ModelMutation(byte[] key, byte[] value) {
        ModelMutation {
            key = key.clone();
            value = value == null ? null : value.clone();
        }
    }

    private record Key(byte[] bytes) implements Comparable<Key> {
        Key {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public int compareTo(Key other) {
            return Arrays.compareUnsigned(bytes, other.bytes);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(bytes, key.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }
}
