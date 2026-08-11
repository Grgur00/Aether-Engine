package io.aetherdb.codec.generated;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Deterministic, allocation-bounded payload codec used by generated container fields. */
public final class CanonicalContainerCodec {
    private CanonicalContainerCodec() {}

    /** Direct generated element codec; implementations must be deterministic and thread-safe. */
    public interface ElementCodec<T> {
        /** Encodes one non-null element. */
        byte[] encode(T value);

        /** Decodes one complete element payload. */
        T decode(byte[] encoded);
    }

    /** Encodes optional presence followed by a length-delimited value. */
    public static <T> byte[] optional(Optional<T> value, ElementCodec<T> codec, int maximumBytes) {
        if (value == null || codec == null) throw invalid("null optional or codec");
        if (value.isEmpty()) return new byte[] {0};
        byte[] item = requireItem(codec.encode(value.orElseThrow()));
        ByteArrayOutputStream output = new ByteArrayOutputStream(item.length + 6);
        output.write(1);
        writeLength(output, item.length);
        output.writeBytes(item);
        return bounded(output.toByteArray(), maximumBytes);
    }

    /** Decodes canonical optional presence. */
    public static <T> Optional<T> optional(
            byte[] encoded, ElementCodec<T> codec, int maximumBytes) {
        byte[] bytes = boundedCopy(encoded, maximumBytes);
        if (bytes.length == 0) throw invalid("invalid optional");
        if (bytes[0] == 0) {
            if (bytes.length != 1) throw invalid("optional trailing bytes");
            return Optional.empty();
        }
        if (bytes[0] != 1) throw invalid("invalid optional presence");
        Cursor cursor = new Cursor(bytes, 1);
        byte[] item = cursor.item();
        cursor.requireEnd();
        return Optional.of(requireDecoded(codec.decode(item)));
    }

    /** Encodes a list in logical iteration order. */
    public static <T> byte[] list(
            List<T> values, int maximumEntries, int maximumBytes, ElementCodec<T> codec) {
        if (values == null || codec == null) throw invalid("null list or codec");
        requireCount(values.size(), maximumEntries);
        ByteArrayOutputStream output = header(values.size());
        for (T value : values) writeItem(output, codec.encode(requireDecoded(value)));
        return bounded(output.toByteArray(), maximumBytes);
    }

