package io.aetherdb.codec;

import io.aetherdb.api.typed.ValueCodec;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.UUID;

/** Factory for stable scalar value codecs. */
public final class BuiltInValueCodecs {
    private BuiltInValueCodecs() {}

    /**
     * Creates a version-one UTF-8 string value codec.
     *
     * @param schemaId durable application-selected schema identity
     * @return string value codec
     */
    public static ValueCodec<String> utf8String(UUID schemaId) {
        return new ValueCodec<>() {
            public UUID schemaId() {
                return schemaId;
            }

            public int currentSchemaVersion() {
                return 1;
            }

            public int maximumEncodedSize(String v) {
                return 16 * 1024 * 1024;
            }

            public byte[] fingerprint() {
                try {
                    return MessageDigest.getInstance("SHA-256")
                            .digest("aether:utf8-value:v1".getBytes(StandardCharsets.UTF_8));
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
            }

            public byte[] encode(String v) {
                return v.getBytes(StandardCharsets.UTF_8);
            }

            public String decode(int version, byte[] b) {
                if (version != 1) throw new IllegalArgumentException("unknown schema version");
                return new String(b, StandardCharsets.UTF_8);
            }
        };
    }
}
