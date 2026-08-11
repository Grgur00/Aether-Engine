package io.aetherdb.codec.generated;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import java.util.function.Function;

/** Strict scalar decoders for length-delimited generated container elements. */
public final class CanonicalElementCodecs {
    private CanonicalElementCodecs() {}

    /** Decodes a Boolean element. */
    public static Boolean bool(byte[] bytes) {
        return read(bytes, WireType.BOOL, CanonicalRecordReader::boolValue);
    }

    /** Decodes a Byte element. */
    public static Byte signedByte(byte[] bytes) {
        return read(bytes, WireType.SIGNED_BYTE, CanonicalRecordReader::signedByteValue);
    }

    /** Decodes a Short element. */
    public static Short signedShort(byte[] bytes) {
        return read(bytes, WireType.SIGNED_VARINT, CanonicalRecordReader::signedShortValue);
    }

    /** Decodes an Integer element. */
    public static Integer signedInt(byte[] bytes) {
        return Math.toIntExact(
                read(bytes, WireType.SIGNED_VARINT, CanonicalRecordReader::signedLongValue));
    }

    /** Decodes a Long element. */
    public static Long signedLong(byte[] bytes) {
        return read(bytes, WireType.SIGNED_VARINT, CanonicalRecordReader::signedLongValue);
    }

    /** Decodes a Float element. */
    public static Float fixed32(byte[] bytes) {
        return read(bytes, WireType.FIXED32, CanonicalRecordReader::fixed32Value);
    }

    /** Decodes a Double element. */
    public static Double fixed64(byte[] bytes) {
        return read(bytes, WireType.FIXED64, CanonicalRecordReader::fixed64Value);
    }

    /** Decodes a Character element. */
    public static Character character(byte[] bytes) {
        return read(bytes, WireType.UNSIGNED_VARINT, CanonicalRecordReader::characterValue);
    }

    /** Decodes a bounded String element. */
    public static String string(byte[] bytes, int maximum) {
        return read(bytes, WireType.STRING_UTF8, reader -> reader.stringValue(maximum));
    }

    /** Decodes a UUID element. */
    public static UUID uuid(byte[] bytes) {
        return read(bytes, WireType.UUID128, CanonicalRecordReader::uuidValue);
    }

    /** Decodes an Instant element. */
    public static Instant instant(byte[] bytes) {
        return read(bytes, WireType.TEMPORAL, CanonicalRecordReader::instantValue);
    }

    /** Decodes a LocalDate element. */
    public static LocalDate localDate(byte[] bytes) {
        return read(bytes, WireType.TEMPORAL, CanonicalRecordReader::localDateValue);
    }

    /** Decodes a LocalTime element. */
    public static LocalTime localTime(byte[] bytes) {
        return read(bytes, WireType.TEMPORAL, CanonicalRecordReader::localTimeValue);
    }

    /** Decodes a LocalDateTime element. */
    public static LocalDateTime localDateTime(byte[] bytes) {
        return read(bytes, WireType.TEMPORAL, CanonicalRecordReader::localDateTimeValue);
    }

    /** Decodes a Duration element. */
    public static Duration duration(byte[] bytes) {
        return read(bytes, WireType.TEMPORAL, CanonicalRecordReader::durationValue);
    }

    /** Decodes a bounded BigInteger element. */
    public static BigInteger bigInteger(byte[] bytes, int maximum) {
        return read(bytes, WireType.DECIMAL, reader -> reader.bigIntegerValue(maximum));
    }

    /** Decodes a bounded BigDecimal element. */
    public static BigDecimal bigDecimal(byte[] bytes, int maximum) {
        return read(bytes, WireType.DECIMAL, reader -> reader.bigDecimalValue(maximum));
    }

    /** Decodes a bounded byte-array element. */
    public static byte[] bytes(byte[] bytes, int maximum) {
        return read(bytes, WireType.BYTES, reader -> reader.bytesValue(maximum));
    }

    private static <T> T read(
            byte[] payload, int wireType, Function<CanonicalRecordReader, T> decoder) {
        if (payload == null || decoder == null)
            throw new IllegalArgumentException("null element payload or decoder");
        CanonicalRecordWriter writer = new CanonicalRecordWriter(Math.addExact(payload.length, 40));
        writer.field(16, wireType, payload);
        byte[] record = writer.finish();
        CanonicalRecordReader reader = new CanonicalRecordReader(record, record.length);
        if (!reader.next()) throw new IllegalArgumentException("missing container element");
        reader.requireWireType(wireType);
        T value = decoder.apply(reader);
        if (reader.next()) throw new IllegalArgumentException("container element trailing field");
        return value;
    }
}
