package io.aetherdb.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Versioned AES-GCM ciphertext envelope with explicit key epoch and nonce. */
public record AeadEnvelope(int version, long keyEpoch, byte[] nonce, byte[] ciphertext) {
    public static final int VERSION = 1;
    public static final int NONCE_BYTES = 12;
    private static final byte[] MAGIC = "AEENC1".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_BYTES = 6 + 2 + 8 + 4 + 4;

    public AeadEnvelope {
        if (version != VERSION) throw new IllegalArgumentException("unsupported envelope version");
        if (keyEpoch <= 0) throw new IllegalArgumentException("key epoch must be positive");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(ciphertext, "ciphertext");
        if (nonce.length != NONCE_BYTES) throw new IllegalArgumentException("invalid nonce length");
        if (ciphertext.length < 16) throw new IllegalArgumentException("ciphertext is missing tag");
        nonce = nonce.clone();
        ciphertext = ciphertext.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    public byte[] encode() {
        ByteBuffer out =
                ByteBuffer.allocate(HEADER_BYTES + nonce.length + ciphertext.length)
                        .order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC)
                .putShort((short) version)
                .putLong(keyEpoch)
                .putInt(nonce.length)
                .putInt(ciphertext.length)
                .put(nonce)
                .put(ciphertext);
        return out.array();
    }

    public static AeadEnvelope decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length < HEADER_BYTES + NONCE_BYTES + 16)
            throw new IllegalArgumentException("short encryption envelope");
        ByteBuffer in = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        in.get(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IllegalArgumentException("bad envelope magic");
        int version = Short.toUnsignedInt(in.getShort());
        long keyEpoch = in.getLong();
        int nonceLength = in.getInt();
        int ciphertextLength = in.getInt();
        if (nonceLength != NONCE_BYTES
                || ciphertextLength < 16
                || in.remaining() != nonceLength + ciphertextLength) {
            throw new IllegalArgumentException("invalid envelope lengths");
        }
        byte[] nonce = new byte[nonceLength];
        byte[] ciphertext = new byte[ciphertextLength];
        in.get(nonce).get(ciphertext);
        return new AeadEnvelope(version, keyEpoch, nonce, ciphertext);
    }
}
