package io.aetherdb.codec;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static java.nio.charset.StandardCharsets.UTF_8;

import org.junit.jupiter.api.Test;

class SchemaCompatibilityCheckerTest {
    private static final String ID = "12345678-1234-5678-9abc-def012345678";

    @Test
    void acceptsStableRenameAndOptionalAddition() {
        byte[] versionOne = descriptor(1, "field=16|name|STRING|100|false\n");
        byte[] versionTwo =
                descriptor(
                        2,
                        "field=16|displayName|STRING|200|false\n"
                                + "field=17|nickname|STRING|80|true\n");

        assertThatCode(() -> SchemaCompatibilityChecker.requireCompatible(versionOne, versionTwo))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsRequiredAdditionRemovalTypeChangeAndTighterBound() {
        byte[] original = descriptor(1, "field=16|name|STRING|100|false\n");

        assertThatThrownBy(
                        () ->
                                SchemaCompatibilityChecker.requireCompatible(
                                        original,
                                        descriptor(
                                                2,
                                                "field=16|name|STRING|100|false\n"
                                                        + "field=17|required|STRING|20|false\n")))
                .hasMessageContaining("new field 17 is required");
        assertThatThrownBy(
                        () ->
                                SchemaCompatibilityChecker.requireCompatible(
                                        original, descriptor(2, "")))
                .hasMessageContaining("field 16 was removed");
        assertThatThrownBy(
                        () ->
                                SchemaCompatibilityChecker.requireCompatible(
                                        original, descriptor(2, "field=16|name|UUID|16|false\n")))
                .hasMessageContaining("changed type");
        assertThatThrownBy(
                        () ->
                                SchemaCompatibilityChecker.requireCompatible(
                                        original, descriptor(2, "field=16|name|STRING|99|false\n")))
                .hasMessageContaining("tightened its bound");
    }

    @Test
    void rejectsChangedEnumOrNestedSchemaMeaningAtAStableFieldId() {
        byte[] original =
                descriptor(1, "field=16|status|ENUM|5|false\ndetail=16|enum|1:OPEN,2:CLOSED\n");
        byte[] changed =
                descriptor(2, "field=16|status|ENUM|5|false\ndetail=16|enum|1:OPEN,2:DELETED\n");

        assertThatThrownBy(() -> SchemaCompatibilityChecker.requireCompatible(original, changed))
                .hasMessageContaining("changed enum or nested-schema identity");
    }

    private static byte[] descriptor(int version, String fields) {
        return ("AETHER_SCHEMA_DESCRIPTOR_V1\n"
                        + "schemaId="
                        + ID
                        + "\n"
                        + "version="
                        + version
                        + "\n"
                        + "type=example.Record\n"
                        + "payloadFormat=1\nunknownFields=SKIP\n"
                        + fields)
                .getBytes(UTF_8);
    }
}
