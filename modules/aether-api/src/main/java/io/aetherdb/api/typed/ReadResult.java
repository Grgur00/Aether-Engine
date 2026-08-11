package io.aetherdb.api.typed;

import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Result of a typed point read, distinguishing absence without using {@code null}.
 *
 * @param <V> logical value type
 */
public sealed interface ReadResult<V> permits ReadResult.Found, ReadResult.NotFound {
    /**
     * Returns the optional value.
     *
     * @return the stored value, or an empty optional when the key is absent
     */
    Optional<V> value();

    /**
     * Returns the stored value or fails when the key is absent.
     *
     * @return stored value
     * @throws NoSuchElementException when no value was found
     */
    default V requireValue() {
        return value().orElseThrow(() -> new NoSuchElementException("value not found"));
    }

    /**
     * Successful lookup containing a non-null value.
     *
     * @param found stored value
     * @param <V> logical value type
     */
    record Found<V>(V found) implements ReadResult<V> {
        /** Validates that the successful result contains a value. */
        public Found {
            if (found == null) throw new IllegalArgumentException("value is null");
        }

        /** {@inheritDoc} */
        @Override
        public Optional<V> value() {
            return Optional.of(found);
        }
    }

    /**
     * Lookup result indicating that no visible value exists.
     *
     * @param <V> logical value type
     */
    record NotFound<V>() implements ReadResult<V> {
        /** {@inheritDoc} */
        @Override
        public Optional<V> value() {
            return Optional.empty();
        }
    }
}
