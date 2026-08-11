package io.aetherdb.workbench;

import io.aetherdb.codec.TypedValueEnvelope;
import io.aetherdb.codec.generated.CanonicalRecordReader;
import io.aetherdb.codec.generated.CanonicalRecordWriter;
import io.aetherdb.codec.generated.WireType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Schema-independent inspection and editing for typed AETV/AER1 values. */
final class UniversalTypedValue {
    private static final int MAXIMUM_RECORD_BYTES = 64 * 1024 * 1024;
    private static final HexFormat HEX = HexFormat.of();

    private UniversalTypedValue() {}

    static boolean isTyped(byte[] value) {
        return value != null
                && value.length >= TypedValueEnvelope.HEADER_BYTES
                && value[0] == 'A'
                && value[1] == 'E'
                && value[2] == 'T'
                && value[3] == 'V';
    }

    static String display(byte[] value) {
        return display(value, new byte[0]);
    }

    static String display(byte[] value, byte[] descriptor) {
        Envelope envelope = Envelope.read(value);
        if (!isAer1(envelope.payload())) return displayScalar(envelope.payload());

        Map<Integer, String> fieldNames = descriptorFieldNames(descriptor);
        StringBuilder output = new StringBuilder();
        CanonicalRecordReader reader =
                new CanonicalRecordReader(envelope.payload(), MAXIMUM_RECORD_BYTES);
        while (reader.next()) {
            output.append(reader.fieldId()).append(':');
            String fieldName = fieldNames.get(reader.fieldId());
            if (fieldName != null) output.append(fieldName).append(':');
            output.append(typeName(reader.wireType()))
                    .append('=')
                    .append(escapeText(displayField(reader)))
                    .append('\n');
        }
        return output.toString();
    }

    static byte[] reencode(byte[] original, String edited) {
        Envelope envelope = Envelope.read(original);
        byte[] payload =
                isAer1(envelope.payload())
                        ? encodeRecord(edited)
                        : encodeScalar(envelope.payload(), edited);
        return envelope.withPayload(payload);
    }

