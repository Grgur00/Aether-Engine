package io.aetherdb.memtable.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.api.exceptions.SequenceExhaustedException;
import org.junit.jupiter.api.Test;

class SequenceSourceTest {
    @Test
    void startsAtOneAndReservesContiguousRanges() {
        SequenceSource source = new SequenceSource();
        assertThat(source.reserveOne()).isEqualTo(1);
        assertThat(source.reserve(3)).isEqualTo(new SequenceSource.SequenceRange(2, 4));
        assertThat(source.lastAssigned()).isEqualTo(4);
    }

    @Test
    void rejectsOverflowWithoutWrapping() {
        SequenceSource source = new SequenceSource(Long.MAX_VALUE - 1);
        assertThat(source.reserveOne()).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(source::reserveOne).isInstanceOf(SequenceExhaustedException.class);
        assertThat(source.lastAssigned()).isEqualTo(Long.MAX_VALUE);
    }
}
