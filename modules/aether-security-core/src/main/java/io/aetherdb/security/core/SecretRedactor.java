package io.aetherdb.security.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Redacts sensitive values with a stable non-secret hash prefix for correlation. */
public final class SecretRedactor {
    private static final int PREFIX_BYTES = 8;

    private SecretRedactor() {}

    public static String redact(String kind, String secret) {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("blank secret kind");
        if (secret == null || secret.isEmpty()) return "REDACTED:" + kind + ":empty";
        byte[] digest = sha256(secret.getBytes(StandardCharsets.UTF_8));
        return "REDACTED:" + kind + ":" + HexFormat.of().formatHex(digest, 0, PREFIX_BYTES);
    }

    public static String redactBytes(String kind, byte[] secret) {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("blank secret kind");
        if (secret == null || secret.length == 0) return "REDACTED:" + kind + ":empty";
        byte[] digest = sha256(secret);
        return "REDACTED:" + kind + ":" + HexFormat.of().formatHex(digest, 0, PREFIX_BYTES);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
