package io.aetherdb.codec.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AetherRecordProcessorTest {
    @TempDir Path temporaryDirectory;

    @Test void rejectsInvalidSchemaUuid() throws Exception {
        assertFailure(record("not-a-uuid", 1, validFields()), "SCHEMA_ID_INVALID");
    }

    @Test void rejectsInvalidSchemaVersion() throws Exception {
        assertFailure(record("a0e988c2-74f0-4243-b44f-c395916e0a74", 0, validFields()),
                "SCHEMA_VERSION_INVALID");
    }

    @Test void rejectsDuplicateAndReservedFieldIds() throws Exception {
        String fields = "@AetherField(id=16) long first, @AetherField(id=16) long second,"
                + " @AetherField(id=2) boolean reserved";
        Compilation result = compile(record(
                "a0e988c2-74f0-4243-b44f-c395916e0a74", 1, fields));
        assertThat(result.success).isFalse();
        assertThat(result.messages()).contains("FIELD_ID_DUPLICATE").contains("FIELD_ID_RESERVED");
    }

    @Test void rejectsUnboundedString() throws Exception {
        assertFailure(record(
                "a0e988c2-74f0-4243-b44f-c395916e0a74",
                1,
                "@AetherField(id=16) String value"), "FIELD_BOUND_REQUIRED");
    }

    @Test void rejectsUnsupportedFieldType() throws Exception {
        assertFailure(record(
                "a0e988c2-74f0-4243-b44f-c395916e0a74",
                1,
                "@AetherField(id=16) java.net.URI value"), "FIELD_TYPE_UNSUPPORTED");
    }

    @Test void automaticIdentityWithoutCommittedLockFailsActionably() throws Exception {
        String source = """
                package test;
                import io.aetherdb.codec.annotation.AetherRecord;
                @AetherRecord(version=1)
                public record InvalidRecord(String value) {}
                """;
        Compilation result = compile(source);
        assertThat(result.success).isFalse();
        assertThat(result.messages())
                .contains("AETHER_SCHEMA_LOCK_MISSING")
                .contains("aetherSchemaInit");
    }

    private void assertFailure(String source, String expectedDiagnostic) throws Exception {
        Compilation result = compile(source);
        assertThat(result.success).isFalse();
        assertThat(result.messages()).contains(expectedDiagnostic);
    }

    private Compilation compile(String source) throws Exception {
        Path sourceFile = temporaryDirectory.resolve("test/InvalidRecord.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        Path classes = temporaryDirectory.resolve("classes-" + System.nanoTime());
        Files.createDirectories(classes);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjects(sourceFile.toFile());
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    files,
                    diagnostics,
                    List.of(
                            "--release", "21",
                            "-classpath", System.getProperty("java.class.path"),
                            "-d", classes.toString(),
                            "-proc:only"),
                    null,
                    units);
            task.setProcessors(List.of(new AetherRecordProcessor()));
            return new Compilation(Boolean.TRUE.equals(task.call()), diagnostics.getDiagnostics());
        }
    }

    private static String record(String schemaId, int version, String fields) {
        return """
                package test;
                import io.aetherdb.codec.annotation.*;
                @AetherRecord(schemaId="%s", version=%d)
                public record InvalidRecord(%s) {}
                """.formatted(schemaId, version, fields);
    }

    private static String validFields() {
        return "@AetherField(id=16) long value";
    }

    private record Compilation(
            boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        String messages() {
            return diagnostics.stream()
                    .map(diagnostic -> diagnostic.getMessage(null))
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
    }
}
