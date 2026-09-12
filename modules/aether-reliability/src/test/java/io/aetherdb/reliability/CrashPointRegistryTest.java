package io.aetherdb.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class CrashPointRegistryTest {
    @Test
    void disabledByDefault() {
        CrashPointRegistry.hit(CrashPointIds.WRITE_BEFORE_SEAL);
    }

    @Test
    void scopedTriggerThrowsOnlyOnTargetHit() {
        TriggeringCrashPoint crashPoint =
                new TriggeringCrashPoint(CrashPointIds.WAL_BEFORE_FRAGMENT_WRITE, 2);

        try (ScopedCrashPoint scope = CrashPointRegistry.install(crashPoint)) {
            assertThat(scope).isNotNull();
            CrashPointRegistry.hit(CrashPointIds.WRITE_BEFORE_SEAL);
            CrashPointRegistry.hit(CrashPointIds.WAL_BEFORE_FRAGMENT_WRITE);
            assertThat(crashPoint.hits()).isEqualTo(1);
            assertThatThrownBy(() -> CrashPointRegistry.hit(CrashPointIds.WAL_BEFORE_FRAGMENT_WRITE))
                    .isInstanceOf(CrashPointException.class)
                    .extracting("crashPointId")
                    .isEqualTo(CrashPointIds.WAL_BEFORE_FRAGMENT_WRITE);
        }

        CrashPointRegistry.hit(CrashPointIds.WAL_BEFORE_FRAGMENT_WRITE);
    }

    @Test
    void standardIdsAreValidAndComplete() {
        assertThat(CrashPointIds.all()).hasSize(19);
        CrashPointIds.all().forEach(CrashPointRegistry::validateId);
    }

    @Test
    void rejectsInvalidIds() {
        for (String id : new String[] {null, "", "wal", "Wal.write", "wal.", ".wal", "wal..write", "wal.write\n", "wal.wr-ite"}) {
            assertThatThrownBy(() -> CrashPointRegistry.hit(id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invalid");
        }
    }
}
