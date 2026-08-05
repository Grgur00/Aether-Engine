package io.aetherdb.api.typed;
import java.util.Comparator;
public interface OrderedKeyCodec<K> extends KeyCodec<K>{Comparator<K> comparator();}
