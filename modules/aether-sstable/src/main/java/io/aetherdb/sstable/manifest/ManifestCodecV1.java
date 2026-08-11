package io.aetherdb.sstable.manifest;

import io.aetherdb.format.checksum.MaskedCrc32c;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Canonical codec for manifest physical records and edit payloads. */
public final class ManifestCodecV1 {
    /** Physical record header bytes. */
    public static final int RECORD_HEADER_BYTES = 24;

    /** Edit payload header bytes. */
    public static final int EDIT_HEADER_BYTES = 64;

    /** Maximum edit payload bytes. */
    public static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;

    private static final int ADDITION_PREFIX_BYTES = 64;
    private static final int DELETION_BYTES = 16;

    private ManifestCodecV1() {}

    /**
     * Encodes one edit including its checksummed physical record header.
     *
     * @param edit validated snapshot or delta
     * @return canonical physical record
     */
    public static byte[] encodeRecord(ManifestEdit edit) {
        if (edit == null) throw new IllegalArgumentException("edit is required");
        byte[] payload = encodePayload(edit);
        byte[] result = new byte[RECORD_HEADER_BYTES + payload.length];
        ByteBuffer bytes = little(result);
        bytes.position(4)
                .putInt(payload.length)
                .putShort((short) 1)
                .put((byte) (edit.kind() == ManifestEdit.Kind.SNAPSHOT ? 1 : 2))
                .put((byte) 0)
                .putLong(edit.editNumber())
                .putInt(0)
                .put(payload);
        bytes.putInt(0, MaskedCrc32c.masked(result, 4, result.length - 4));
        return result;
    }

    /**
     * Decodes one exact physical record.
     *
     * @param record complete physical record bytes
     * @return validated manifest edit
     */
    public static ManifestEdit decodeRecord(byte[] record) {
        if (record == null || record.length < RECORD_HEADER_BYTES + EDIT_HEADER_BYTES)
            throw corrupt("manifest record too short");
        if (little(record).getInt(0) != MaskedCrc32c.masked(record, 4, record.length - 4))
            throw corrupt("manifest record checksum mismatch");
        ByteBuffer bytes = little(record);
        bytes.position(4);
        int payloadLength = bytes.getInt();
        if (payloadLength < EDIT_HEADER_BYTES
                || payloadLength > MAX_PAYLOAD_BYTES
                || record.length != RECORD_HEADER_BYTES + payloadLength
                || Short.toUnsignedInt(bytes.getShort()) != 1) {
            throw corrupt("invalid manifest physical record length or version");
        }
        int type = Byte.toUnsignedInt(bytes.get());
        if (bytes.get() != 0) throw corrupt("nonzero manifest record flags");
        long recordNumber = bytes.getLong();
        if (recordNumber <= 0 || bytes.getInt() != 0)
            throw corrupt("invalid manifest record number or reserved field");
        ManifestEdit edit =
                decodePayload(Arrays.copyOfRange(record, RECORD_HEADER_BYTES, record.length));
        int expectedType = edit.kind() == ManifestEdit.Kind.SNAPSHOT ? 1 : 2;
        if (type != expectedType || recordNumber != edit.editNumber())
            throw corrupt("manifest record and edit identity disagree");
        return edit;
    }

    /**
     * Reads the declared total physical size from a 24-byte header.
     *
     * @param header exact physical header bytes
     * @return header plus declared payload bytes
     */
    public static int physicalRecordBytes(byte[] header) {
        if (header == null || header.length != RECORD_HEADER_BYTES)
            throw corrupt("invalid manifest record header length");
        int payload = little(header).getInt(4);
        if (payload < EDIT_HEADER_BYTES || payload > MAX_PAYLOAD_BYTES)
            throw corrupt("invalid manifest payload length");
        return RECORD_HEADER_BYTES + payload;
    }

