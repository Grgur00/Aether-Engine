package io.aetherdb.lsm.pressure;

import static org.junit.jupiter.api.Assertions.*;

import io.aetherdb.lsm.compaction.LevelCompactionConfig;

import org.junit.jupiter.api.Test;

final class WritePressureControllerTest {
    private final WritePressureController controller = new WritePressureController();

    @Test
    void normalStateHasNoDelay() {
        WritePressureSnapshot snapshot = controller.evaluate(input(0, true, 0, 0, 0, 100, 100));
        assertEquals(WritePressureState.NORMAL, snapshot.state());
        assertEquals(0, snapshot.delayMicros());
    }

    @Test
    void slowdownUsesBoundedQuadraticDelayAndAllReasons() {
        WritePressureInput input =
                input(
                        3,
                        true,
                        LevelCompactionConfig.GIB,
                        16,
                        5 * LevelCompactionConfig.GIB,
                        100,
                        100);
        WritePressureSnapshot snapshot = controller.evaluate(input);
        assertEquals(WritePressureState.SLOWDOWN, snapshot.state());
        assertEquals(2_575, snapshot.delayMicros());
        assertTrue(snapshot.reasons().contains(WritePressureReason.IMMUTABLE_MEMTABLES));
        assertTrue(snapshot.reasons().contains(WritePressureReason.LEVEL_ZERO_FILES));
        assertTrue(snapshot.reasons().contains(WritePressureReason.WAL_BYTES));
    }

    @Test
    void anyStopConditionStopsAdmission() {
        assertEquals(
                WritePressureState.STOPPED_RETRYABLE,
                controller.evaluate(input(4, true, 0, 0, 0, 100, 100)).state());
        assertEquals(
                WritePressureState.STOPPED_RETRYABLE,
                controller.evaluate(input(0, false, 0, 0, 0, 100, 100)).state());
        assertEquals(
                WritePressureState.STOPPED_RETRYABLE,
                controller.evaluate(input(0, true, 0, 20, 0, 100, 100)).state());
    }

    @Test
    void diskThresholdUsesAbsoluteOrPercentageMaximum() {
        long gib = LevelCompactionConfig.GIB;
        WritePressureSnapshot stopped =
                controller.evaluate(
                        new WritePressureInput(
                                0, true, 0, 0, 0, gib, 100 * gib, true, false, false));
        assertEquals(WritePressureState.STOPPED_RETRYABLE, stopped.state());
        assertTrue(stopped.reasons().contains(WritePressureReason.DISK_SPACE));
    }

    private static WritePressureInput input(
            int immutable,
            boolean nativeCapacity,
            long wal,
            int l0,
            long debt,
            long usable,
            long total) {
        return new WritePressureInput(
                immutable, nativeCapacity, wal, l0, debt, usable, total, false, false, false);
    }
}
