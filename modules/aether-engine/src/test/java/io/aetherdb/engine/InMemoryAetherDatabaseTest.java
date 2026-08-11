package io.aetherdb.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.api.AetherCursor;
import io.aetherdb.api.AetherDatabase;
import io.aetherdb.api.Snapshot;
import io.aetherdb.api.WriteBatch;
import io.aetherdb.api.WriteOptions;
import io.aetherdb.api.WriteResult;
import io.aetherdb.api.exceptions.AetherClosedException;
import io.aetherdb.api.exceptions.SequenceExhaustedException;
import io.aetherdb.api.exceptions.SnapshotException;
import io.aetherdb.api.exceptions.SnapshotLimitExceededException;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class InMemoryAetherDatabaseTest {
    @Test
    void putGetAndOwnershipCopiesHandleEmptyData() {
        try (AetherDatabase database = Aether.openInMemory()) {
            byte[] key = {};
            byte[] value = {};
            database.put(key, value);
            assertThat(database.get(new byte[0]).value()).isEmpty();

            byte[] mutableKey = {1};
            byte[] mutableValue = {2};
            database.put(mutableKey, mutableValue);
            mutableKey[0] = 9;
            mutableValue[0] = 9;
            byte[] output = database.get(new byte[] {1}).value();
            output[0] = 8;
            assertThat(database.get(new byte[] {1}).value()).containsExactly(2);
        }
    }

    @Test
    void tombstonesRetainHistoryAndSnapshotsRemainStable() {
        InMemoryAetherDatabase database = new InMemoryAetherDatabase();
        database.put(bytes("k"), bytes("old"));
        try (Snapshot beforeDelete = database.newSnapshot()) {
            database.delete(bytes("k"));
            try (Snapshot afterDelete = database.newSnapshot()) {
                database.put(bytes("k"), bytes("new"));
                assertThat(database.get(bytes("k"), beforeDelete).value()).isEqualTo(bytes("old"));
                assertThat(database.get(bytes("k"), afterDelete).isFound()).isFalse();
                assertThat(database.get(bytes("k")).value()).isEqualTo(bytes("new"));
                assertThat(database.retainedVersionCount(bytes("k"))).isEqualTo(3);
            }
        }
        database.close();
    }

    @Test
    void scanIsHalfOpenOrderedCollapsedAndSnapshotAware() {
        try (AetherDatabase database = Aether.openInMemory()) {
            database.put(new byte[] {(byte) 0x80}, bytes("high"));
            database.put(new byte[] {0x7f}, bytes("low"));
            database.put(new byte[] {(byte) 0xff}, bytes("end"));
            try (Snapshot snapshot = database.newSnapshot()) {
                database.delete(new byte[] {(byte) 0x80});
                assertThat(rows(database.scan(new byte[] {0x7f}, new byte[] {(byte) 0xff})))
                        .containsExactly("7f=low");
                assertThat(
                                rows(
                                        database.scan(
                                                new byte[] {0x7f},
                                                new byte[] {(byte) 0xff},
                                                snapshot)))
                        .containsExactly("7f=low", "80=high");
            }
            assertThat(rows(database.scan(new byte[] {1}, new byte[] {1}))).isEmpty();
            assertThatThrownBy(() -> database.scan(new byte[] {2}, new byte[] {1}))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void batchUsesContiguousSequencesAndFreezesAfterCommit() {
        InMemoryAetherDatabase database = new InMemoryAetherDatabase();
        WriteBatch batch =
                new WriteBatch()
                        .put(bytes("a"), bytes("1"))
                        .delete(bytes("b"))
                        .put(bytes("a"), bytes("2"));
        database.write(batch);
        assertThat(database.lastVisibleSequence()).isEqualTo(3);
        assertThat(database.get(bytes("a")).value()).isEqualTo(bytes("2"));
        assertThat(database.get(bytes("b")).isFound()).isFalse();
        assertThat(batch.isClosed()).isTrue();
        assertThatThrownBy(() -> batch.put(bytes("x"), bytes("y")))
                .isInstanceOf(AetherClosedException.class);

        WriteBatch empty = new WriteBatch();
        database.write(empty);
        assertThat(database.lastVisibleSequence()).isEqualTo(3);
        database.close();
    }

    @Test
    void validatesSnapshotOwnershipAndAllHandleLifetimes() {
        AetherDatabase first = Aether.openInMemory();
        AetherDatabase second = Aether.openInMemory();
        Snapshot snapshot = first.newSnapshot();
        assertThatThrownBy(() -> second.get(bytes("k"), snapshot))
                .isInstanceOf(SnapshotException.class);
        snapshot.close();
        snapshot.close();
        assertThatThrownBy(() -> first.get(bytes("k"), snapshot))
                .isInstanceOf(SnapshotException.class);

        AetherCursor cursor = first.scan(new byte[0], new byte[] {(byte) 0xff});
        first.close();
        first.close();
        assertThat(first.isClosed()).isTrue();
        assertThatThrownBy(() -> first.get(bytes("k"))).isInstanceOf(AetherClosedException.class);
        assertThatThrownBy(cursor::next).isInstanceOf(AetherClosedException.class);
        second.close();
    }

    @Test
    void rejectsNullsAndSequenceOverflowBeforeMutation() {
        try (AetherDatabase database = Aether.openInMemory()) {
            assertThatThrownBy(() -> database.put(null, bytes("v")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> database.put(bytes("k"), null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> database.delete(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        InMemoryAetherDatabase database = new InMemoryAetherDatabase(Long.MAX_VALUE);
        assertThatThrownBy(() -> database.put(bytes("k"), bytes("v")))
                .isInstanceOf(SequenceExhaustedException.class);
        assertThat(database.get(bytes("k")).isFound()).isFalse();
        database.close();
    }

    @Test
    void snapshotIdsAreMonotonicAndActiveHandleLimitIsEnforced() {
        InMemoryAetherDatabase database = new InMemoryAetherDatabase(0, 2);
        Snapshot first = database.newSnapshot();
        Snapshot second = database.newSnapshot();
        assertThat(first.id()).isEqualTo(1);
        assertThat(second.id()).isEqualTo(2);
        assertThatThrownBy(database::newSnapshot)
                .isInstanceOf(SnapshotLimitExceededException.class);
        first.close();
        try (Snapshot third = database.newSnapshot()) {
            assertThat(third.id()).isEqualTo(3);
        }
        second.close();
        database.close();
    }

    @Test
    void boundedOneShotBatchReportsExactSizeStateAndSequenceResult() {
        try (AetherDatabase database = Aether.openInMemory()) {
            WriteBatch batch = new WriteBatch().put(bytes("a"), bytes("one")).delete(bytes("b"));
            assertThat(batch.operationCount()).isEqualTo(2);
            assertThat(batch.encodedSizeBytes()).isEqualTo(24 + 12 + 1 + 3 + 12 + 1);
            WriteResult result = database.write(batch, WriteOptions.defaults());
            assertThat(result.operationCount()).isEqualTo(2);
            assertThat(result.firstSequence()).isEqualTo(1);
            assertThat(result.lastSequence()).isEqualTo(2);
            assertThat(result.durabilityBarrierPerformed()).isFalse();
            assertThat(batch.state()).isEqualTo(WriteBatch.State.SUCCEEDED);
            assertThatThrownBy(() -> database.write(batch))
                    .isInstanceOf(AetherClosedException.class);

            WriteBatch oversizedKey = new WriteBatch();
            assertThatThrownBy(() -> oversizedKey.delete(new byte[WriteBatch.MAX_KEY_BYTES + 1]))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(oversizedKey.isEmpty()).isTrue();
        }
    }

    @Test
    void scanAllIncludesEntireUnsignedKeyspace() {
        try (AetherDatabase database = Aether.openInMemory()) {
            database.put(new byte[0], bytes("empty"));
            database.put(new byte[] {(byte) 0xff, (byte) 0xff}, bytes("high"));
            assertThat(rows(database.scanAll())).containsExactly("=empty", "ffff=high");
        }
    }

    private static List<String> rows(AetherCursor cursor) {
        try (cursor) {
            List<String> rows = new ArrayList<>();
            while (cursor.next()) {
                byte[] key = cursor.key();
                byte[] value = cursor.value();
                if (key.length > 0)
                    key[0] = key[0]; // Exercise a mutable copy without changing cursor state.
                rows.add(
                        java.util.HexFormat.of().formatHex(key)
                                + "="
                                + new String(value, java.nio.charset.StandardCharsets.UTF_8));
            }
            return rows;
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
