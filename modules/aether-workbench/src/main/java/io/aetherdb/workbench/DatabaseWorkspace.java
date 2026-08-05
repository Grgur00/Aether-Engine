package io.aetherdb.workbench;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.aetherdb.api.AetherDatabase;
import io.aetherdb.api.AetherCursor;
import io.aetherdb.api.WriteBatch;
import io.aetherdb.codec.CollectionMetadata;
import io.aetherdb.codec.TypedKeyEnvelope;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Text-oriented editing session backed by an Aether database. */
public final class DatabaseWorkspace implements AutoCloseable {
    private final AetherDatabase database;
    private final boolean closeDatabase;
    private final Map<String, byte[]> knownKeys = new TreeMap<>();
    private final Map<UUID, CollectionMetadata> collections = new TreeMap<>();

    public DatabaseWorkspace(AetherDatabase database) { this(database, true); }
    public DatabaseWorkspace(AetherDatabase database, boolean closeDatabase) {
        this.database = Objects.requireNonNull(database, "database"); this.closeDatabase = closeDatabase;
    }

    public void put(String key, String value) {
        validate(key, "key"); validate(value, "value");
        database.put(key.getBytes(UTF_8), value.getBytes(UTF_8));
        knownKeys.put(key, key.getBytes(UTF_8));
    }

    public String addTypedEntry(String templateKey, String userKey, String value) {
        validate(templateKey, "templateKey"); validate(userKey, "userKey"); validate(value, "value");
        byte[] template = knownKeys.get(templateKey);
        if (template == null || !isTypedKey(template)) {
            throw new IllegalArgumentException("select an existing typed entry from the target collection");
        }
        byte[] templateValue = database.get(template).value();
        if (!UniversalTypedValue.isTyped(templateValue)) {
            throw new IllegalArgumentException("selected entry has no editable AETV value envelope");
        }
        byte[] encodedUserKey = encodeUserKeyLike(template, userKey);
        byte[] physicalKey = ByteBuffer.allocate(TypedKeyEnvelope.PREFIX_BYTES + encodedUserKey.length)
                .put(template, 0, TypedKeyEnvelope.PREFIX_BYTES)
                .put(encodedUserKey)
                .array();
        String displayedKey = displayKey(physicalKey);
        if (knownKeys.containsKey(displayedKey)) {
            throw new IllegalArgumentException("an entry with that key already exists");
        }
        database.put(physicalKey, UniversalTypedValue.reencode(templateValue, value));
        knownKeys.put(displayedKey, physicalKey);
        return displayedKey;
    }

    public void edit(String originalKey, String newKey, String value) {
        validate(originalKey, "originalKey"); validate(newKey, "newKey"); validate(value, "value");
        byte[] physicalKey = knownKeys.get(originalKey);
        if (physicalKey == null) throw new IllegalArgumentException("original key does not exist");
        if (isTypedKey(physicalKey)) {
            if (!originalKey.equals(newKey)) {
                throw new IllegalArgumentException("typed keys cannot be renamed in the Workbench");
            }
            byte[] current = database.get(physicalKey).value();
            if (!UniversalTypedValue.isTyped(current)) {
                throw new IllegalArgumentException("typed key has no editable AETV value envelope");
            }
            database.put(physicalKey, UniversalTypedValue.reencode(current, value));
            return;
        }
        if (!originalKey.equals(newKey) && knownKeys.containsKey(newKey)) {
            throw new IllegalArgumentException("new key already exists");
        }
        try (WriteBatch batch = new WriteBatch()) {
            if (!originalKey.equals(newKey)) batch.delete(originalKey.getBytes(UTF_8));
            batch.put(newKey.getBytes(UTF_8), value.getBytes(UTF_8));
            database.write(batch);
        }
        knownKeys.remove(originalKey); knownKeys.put(newKey, newKey.getBytes(UTF_8));
    }

    public boolean delete(String key) {
        validate(key, "key");
        byte[] physicalKey = knownKeys.remove(key);
        if (physicalKey == null) return false;
        database.delete(physicalKey);
        return true;
    }