    /** Decodes an immutable list after validating its count before allocation. */
    public static <T> List<T> list(
            byte[] encoded, int maximumEntries, int maximumBytes, ElementCodec<T> codec) {
        Cursor cursor = cursor(encoded, maximumBytes);
        int count = cursor.count(maximumEntries);
        List<T> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++)
            values.add(requireDecoded(codec.decode(cursor.item())));
        cursor.requireEnd();
        return List.copyOf(values);
    }

    /** Encodes a set in unsigned lexicographic order of canonical element payloads. */
    public static <T> byte[] set(
            Set<T> values, int maximumEntries, int maximumBytes, ElementCodec<T> codec) {
        if (values == null || codec == null) throw invalid("null set or codec");
        requireCount(values.size(), maximumEntries);
        List<byte[]> encoded =
                values.stream()
                        .map(value -> requireItem(codec.encode(requireDecoded(value))))
                        .sorted(Arrays::compareUnsigned)
                        .toList();
        rejectDuplicates(encoded, "CONTAINER_DUPLICATE_CANONICAL_KEY");
        ByteArrayOutputStream output = header(encoded.size());
        encoded.forEach(item -> writeItem(output, item));
        return bounded(output.toByteArray(), maximumBytes);
    }

    /** Decodes an immutable insertion-ordered set and requires canonical payload ordering. */
    public static <T> Set<T> set(
            byte[] encoded, int maximumEntries, int maximumBytes, ElementCodec<T> codec) {
        Cursor cursor = cursor(encoded, maximumBytes);
        int count = cursor.count(maximumEntries);
        LinkedHashSet<T> values = new LinkedHashSet<>(capacity(count));
        byte[] previous = null;
        for (int index = 0; index < count; index++) {
            byte[] item = cursor.item();
            requireIncreasing(previous, item);
            if (!values.add(requireDecoded(codec.decode(item))))
                throw invalid("CONTAINER_DUPLICATE_CANONICAL_KEY");
            previous = item;
        }
        cursor.requireEnd();
        return Collections.unmodifiableSet(values);
    }

    /** Encodes a map in unsigned lexicographic order of canonical key payloads. */
    public static <K, V> byte[] map(
            Map<K, V> values,
            int maximumEntries,
            int maximumBytes,
            ElementCodec<K> keyCodec,
            ElementCodec<V> valueCodec) {
        if (values == null || keyCodec == null || valueCodec == null)
            throw invalid("null map or codec");
        requireCount(values.size(), maximumEntries);
        List<EntryBytes> entries = new ArrayList<>(values.size());
        values.forEach(
                (key, value) ->
                        entries.add(
                                new EntryBytes(
                                        requireItem(keyCodec.encode(requireDecoded(key))),
                                        requireItem(valueCodec.encode(requireDecoded(value))))));
        entries.sort((left, right) -> Arrays.compareUnsigned(left.key, right.key));
        for (int index = 1; index < entries.size(); index++) {
            if (Arrays.equals(entries.get(index - 1).key, entries.get(index).key)) {
                throw invalid("CONTAINER_DUPLICATE_CANONICAL_KEY");
            }
        }
        ByteArrayOutputStream output = header(entries.size());
        entries.forEach(
                entry -> {
                    writeItem(output, entry.key);
                    writeItem(output, entry.value);
                });
        return bounded(output.toByteArray(), maximumBytes);
    }

    /** Decodes an immutable canonical-order map. */
    public static <K, V> Map<K, V> map(
            byte[] encoded,
            int maximumEntries,
            int maximumBytes,
            ElementCodec<K> keyCodec,
            ElementCodec<V> valueCodec) {
        Cursor cursor = cursor(encoded, maximumBytes);
        int count = cursor.count(maximumEntries);
        LinkedHashMap<K, V> values = new LinkedHashMap<>(capacity(count));
        byte[] previous = null;
        for (int index = 0; index < count; index++) {
            byte[] keyBytes = cursor.item();
            requireIncreasing(previous, keyBytes);
            K key = requireDecoded(keyCodec.decode(keyBytes));
            V value = requireDecoded(valueCodec.decode(cursor.item()));
            if (values.putIfAbsent(key, value) != null)
                throw invalid("CONTAINER_DUPLICATE_CANONICAL_KEY");
            previous = keyBytes;
        }
        cursor.requireEnd();
        return Collections.unmodifiableMap(values);
    }

    private static ByteArrayOutputStream header(int count) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CanonicalRecordWriter.writeUnsignedVarint(output, count);
        return output;
    }

    private static void writeItem(ByteArrayOutputStream output, byte[] item) {
        byte[] checked = requireItem(item);
        writeLength(output, checked.length);
        output.writeBytes(checked);
    }

    private static void writeLength(ByteArrayOutputStream output, int length) {
        CanonicalRecordWriter.writeUnsignedVarint(output, length);
    }

    private static byte[] requireItem(byte[] item) {
        if (item == null) throw invalid("null encoded container item");
        return item;
    }

    private static <T> T requireDecoded(T value) {
        if (value == null) throw invalid("null container item");
        return value;
    }

    private static void requireCount(int count, int maximum) {
        if (maximum < 0 || count < 0 || count > maximum) throw invalid("CONTAINER_COUNT_EXCEEDED");
    }

    private static byte[] bounded(byte[] bytes, int maximum) {
        if (maximum < 1 || bytes.length > maximum) throw invalid("FIELD_LENGTH_EXCEEDED");
        return bytes;
    }

    private static byte[] boundedCopy(byte[] bytes, int maximum) {
        if (bytes == null) throw invalid("null container payload");
        return Arrays.copyOf(bounded(bytes, maximum), bytes.length);
    }

    private static Cursor cursor(byte[] bytes, int maximum) {
        return new Cursor(boundedCopy(bytes, maximum), 0);
    }

    private static int capacity(int count) {
        return Math.max(1, (int) Math.ceil(count / 0.75d));
    }

    private static void rejectDuplicates(List<byte[]> values, String message) {
        for (int index = 1; index < values.size(); index++)
            if (Arrays.equals(values.get(index - 1), values.get(index))) throw invalid(message);
    }

    private static void requireIncreasing(byte[] previous, byte[] current) {
        if (previous != null && Arrays.compareUnsigned(previous, current) >= 0)
            throw invalid("CONTAINER_DUPLICATE_CANONICAL_KEY");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private record EntryBytes(byte[] key, byte[] value) {}

    private static final class Cursor {
        private final byte[] bytes;
        private int offset;

        private Cursor(byte[] bytes, int offset) {
            this.bytes = bytes;
            this.offset = offset;
        }

        private int count(int maximum) {
            long count = unsigned();
            if (count > Integer.MAX_VALUE) throw invalid("CONTAINER_COUNT_EXCEEDED");
            requireCount((int) count, maximum);
            return (int) count;
        }

        private byte[] item() {
            long length = unsigned();
            if (length > Integer.MAX_VALUE || offset > bytes.length - (int) length)
                throw invalid("truncated container item");
            byte[] item = Arrays.copyOfRange(bytes, offset, offset + (int) length);
            offset += (int) length;
            return item;
        }

        private long unsigned() {
            long value = 0;
            int shift = 0;
            while (offset < bytes.length && shift < 64) {
                int current = bytes[offset++] & 0xff;
                value |= (long) (current & 0x7f) << shift;
                if ((current & 0x80) == 0) {
                    if (shift > 0 && current == 0) throw invalid("non-canonical container varint");
                    return value;
                }
                shift += 7;
            }
            throw invalid("malformed container varint");
        }

        private void requireEnd() {
            if (offset != bytes.length) throw invalid("container trailing bytes");
        }
    }
}
