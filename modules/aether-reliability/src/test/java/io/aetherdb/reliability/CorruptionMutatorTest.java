package io.aetherdb.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class CorruptionMutatorTest {
    @Test
    void flipBitMutatesOnlyTheSelectedBitInACopy() {
        byte[] original = new byte[] {0, 0b0000_0010, 3};

        byte[] corrupted = CorruptionMutator.apply(original, CorruptionPlan.flipBit(1, 2));

        assertThat(corrupted).containsExactly(0, 0b0000_0110, 3);
        assertThat(original).containsExactly(0, 0b0000_0010, 3);
    }

    @Test
    void truncateAndTornWritePrefixKeepExactPrefixes() {
        byte[] original = new byte[] {1, 2, 3, 4};

        assertThat(CorruptionMutator.apply(original, CorruptionPlan.truncate(2)))
                .containsExactly(1, 2);
        assertThat(CorruptionMutator.apply(original, CorruptionPlan.tornWritePrefix(3)))
                .containsExactly(1, 2, 3);
    }

    @Test
    void appendGarbageAndOverwriteRangeAreDeterministic() {
        byte[] original = new byte[] {1, 2, 3};

        assertThat(CorruptionMutator.apply(original, CorruptionPlan.appendGarbage(2, (byte) 9)))
                .containsExactly(1, 2, 3, 9, 9);
        assertThat(CorruptionMutator.apply(original, CorruptionPlan.overwriteRange(1, 2, (byte) 7)))
                .containsExactly(1, 7, 7);
    }

    @Test
    void invalidPlansFailBeforeReturningAmbiguousBytes() {
        byte[] original = new byte[] {1, 2, 3};

        assertThatThrownBy(() -> CorruptionPlan.flipBit(0, 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorruptionMutator.apply(original, CorruptionPlan.flipBit(3, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorruptionMutator.apply(original, CorruptionPlan.truncate(4)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                CorruptionMutator.apply(
                                        original, CorruptionPlan.appendGarbage(0, (byte) 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                CorruptionMutator.apply(
                                        original, CorruptionPlan.overwriteRange(2, 2, (byte) 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
