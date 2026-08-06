package io.aetherdb.replication.log;

import io.aetherdb.format.checksum.MaskedCrc32c;
import io.aetherdb.replication.api.ReplicatedEntryType;
import io.aetherdb.replication.api.ReplicatedLogEntry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/** Exact aligned replicated-log entry record v1 encoder, parser, and hash-chain verifier. */
public final class ReplicatedLogEntryCodecV1 {
    private ReplicatedLogEntryCodecV1() {}

    /** Builds an immutable entry and computes its payload and entry hashes. */
    public static ReplicatedLogEntry create(ReplicatedEntryType type, int payloadFormatVersion,
                                            long index, long term, UUID commandId,
                                            long sequenceStart, long stateSequenceAfter,
                                            byte[] previousEntryHash, byte[] payload) {
        byte[] payloadCopy = payload == null ? null : payload.clone();
        if (payloadCopy == null) throw new IllegalArgumentException("payload must not be null");
        byte[] payloadHash = sha256(payloadCopy);
        byte[] entryHash = entryHash(type, payloadFormatVersion, index, term, commandId,
                sequenceStart, stateSequenceAfter, payloadCopy.length, payloadHash, previousEntryHash);
        ReplicatedLogEntry entry = new ReplicatedLogEntry(type, payloadFormatVersion, index, term,
                commandId, sequenceStart, stateSequenceAfter, previousEntryHash, entryHash, payloadCopy);
        validateCommandConsistency(entry); return entry;
    }

    /** Encodes one complete aligned record including its trailer checksum. */
    public static byte[] encode(ReplicatedLogEntry entry) {
        if (entry == null) throw new IllegalArgumentException("entry must not be null");
        validateCommandConsistency(entry); byte[] payload = entry.payload(), payloadHash = sha256(payload);
        byte[] expectedHash = entryHash(entry.type(), entry.payloadFormatVersion(), entry.index(), entry.term(),
                entry.commandId(), entry.sequenceStart(), entry.stateSequenceAfter(), payload.length,
                payloadHash, entry.previousEntryHash());
        if (!MessageDigest.isEqual(expectedHash, entry.entryHash())) throw new IllegalArgumentException("entry hash mismatch");
        int length = ReplicatedLogFormatV1.recordLength(payload.length);
        byte[] result = new byte[length]; ByteBuffer bytes = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("AERE".getBytes(StandardCharsets.US_ASCII)).putShort((short) 1)
                .putShort((short) ReplicatedLogFormatV1.ENTRY_HEADER_BYTES).putInt(length)
                .put((byte) entry.type().code()).put((byte) 0).putShort((short) entry.payloadFormatVersion())
                .putLong(entry.index()).putLong(entry.term()).putLong(entry.commandId().getMostSignificantBits())
                .putLong(entry.commandId().getLeastSignificantBits()).putLong(entry.sequenceStart())
                .putLong(entry.stateSequenceAfter()).putInt(payload.length).putInt(0).put(payloadHash)
                .put(entry.previousEntryHash()).put(entry.entryHash());
        bytes.putInt(MaskedCrc32c.masked(result, 0, 168)); bytes.position(ReplicatedLogFormatV1.ENTRY_HEADER_BYTES).put(payload);
        int trailer = length - ReplicatedLogFormatV1.ENTRY_TRAILER_BYTES;
        byte[] magic = "AEND".getBytes(StandardCharsets.US_ASCII); System.arraycopy(magic, 0, result, trailer + 4, 4);
        byte[] coverage = new byte[trailer + 4]; System.arraycopy(result, 0, coverage, 0, trailer);
        System.arraycopy(magic, 0, coverage, trailer, 4);
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(trailer,
                MaskedCrc32c.masked(coverage, 0, coverage.length)); return result;
    }