    private static byte[] encodePayload(ManifestEdit edit) {
        long size = EDIT_HEADER_BYTES + (long) DELETION_BYTES * edit.deletions().size();
        for (ManifestFileMetadata file : edit.additions()) {
            size =
                    Math.addExact(
                            size,
                            ADDITION_PREFIX_BYTES
                                    + (long) file.smallestInternalKey().length
                                    + file.largestInternalKey().length);
        }
        if (size > MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("manifest edit exceeds maximum payload");
        byte[] payload = new byte[Math.toIntExact(size)];
        ByteBuffer bytes = little(payload);
        bytes.putInt(payload.length)
                .putShort((short) EDIT_HEADER_BYTES)
                .putShort((short) 1)
                .put((byte) (edit.kind() == ManifestEdit.Kind.SNAPSHOT ? 1 : 2))
                .put((byte) 0)
                .putShort((short) 0)
                .putLong(edit.editNumber())
                .putLong(edit.nextFileNumber())
                .putLong(edit.lastAssignedSequence())
                .putLong(edit.persistedSequenceWatermark())
                .putLong(edit.minimumWalFileNumber())
                .putInt(edit.additions().size())
                .putInt(edit.deletions().size())
                .putInt(0);
        for (ManifestFileMetadata file : edit.additions()) {
            byte[] smallest = file.smallestInternalKey(), largest = file.largestInternalKey();
            bytes.putLong(file.fileNumber())
                    .putInt(file.level())
                    .putInt(0)
                    .putLong(file.fileSize())
                    .putLong(file.entryCount())
                    .putLong(file.smallestSequence())
                    .putLong(file.largestSequence())
                    .putInt(smallest.length)
                    .putInt(largest.length)
                    .putLong(0)
                    .put(smallest)
                    .put(largest);
        }
        for (ManifestDeletion deletion : edit.deletions()) {
            bytes.putLong(deletion.fileNumber()).putInt(deletion.level()).putInt(0);
        }
        return payload;
    }

    private static ManifestEdit decodePayload(byte[] payload) {
        try {
            if (payload.length < EDIT_HEADER_BYTES || payload.length > MAX_PAYLOAD_BYTES)
                throw corrupt("invalid manifest edit size");
            ByteBuffer bytes = little(payload);
            int totalLength = bytes.getInt();
            if (totalLength != payload.length
                    || Short.toUnsignedInt(bytes.getShort()) != EDIT_HEADER_BYTES
                    || Short.toUnsignedInt(bytes.getShort()) != 1)
                throw corrupt("invalid manifest edit prefix");
            int kindCode = Byte.toUnsignedInt(bytes.get());
            if (bytes.get() != 0 || bytes.getShort() != 0)
                throw corrupt("nonzero manifest edit flags");
            ManifestEdit.Kind kind =
                    kindCode == 1
                            ? ManifestEdit.Kind.SNAPSHOT
                            : kindCode == 2 ? ManifestEdit.Kind.DELTA : null;
            if (kind == null) throw corrupt("unknown manifest edit kind");
            long editNumber = bytes.getLong(), next = bytes.getLong(), last = bytes.getLong();
            long persisted = bytes.getLong(), minimumWal = bytes.getLong();
            int additionCount = bytes.getInt(), deletionCount = bytes.getInt();
            if (additionCount < 0
                    || deletionCount < 0
                    || bytes.getInt() != 0
                    || additionCount > bytes.remaining() / ADDITION_PREFIX_BYTES
                    || deletionCount > bytes.remaining() / DELETION_BYTES)
                throw corrupt("invalid manifest edit counts");
            List<ManifestFileMetadata> additions = new ArrayList<>(additionCount);
            for (int index = 0; index < additionCount; index++) {
                if (bytes.remaining() < ADDITION_PREFIX_BYTES)
                    throw corrupt("truncated manifest addition");
                long file = bytes.getLong();
                int level = bytes.getInt();
                if (bytes.getInt() != 0) throw corrupt("nonzero manifest addition flags");
                long fileSize = bytes.getLong(),
                        entries = bytes.getLong(),
                        smallestSequence = bytes.getLong(),
                        largestSequence = bytes.getLong();
                int smallestLength = bytes.getInt(), largestLength = bytes.getInt();
                if (bytes.getLong() != 0
                        || smallestLength < 9
                        || largestLength < 9
                        || smallestLength > bytes.remaining()
                        || largestLength > bytes.remaining() - smallestLength) {
                    throw corrupt("invalid manifest key-bound lengths");
                }
                byte[] smallest = new byte[smallestLength], largest = new byte[largestLength];
                bytes.get(smallest).get(largest);
                additions.add(
                        new ManifestFileMetadata(
                                file,
                                level,
                                fileSize,
                                entries,
                                smallestSequence,
                                largestSequence,
                                smallest,
                                largest));
            }
            List<ManifestDeletion> deletions = new ArrayList<>(deletionCount);
            for (int index = 0; index < deletionCount; index++) {
                if (bytes.remaining() < DELETION_BYTES)
                    throw corrupt("truncated manifest deletion");
                long file = bytes.getLong();
                int level = bytes.getInt();
                if (bytes.getInt() != 0) throw corrupt("nonzero manifest deletion reserved field");
                deletions.add(new ManifestDeletion(file, level));
            }
            if (bytes.hasRemaining()) throw corrupt("manifest edit trailing bytes");
            return new ManifestEdit(
                    kind, editNumber, next, last, persisted, minimumWal, additions, deletions);
        } catch (ManifestCorruptionException failure) {
            throw failure;
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new ManifestCorruptionException("invalid manifest edit fields", failure);
        }
    }

    private static ByteBuffer little(byte[] value) {
        return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ManifestCorruptionException corrupt(String message) {
        return new ManifestCorruptionException(message);
    }
}
