package io.aetherdb.api.typed;

import java.util.Comparator;

/** A key codec whose byte ordering is compatible with a declared logical ordering.
 * @param <K> logical key type */
public interface OrderedKeyCodec<K> extends KeyCodec<K> {
    /** Returns the key ordering.
     * @return comparator defining the logical order of decoded keys */
    Comparator<K> comparator();
}