    public List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        List<PhysicalRow> data = new ArrayList<>();
        knownKeys.clear();
        collections.clear();
        try (AetherCursor cursor = database.scanAll()) {
            while (cursor.next()) {
                byte[] physicalKey = cursor.key();
                byte[] valueBytes = cursor.value();
                Optional<CollectionMetadata> metadata = CollectionMetadata.decode(physicalKey, valueBytes);
                if (metadata.isPresent()) {
                    collections.put(metadata.orElseThrow().id().value(), metadata.orElseThrow());
                    continue;
                }
                data.add(new PhysicalRow(physicalKey, valueBytes));
            }
        }
        for (PhysicalRow physical : data) {
            String key = displayKey(physical.key());
            knownKeys.put(key, physical.key());
            CollectionMetadata metadata = metadataForKey(physical.key());
            byte[] descriptor = metadata == null ? new byte[0] : metadata.schemaDescriptor();
            rows.add(new Row(key, displayValue(physical.value(), descriptor), physical.value().length));
        }
        return List.copyOf(rows);
    }

    public boolean contains(String key) { return knownKeys.containsKey(key); }
    public int size() { return knownKeys.size(); }
    public boolean canEdit(String key) {
        byte[] physicalKey = knownKeys.get(key);
        if (physicalKey == null) return false;
        return !isTypedKey(physicalKey)
                || UniversalTypedValue.isTyped(database.get(physicalKey).value());
    }
    public boolean keyEditable(String key) {
        byte[] physicalKey = knownKeys.get(key);
        return physicalKey != null && !isTypedKey(physicalKey);
    }
    @Override public void close() { if (closeDatabase) database.close(); knownKeys.clear(); }

    private static void validate(String value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
    }

    private static String displayKey(byte[] key) {
        if (key.length >= 19 && key[0] == 0x40) {
            ByteBuffer buffer = ByteBuffer.wrap(key);
            buffer.get();
            String collection = new java.util.UUID(buffer.getLong(), buffer.getLong()).toString();
            buffer.getShort();
            byte[] userKey = Arrays.copyOfRange(key, 19, key.length);
            return "collection/" + collection + "/" + displayBytes(userKey);
        }
        return displayBytes(key);
    }

    private static boolean isTypedKey(byte[] key) {
        return key.length >= TypedKeyEnvelope.PREFIX_BYTES && key[0] == 0x40;
    }

    private static byte[] encodeUserKeyLike(byte[] template, String userKey) {
        int existingLength = template.length - TypedKeyEnvelope.PREFIX_BYTES;
        if (existingLength == 16) {
            UUID uuid;
            try { uuid = UUID.fromString(userKey); }
            catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("this collection requires UUID keys", failure);
            }
            return ByteBuffer.allocate(16)
                    .putLong(uuid.getMostSignificantBits())
                    .putLong(uuid.getLeastSignificantBits())
                    .array();
        }
        if (userKey.isEmpty()) throw new IllegalArgumentException("key must not be empty");
        return userKey.getBytes(UTF_8);
    }

    private CollectionMetadata metadataForKey(byte[] key) {
        if (!isTypedKey(key)) return null;
        ByteBuffer input = ByteBuffer.wrap(key).order(java.nio.ByteOrder.BIG_ENDIAN);
        input.get();
        return collections.get(new UUID(input.getLong(), input.getLong()));
    }

    private static String displayValue(byte[] value, byte[] descriptor) {
        if (UniversalTypedValue.isTyped(value)) {
            try {
                return UniversalTypedValue.display(value, descriptor);
            }
            catch (IllegalArgumentException ignored) {
                // Display malformed data generically rather than preventing database inspection.
            }
        }
        return displayBytes(value);
    }

    private record PhysicalRow(byte[] key, byte[] value) {}

    private static String displayBytes(byte[] bytes) {
        if (bytes.length == 16) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new java.util.UUID(buffer.getLong(), buffer.getLong()).toString();
        }
        try {
            String text = UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (text.codePoints().allMatch(codePoint -> !Character.isISOControl(codePoint))) {
                return text;
            }
        }
        catch (CharacterCodingException ignored) {
            // Fall through to a stable hexadecimal representation.
        }
        return java.util.HexFormat.of().formatHex(bytes);
    }

    public record Row(String key, String value, int valueBytes) {
        /** Parent path used by the workbench to group slash-delimited application keys. */
        public String group() {
            int separator = key.lastIndexOf('/');
            return separator < 0 ? "(root)" : key.substring(0, separator);
        }

        /** Final path component shown as the editable field name. */
        public String field() {
            int separator = key.lastIndexOf('/');
            return separator < 0 ? key : key.substring(separator + 1);
        }
    }
}
