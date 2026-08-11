package io.aetherdb.codec;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Validates routine generated-record evolution using canonical schema descriptors. */
public final class SchemaCompatibilityChecker {
    private SchemaCompatibilityChecker() {}

    /**
     * Verifies that a newer generated descriptor can decode values written by an older descriptor.
     *
     * <p>V1 supports stable-ID renames, required-to-optional changes, relaxed bounds, and added
     * optional fields. Removing a field, changing its wire type, tightening a bound, making an
     * optional field required, or adding a required field requires an explicit migration.
     *
     * @param olderDescriptor descriptor stored with the collection
     * @param newerDescriptor descriptor supplied by the prospective writer
     * @throws IllegalArgumentException when the descriptors are absent, malformed, or incompatible
     */
    public static void requireCompatible(byte[] olderDescriptor, byte[] newerDescriptor) {
        Descriptor older = parse(olderDescriptor), newer = parse(newerDescriptor);
        if (!older.schemaId.equals(newer.schemaId)) throw incompatible("schema identity changed");
        if (newer.version <= older.version) throw incompatible("schema version did not increase");
        for (Field previous : older.fields.values()) {
            Field current = newer.fields.get(previous.id);
            if (current == null) throw incompatible("field " + previous.id + " was removed");
            if (!previous.type.equals(current.type))
                throw incompatible("field " + previous.id + " changed type");
            if (!older.details
                    .getOrDefault(previous.id, "")
                    .equals(newer.details.getOrDefault(previous.id, ""))) {
                throw incompatible(
                        "field " + previous.id + " changed enum or nested-schema identity");
            }
            if (current.bound < previous.bound)
                throw incompatible("field " + previous.id + " tightened its bound");
            if (current.maximumEntries < previous.maximumEntries) {
                throw incompatible("field " + previous.id + " tightened its entry bound");
            }
            if (previous.optional && !current.optional)
                throw incompatible("field " + previous.id + " became required");
        }
        for (Field current : newer.fields.values()) {
            if (!older.fields.containsKey(current.id) && !current.optional) {
                throw incompatible(
                        "new field " + current.id + " is required and has no generated default");
            }
        }
    }

    private static Descriptor parse(byte[] encoded) {
        if (encoded == null || encoded.length == 0 || encoded.length > 1024 * 1024) {
            throw incompatible("generated descriptor is unavailable");
        }
        String schemaId = null;
        int version = 0;
        Map<Integer, Field> fields = new HashMap<>();
        Map<Integer, String> details = new HashMap<>();
        String[] lines = new String(encoded, StandardCharsets.UTF_8).split("\\n");
        if (lines.length == 0 || !lines[0].equals("AETHER_SCHEMA_DESCRIPTOR_V1")) {
            throw incompatible("unsupported descriptor format");
        }
        try {
            for (String line : lines) {
                if (line.startsWith("schemaId=")) schemaId = line.substring("schemaId=".length());
                else if (line.startsWith("version="))
                    version = Integer.parseInt(line.substring("version=".length()));
                else if (line.startsWith("field=")) {
                    String[] values = line.substring("field=".length()).split("\\|", -1);
                    if (values.length < 4 || values.length > 6)
                        throw incompatible("malformed field descriptor");
                    int id = Integer.parseInt(values[0]), bound = Integer.parseInt(values[3]);
                    boolean optional = values.length == 5 && Boolean.parseBoolean(values[4]);
                    if (values.length == 6) optional = Boolean.parseBoolean(values[4]);
                    int maximumEntries =
                            values.length == 6 ? Integer.parseInt(values[5]) : Integer.MAX_VALUE;
                    if (id < 16
                            || bound < 0
                            || fields.putIfAbsent(
                                            id,
                                            new Field(
                                                    id, values[2], bound, optional, maximumEntries))
                                    != null) {
                        throw incompatible("invalid or duplicate field identity");
                    }
                } else if (line.startsWith("detail=")) {
                    String[] values = line.substring("detail=".length()).split("\\|", 3);
                    if (values.length != 3
                            || details.putIfAbsent(
                                            Integer.parseInt(values[0]),
                                            values[1] + '|' + values[2])
                                    != null) {
                        throw incompatible("invalid or duplicate field detail");
                    }
                }
            }
        } catch (NumberFormatException failure) {
            throw incompatible("malformed numeric descriptor value");
        }
        if (schemaId == null || version < 1)
            throw incompatible("descriptor identity is incomplete");
        if (!fields.keySet().containsAll(details.keySet()))
            throw incompatible("detail references unknown field");
        return new Descriptor(schemaId, version, Map.copyOf(fields), Map.copyOf(details));
    }

    private static IllegalArgumentException incompatible(String reason) {
        return new IllegalArgumentException("SCHEMA_MIGRATION_REQUIRED: " + reason);
    }

    private record Descriptor(
            String schemaId,
            int version,
            Map<Integer, Field> fields,
            Map<Integer, String> details) {}

    private record Field(int id, String type, int bound, boolean optional, int maximumEntries) {}
}
