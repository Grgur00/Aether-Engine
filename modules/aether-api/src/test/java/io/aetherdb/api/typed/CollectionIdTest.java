package io.aetherdb.api.typed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class CollectionIdTest {
    @Test
    void nameDerivationIsFrozenAndDeterministic() {
        CollectionId first = CollectionId.fromName("todos");

        assertThat(first.value()).hasToString("91cda9c0-1951-3dba-ad40-240874cbffb8");
        assertThat(CollectionId.fromName("todos")).isEqualTo(first);
        assertThat(CollectionId.fromName("Todos")).isNotEqualTo(first);
    }

    @Test
    void nameDerivationUsesUtf8ByteBounds() {
        assertThatThrownBy(() -> CollectionId.fromName(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("name");
        assertThatThrownBy(() -> CollectionId.fromName("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid collection name");
        assertThatThrownBy(() -> CollectionId.fromName("é".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid collection name");
    }
}