    /** Decodes and validates one exact complete record. */
    public static ReplicatedLogEntry decode(byte[] record) {
        if (record == null || record.length < 200 || record.length > ReplicatedLogFormatV1.MAXIMUM_ENTRY_RECORD_BYTES
                || record.length % ReplicatedLogFormatV1.ALIGNMENT_BYTES != 0) {
            throw new IllegalArgumentException("invalid replicated entry record length");
        }
        ByteBuffer bytes = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN); byte[] magic = new byte[4]; bytes.get(magic);
        if (!Arrays.equals(magic, "AERE".getBytes(StandardCharsets.US_ASCII)) || bytes.getShort() != 1
                || bytes.getShort() != ReplicatedLogFormatV1.ENTRY_HEADER_BYTES || bytes.getInt() != record.length) {
            throw new IllegalArgumentException("invalid replicated entry header");
        }
        ReplicatedEntryType type = ReplicatedEntryType.fromCode(Byte.toUnsignedInt(bytes.get()));
        if (bytes.get() != 0) throw new IllegalArgumentException("nonzero entry flags");
        int payloadVersion = Short.toUnsignedInt(bytes.getShort()); long index = bytes.getLong(), term = bytes.getLong();
        UUID command = new UUID(bytes.getLong(), bytes.getLong()); long sequenceStart = bytes.getLong(), stateAfter = bytes.getLong();
        int payloadLength = bytes.getInt(); if (bytes.getInt() != 0) throw new IllegalArgumentException("nonzero entry reserved field");
        byte[] payloadHash = new byte[32], previousHash = new byte[32], storedEntryHash = new byte[32];
        bytes.get(payloadHash).get(previousHash).get(storedEntryHash); int storedHeaderCrc = bytes.getInt();
        for (int offset = 172; offset < 192; offset++) if (record[offset] != 0) throw new IllegalArgumentException("nonzero entry reserved byte");
        if (storedHeaderCrc != MaskedCrc32c.masked(record, 0, 168)) throw new IllegalArgumentException("entry header checksum mismatch");
        if (payloadLength < 0 || ReplicatedLogFormatV1.recordLength(payloadLength) != record.length) {
            throw new IllegalArgumentException("entry payload/record length mismatch");
        }
        int payloadEnd = ReplicatedLogFormatV1.ENTRY_HEADER_BYTES + payloadLength;
        int trailer = record.length - ReplicatedLogFormatV1.ENTRY_TRAILER_BYTES;
        for (int offset = payloadEnd; offset < trailer; offset++) if (record[offset] != 0) throw new IllegalArgumentException("nonzero entry alignment padding");
        if (record[trailer + 4] != 'A' || record[trailer + 5] != 'E'
                || record[trailer + 6] != 'N' || record[trailer + 7] != 'D') {
            throw new IllegalArgumentException("entry trailer magic mismatch");
        }
        byte[] coverage = new byte[trailer + 4]; System.arraycopy(record, 0, coverage, 0, trailer);
        System.arraycopy(record, trailer + 4, coverage, trailer, 4);
        if (ByteBuffer.wrap(record, trailer, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                != MaskedCrc32c.masked(coverage, 0, coverage.length)) {
            throw new IllegalArgumentException("entry record checksum mismatch");
        }
        byte[] payload = Arrays.copyOfRange(record, ReplicatedLogFormatV1.ENTRY_HEADER_BYTES, payloadEnd);
        if (!MessageDigest.isEqual(payloadHash, sha256(payload))) throw new IllegalArgumentException("entry payload hash mismatch");
        byte[] calculatedEntryHash = entryHash(type, payloadVersion, index, term, command,
                sequenceStart, stateAfter, payloadLength, payloadHash, previousHash);
        if (!MessageDigest.isEqual(storedEntryHash, calculatedEntryHash)) throw new IllegalArgumentException("entry hash-chain mismatch");
        ReplicatedLogEntry entry = new ReplicatedLogEntry(type, payloadVersion, index, term, command,
                sequenceStart, stateAfter, previousHash, storedEntryHash, payload);
        validateCommandConsistency(entry); return entry;
    }

    private static byte[] entryHash(ReplicatedEntryType type, int payloadVersion, long index, long term,
                                    UUID commandId, long sequenceStart, long stateAfter, int payloadLength,
                                    byte[] payloadHash, byte[] previousHash) {
        if (type == null || commandId == null || payloadHash == null || payloadHash.length != 32
                || previousHash == null || previousHash.length != 32) throw new IllegalArgumentException("invalid entry hash input");
        byte[] prefix = "AETHER-RLOG-ENTRY-V1".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer bytes = ByteBuffer.allocate(prefix.length + 32 + 1 + 1 + 2 + 6 * 8 + 4 + 32)
                .order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(prefix).put(previousHash).put((byte) type.code()).put((byte) 0).putShort((short) payloadVersion)
                .putLong(index).putLong(term).putLong(commandId.getMostSignificantBits())
                .putLong(commandId.getLeastSignificantBits()).putLong(sequenceStart).putLong(stateAfter)
                .putInt(payloadLength).put(payloadHash); return sha256(bytes.array());
    }

    private static void validateCommandConsistency(ReplicatedLogEntry entry) {
        if (entry.type() != ReplicatedEntryType.COMMAND) return;
        ReplicatedWriteCommandV1 command = ReplicatedWriteCommandV1.decode(entry.payload());
        if (!command.commandId().equals(entry.commandId()) || command.sequences().first() != entry.sequenceStart()
                || command.sequences().last() != entry.stateSequenceAfter()) {
            throw new IllegalArgumentException("outer entry and command envelope disagree");
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
