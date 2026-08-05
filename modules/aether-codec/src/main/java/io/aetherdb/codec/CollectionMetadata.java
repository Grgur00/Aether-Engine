package io.aetherdb.codec;

import io.aetherdb.api.typed.CollectionDefinition;
import io.aetherdb.api.typed.CollectionId;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Durable, bounded collection/schema metadata used by administration tools. */
public record CollectionMetadata(
        CollectionId id,
        String name,
        String keyCodecId,
        int keyEncodingVersion,
        byte[] keyFingerprint,
        UUID schemaId,
        int schemaVersion,
        byte[] schemaFingerprint,
        byte[] schemaDescriptor) {
    private static final byte[] KEY_PREFIX = "\0AETHER/COLLECTION/".getBytes(StandardCharsets.US_ASCII);
    private static final String MAGIC = "AETHER_COLLECTION_V1";
    private static final int MAXIMUM_BYTES = 1024 * 1024;

    public CollectionMetadata {
        if (id == null || name == null || name.isBlank() || keyCodecId == null
                || keyEncodingVersion < 1 || schemaId == null || schemaVersion < 1
                || keyFingerprint == null || keyFingerprint.length != 32
                || schemaFingerprint == null || schemaFingerprint.length != 32
                || schemaDescriptor == null || schemaDescriptor.length > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("invalid collection metadata");
        }
        keyFingerprint = Arrays.copyOf(keyFingerprint, keyFingerprint.length);
        schemaFingerprint = Arrays.copyOf(schemaFingerprint, schemaFingerprint.length);
        schemaDescriptor = Arrays.copyOf(schemaDescriptor, schemaDescriptor.length);
    }

    @Override public byte[] keyFingerprint() { return Arrays.copyOf(keyFingerprint, keyFingerprint.length); }
    @Override public byte[] schemaFingerprint() { return Arrays.copyOf(schemaFingerprint, schemaFingerprint.length); }
    @Override public byte[] schemaDescriptor() { return Arrays.copyOf(schemaDescriptor, schemaDescriptor.length); }

    public static CollectionMetadata from(
            CollectionDefinition<?, ?> definition, Optional<byte[]> descriptor) {
        return new CollectionMetadata(
                definition.id(), definition.name(), definition.keyCodec().codecId(),
                definition.keyCodec().encodingVersion(), definition.keyCodec().fingerprint(),
                definition.valueCodec().schemaId(), definition.valueCodec().currentSchemaVersion(),
                definition.valueCodec().fingerprint(), descriptor.orElseGet(() -> new byte[0]));
    }

    public byte[] key() {
        return ByteBuffer.allocate(KEY_PREFIX.length + 16).order(ByteOrder.BIG_ENDIAN)
                .put(KEY_PREFIX).putLong(id.value().getMostSignificantBits())
                .putLong(id.value().getLeastSignificantBits()).array();
    }

    public boolean compatibleWith(CollectionMetadata other) {
        return id.equals(other.id) && keyCodecId.equals(other.keyCodecId)
                && keyEncodingVersion == other.keyEncodingVersion
                && Arrays.equals(keyFingerprint, other.keyFingerprint)
                && schemaId.equals(other.schemaId) && schemaVersion == other.schemaVersion
                && Arrays.equals(schemaFingerprint, other.schemaFingerprint);
    }

    public byte[] encode() {
        Base64.Encoder base64 = Base64.getEncoder();
        String text = MAGIC + "\n"
                + "id=" + id.value() + "\n"
                + "name=" + base64.encodeToString(name.getBytes(StandardCharsets.UTF_8)) + "\n"
                + "keyCodec=" + base64.encodeToString(keyCodecId.getBytes(StandardCharsets.UTF_8)) + "\n"
                + "keyVersion=" + keyEncodingVersion + "\n"
                + "keyFingerprint=" + HexFormat.of().formatHex(keyFingerprint) + "\n"
                + "schemaId=" + schemaId + "\n"
                + "schemaVersion=" + schemaVersion + "\n"
                + "schemaFingerprint=" + HexFormat.of().formatHex(schemaFingerprint) + "\n"
                + "descriptor=" + base64.encodeToString(schemaDescriptor) + "\n";
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAXIMUM_BYTES) throw new IllegalArgumentException("collection metadata too large");
        return encoded;
    }

    public static Optional<CollectionMetadata> decode(byte[] key, byte[] value) {
        if (key == null || key.length != KEY_PREFIX.length + 16
                || !Arrays.equals(KEY_PREFIX, Arrays.copyOf(key, KEY_PREFIX.length))) {
            return Optional.empty();
        }
        if (value == null || value.length > MAXIMUM_BYTES) throw new IllegalArgumentException("invalid collection metadata");
        String[] lines = new String(value, StandardCharsets.UTF_8).split("\\n");
        if (lines.length != 10 || !lines[0].equals(MAGIC)) throw new IllegalArgumentException("invalid collection metadata");
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        for (int index = 1; index < lines.length; index++) {
            int separator = lines[index].indexOf('=');
            if (separator <= 0) throw new IllegalArgumentException("invalid collection metadata");
            fields.put(lines[index].substring(0, separator), lines[index].substring(separator + 1));
        }
        try {
            Base64.Decoder base64 = Base64.getDecoder();
            CollectionMetadata metadata = new CollectionMetadata(
                    new CollectionId(UUID.fromString(fields.get("id"))),
                    new String(base64.decode(fields.get("name")), StandardCharsets.UTF_8),
                    new String(base64.decode(fields.get("keyCodec")), StandardCharsets.UTF_8),
                    Integer.parseInt(fields.get("keyVersion")),
                    HexFormat.of().parseHex(fields.get("keyFingerprint")),
                    UUID.fromString(fields.get("schemaId")),
                    Integer.parseInt(fields.get("schemaVersion")),
                    HexFormat.of().parseHex(fields.get("schemaFingerprint")),
                    base64.decode(fields.get("descriptor")));
            if (!Arrays.equals(metadata.key(), key)) throw new IllegalArgumentException("metadata key mismatch");
            return Optional.of(metadata);
        }
        catch (RuntimeException failure) {
            throw new IllegalArgumentException("invalid collection metadata", failure);
        }
    }
}
