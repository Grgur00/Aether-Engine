package io.aetherdb.codec.generated;

import io.aetherdb.api.typed.ValueCodec;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;

/** Immutable runtime lookup for compile-time generated record codecs. */
public final class GeneratedCodecs {
    private static final Registry REGISTRY = load();

    private GeneratedCodecs() {}

    public static <T> ValueCodec<T> forRecord(Class<T> recordType) {
        if (recordType == null) throw new IllegalArgumentException("recordType must not be null");
        ValueCodec<?> codec = REGISTRY.codecs().get(recordType);
        if (codec == null) {
            throw new IllegalArgumentException(
                    "CODEC_NOT_AVAILABLE for " + recordType.getName()
                            + "; annotate the record and enable aether-codec-processor");
        }
        @SuppressWarnings("unchecked")
        ValueCodec<T> typed = (ValueCodec<T>) codec;
        return typed;
    }

    /** Returns the authoritative generated descriptor bytes for a schema version, if installed. */
    public static Optional<byte[]> descriptor(UUID schemaId, int version) {
        byte[] descriptor = REGISTRY.descriptors().get(new SchemaVersion(schemaId, version));
        return descriptor == null ? Optional.empty() : Optional.of(Arrays.copyOf(descriptor, descriptor.length));
    }

    private static Registry load() {
        Map<Class<?>, ValueCodec<?>> codecs = new HashMap<>();
        Map<SchemaVersion, Class<?>> schemas = new HashMap<>();
        Map<SchemaVersion, byte[]> descriptors = new HashMap<>();
        for (GeneratedCodecProvider provider : ServiceLoader.load(GeneratedCodecProvider.class)) {
            byte[] descriptor = validateDescriptor(provider);
            ValueCodec<?> previous = codecs.putIfAbsent(provider.recordType(), provider.codec());
            if (previous != null
                    && (!previous.schemaId().equals(provider.codec().schemaId())
                            || previous.currentSchemaVersion()
                                    != provider.codec().currentSchemaVersion())) {
                throw new IllegalStateException(
                        "conflicting generated codecs for " + provider.recordType().getName());
            }
            SchemaVersion schemaVersion = new SchemaVersion(
                    provider.codec().schemaId(), provider.codec().currentSchemaVersion());
            Class<?> previousType = schemas.putIfAbsent(
                    schemaVersion,
                    provider.recordType());
            if (previousType != null && previousType != provider.recordType()) {
                throw new IllegalStateException(
                        "SCHEMA_DESCRIPTOR_CONFLICT between " + previousType.getName()
                                + " and " + provider.recordType().getName());
            }
            descriptors.putIfAbsent(schemaVersion, descriptor);
        }
        return new Registry(Map.copyOf(codecs), Map.copyOf(descriptors));
    }

    private static byte[] validateDescriptor(GeneratedCodecProvider provider) {
        ValueCodec<?> codec = provider.codec();
        String resource = "META-INF/aether/schemas/" + codec.schemaId() + "/"
                + codec.currentSchemaVersion() + ".aesch";
        try (var input = provider.recordType().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing generated schema descriptor: " + resource);
            }
            byte[] descriptor = input.readAllBytes();
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(descriptor);
            if (!Arrays.equals(actual, codec.fingerprint())) {
                throw new IllegalStateException(
                        "SCHEMA_FINGERPRINT_MISMATCH for " + provider.recordType().getName());
            }
            return descriptor;
        }
        catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException(
                    "cannot validate descriptor for " + provider.recordType().getName(), failure);
        }
    }

    private record Registry(
            Map<Class<?>, ValueCodec<?>> codecs,
            Map<SchemaVersion, byte[]> descriptors) {}

    private record SchemaVersion(UUID schemaId, int version) {}
}
