package io.aetherdb.wal.format;

import io.aetherdb.format.checksum.MaskedCrc32c;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic physical fragmentation and strict logical reassembly. */
public final class WalFragmentCodec {
    private WalFragmentCodec() {}

    /** Fragments one logical record along physical block boundaries.
     * @param logical logical record bytes
     * @param startOffset physical file offset
     * @param recordNumber positive segment-local record number
     * @return encoded physical fragments including block padding */
    public static byte[] fragment(byte[] logical, long startOffset, int recordNumber) {
        if (logical == null || logical.length == 0 || logical.length > WalFormatV1.MAX_LOGICAL_GROUP_BYTES || recordNumber <= 0)
            throw new IllegalArgumentException("invalid logical WAL record");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int cursor = 0, fragmentIndex = 0;
        long fileOffset = startOffset;
        while (cursor < logical.length) {
            int blockRemaining = (int) (WalFormatV1.BLOCK_BYTES - fileOffset % WalFormatV1.BLOCK_BYTES);
            if (blockRemaining <= WalFormatV1.FRAGMENT_HEADER_BYTES) {
                output.writeBytes(new byte[blockRemaining]); fileOffset += blockRemaining; continue;
            }
            int payloadLength = Math.min(logical.length - cursor, blockRemaining - WalFormatV1.FRAGMENT_HEADER_BYTES);
            boolean first = cursor == 0, last = cursor + payloadLength == logical.length;
            byte type = first && last ? WalFormatV1.FULL : first ? WalFormatV1.FIRST : last ? WalFormatV1.LAST : WalFormatV1.MIDDLE;
            byte[] fragment = new byte[WalFormatV1.FRAGMENT_HEADER_BYTES + payloadLength];
            ByteBuffer header = ByteBuffer.wrap(fragment).order(ByteOrder.LITTLE_ENDIAN);
            header.position(4); header.putShort((short) payloadLength).put(type).put((byte) 1)
                    .putInt(recordNumber).putShort((short) fragmentIndex).putShort((short) 0);
            System.arraycopy(logical, cursor, fragment, WalFormatV1.FRAGMENT_HEADER_BYTES, payloadLength);
            header.putInt(0, MaskedCrc32c.masked(fragment, 4, fragment.length - 4));
            output.writeBytes(fragment);
            cursor += payloadLength; fileOffset += fragment.length; fragmentIndex++;
        }
        return output.toByteArray();
    }

    /** Strictly reassembles complete logical records from physical bytes.
     * @param physical physical fragment bytes
     * @param startOffset file offset corresponding to the first byte
     * @return complete verified logical records; an incomplete tail is ignored */
    public static List<byte[]> reassemble(byte[] physical, long startOffset) {
        List<byte[]> records = new ArrayList<>();
        ByteArrayOutputStream assembling = null;
        int expectedRecord = 1, expectedFragment = 0, cursor = 0;
        long fileOffset = startOffset;
        while (cursor < physical.length) {
            int blockRemaining = (int) (WalFormatV1.BLOCK_BYTES - fileOffset % WalFormatV1.BLOCK_BYTES);
            if (blockRemaining <= WalFormatV1.FRAGMENT_HEADER_BYTES) {
                int available = Math.min(blockRemaining, physical.length - cursor);
                for (int i = 0; i < available; i++) if (physical[cursor + i] != 0) throw corrupt("non-zero block trailer");
                cursor += available; fileOffset += available; continue;
            }
            if (physical.length - cursor < WalFormatV1.FRAGMENT_HEADER_BYTES) break;
            byte[] headerBytes = Arrays.copyOfRange(physical, cursor, cursor + WalFormatV1.FRAGMENT_HEADER_BYTES);
            boolean zero = true; for (byte value : headerBytes) zero &= value == 0;
            if (zero) throw corrupt("large zero fragment header");
            ByteBuffer header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
            int storedCrc = header.getInt();
            int length = Short.toUnsignedInt(header.getShort());
            byte type = header.get(), version = header.get();
            long record = Integer.toUnsignedLong(header.getInt());
            int fragment = Short.toUnsignedInt(header.getShort()), flags = Short.toUnsignedInt(header.getShort());
            if (length == 0 || length > blockRemaining - 16 || cursor + 16L + length > physical.length) break;
            byte[] complete = Arrays.copyOfRange(physical, cursor, cursor + 16 + length);
            if (storedCrc != MaskedCrc32c.masked(complete, 4, complete.length - 4)) throw corrupt("fragment checksum mismatch");
            if (version != 1 || flags != 0 || record != expectedRecord || fragment != expectedFragment) throw corrupt("fragment metadata mismatch");
            byte[] payload = Arrays.copyOfRange(complete, 16, complete.length);
            if (type == WalFormatV1.FULL && assembling == null && fragment == 0) records.add(payload);
            else if (type == WalFormatV1.FIRST && assembling == null && fragment == 0) { assembling = new ByteArrayOutputStream(); assembling.writeBytes(payload); }
            else if (type == WalFormatV1.MIDDLE && assembling != null) assembling.writeBytes(payload);
            else if (type == WalFormatV1.LAST && assembling != null) { assembling.writeBytes(payload); records.add(assembling.toByteArray()); assembling = null; }
            else throw corrupt("invalid fragment sequence");
            expectedFragment++;
            if (type == WalFormatV1.FULL || type == WalFormatV1.LAST) { expectedRecord++; expectedFragment = 0; }
            cursor += complete.length; fileOffset += complete.length;
        }
        return records;
    }

    private static WalCorruptionException corrupt(String message) { return new WalCorruptionException(message); }
}