    private static byte[] encodeRecord(String edited) {
        CanonicalRecordWriter writer = new CanonicalRecordWriter(MAXIMUM_RECORD_BYTES);
        for (String line : edited.split("\\R", -1)) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int colon = line.indexOf(':');
            int equals = line.indexOf('=', colon + 1);
            if (colon <= 0 || equals <= colon + 1) {
                throw new IllegalArgumentException(
                        "each field must use fieldId:type=value (for example 16:string=Ada)");
            }
            int fieldId;
            try {
                fieldId = Integer.parseInt(line.substring(0, colon).strip());
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(
                        "invalid field ID: " + line.substring(0, colon), failure);
            }
            String metadata = line.substring(colon + 1, equals).strip();
            int nameSeparator = metadata.lastIndexOf(':');
            String type =
                    (nameSeparator < 0 ? metadata : metadata.substring(nameSeparator + 1)).strip();
            String value = unescapeText(line.substring(equals + 1));
            writer.field(fieldId, wireType(type), encodeField(type, value));
        }
        return writer.finish();
    }

    private static Map<Integer, String> descriptorFieldNames(byte[] descriptor) {
        if (descriptor == null || descriptor.length == 0) return Map.of();
        String text = new String(descriptor, StandardCharsets.UTF_8);
        if (!text.startsWith("AETHER_SCHEMA_DESCRIPTOR_V1\n")) return Map.of();
        Map<Integer, String> names = new HashMap<>();
        for (String line : text.split("\\n")) {
            if (!line.startsWith("field=")) continue;
            String[] parts = line.substring(6).split("\\|", -1);
            if (parts.length != 4) continue;
            try {
                names.put(Integer.parseInt(parts[0]), parts[1]);
            } catch (NumberFormatException ignored) {
                /* malformed descriptor is not trusted */
            }
        }
        return Map.copyOf(names);
    }

    private static String displayField(CanonicalRecordReader reader) {
        return switch (reader.wireType()) {
            case WireType.BOOL -> Boolean.toString(reader.boolValue());
            case WireType.SIGNED_VARINT -> Long.toString(reader.signedLongValue());
            case WireType.FIXED64 -> Double.toString(reader.fixed64Value());
            case WireType.STRING_UTF8 -> reader.stringValue(MAXIMUM_RECORD_BYTES);
            case WireType.UUID128 -> reader.uuidValue().toString();
            case WireType.TEMPORAL -> reader.instantValue().toString();
            default -> HEX.formatHex(reader.rawPayload());
        };
    }

    private static byte[] encodeField(String type, String value) {
        try {
            return switch (type) {
                case "bool" -> CanonicalRecordWriter.bool(parseBoolean(value));
                case "long" -> CanonicalRecordWriter.signedLong(Long.parseLong(value));
                case "double" -> CanonicalRecordWriter.fixed64(Double.parseDouble(value));
                case "string" -> CanonicalRecordWriter.string(value, MAXIMUM_RECORD_BYTES);
                case "uuid" -> CanonicalRecordWriter.uuid(UUID.fromString(value));
                case "instant" -> CanonicalRecordWriter.instant(Instant.parse(value));
                default -> {
                    if (!type.startsWith("wire-"))
                        throw new IllegalArgumentException("unknown type: " + type);
                    yield HEX.parseHex(value);
                }
            };
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid " + type + " value: " + value, failure);
        }
    }

    private static boolean parseBoolean(String value) {
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw new IllegalArgumentException("boolean must be true or false");
    }

    private static String typeName(int wireType) {
        return switch (wireType) {
            case WireType.BOOL -> "bool";
            case WireType.SIGNED_VARINT -> "long";
            case WireType.FIXED64 -> "double";
            case WireType.STRING_UTF8 -> "string";
            case WireType.UUID128 -> "uuid";
            case WireType.TEMPORAL -> "instant";
            default -> "wire-" + wireType;
        };
    }

    private static int wireType(String type) {
        return switch (type) {
            case "bool" -> WireType.BOOL;
            case "long" -> WireType.SIGNED_VARINT;
            case "double" -> WireType.FIXED64;
            case "string" -> WireType.STRING_UTF8;
            case "uuid" -> WireType.UUID128;
            case "instant" -> WireType.TEMPORAL;
            default -> {
                if (!type.startsWith("wire-"))
                    throw new IllegalArgumentException("unknown type: " + type);
                try {
                    yield Integer.parseInt(type.substring(5));
                } catch (NumberFormatException failure) {
                    throw new IllegalArgumentException("invalid wire type: " + type, failure);
                }
            }
        };
    }

    private static String displayScalar(byte[] payload) {
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(payload))
                            .toString();
            if (text.codePoints()
                    .allMatch(
                            codePoint ->
                                    !Character.isISOControl(codePoint)
                                            || codePoint == '\n'
                                            || codePoint == '\r'
                                            || codePoint == '\t')) {
                return escapeText(text);
            }
        } catch (CharacterCodingException ignored) {
            // Binary scalar values are represented explicitly as hexadecimal.
        }
        return "hex:" + HEX.formatHex(payload);
    }

    private static byte[] encodeScalar(byte[] original, String edited) {
        if (isDisplayableUtf8(original))
            return unescapeText(edited).getBytes(StandardCharsets.UTF_8);
        if (edited.startsWith("hex:")) {
            try {
                return HEX.parseHex(edited.substring(4).strip());
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("invalid hexadecimal scalar value", failure);
            }
        }
        throw new IllegalArgumentException("binary typed values must start with hex:");
    }

    private static boolean isDisplayableUtf8(byte[] payload) {
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(payload))
                            .toString();
            return text.codePoints()
                    .allMatch(
                            codePoint ->
                                    !Character.isISOControl(codePoint)
                                            || codePoint == '\n'
                                            || codePoint == '\r'
                                            || codePoint == '\t');
        } catch (CharacterCodingException ignored) {
            return false;
        }
    }

    static String escapeText(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    static String unescapeText(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!escaped && current == '\\') {
                escaped = true;
                continue;
            }
            if (escaped) {
                decoded.append(
                        switch (current) {
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case '\\' -> '\\';
                            default ->
                                    throw new IllegalArgumentException(
                                            "unsupported escape: \\" + current);
                        });
                escaped = false;
            } else decoded.append(current);
        }
        if (escaped) throw new IllegalArgumentException("unfinished escape at end of value");
        return decoded.toString();
    }

    private static boolean isAer1(byte[] payload) {
        return payload.length >= CanonicalRecordWriter.HEADER_BYTES
                && payload[0] == 'A'
                && payload[1] == 'E'
                && payload[2] == 'R'
                && payload[3] == '1';
    }

    private record Envelope(byte[] header, byte[] payload) {
        static Envelope read(byte[] value) {
            if (!isTyped(value)) throw new IllegalArgumentException("invalid typed value envelope");
            ByteBuffer input = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN);
            input.position(4);
            if (input.getShort() != 1 || input.getShort() != TypedValueEnvelope.HEADER_BYTES) {
                throw new IllegalArgumentException("unsupported typed value envelope");
            }
            input.position(28);
            int flags = input.getInt();
            int payloadLength = input.getInt();
            int reserved = input.getInt();
            if (flags != 0
                    || reserved != 0
                    || payloadLength < 0
                    || payloadLength != input.remaining()) {
                throw new IllegalArgumentException("invalid typed value envelope");
            }
            return new Envelope(
                    Arrays.copyOf(value, TypedValueEnvelope.HEADER_BYTES),
                    Arrays.copyOfRange(value, TypedValueEnvelope.HEADER_BYTES, value.length));
        }

        byte[] withPayload(byte[] replacement) {
            byte[] result = Arrays.copyOf(header, header.length + replacement.length);
            ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN).putInt(32, replacement.length);
            System.arraycopy(replacement, 0, result, header.length, replacement.length);
            return result;
        }
    }
}
