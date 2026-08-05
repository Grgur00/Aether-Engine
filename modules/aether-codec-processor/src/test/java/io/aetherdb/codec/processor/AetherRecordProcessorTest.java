package io.aetherdb.codec.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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

    @Test void proposalAllocatesStableAutomaticIdsAndNormalCompilationUsesTheLock() throws Exception {
        String source = automaticRecord(1, "String id, String title, boolean completed");
        Path proposal = temporaryDirectory.resolve("proposal");
        Compilation proposed = compile(source, null, proposal, true);
        assertThat(proposed.success).withFailMessage(proposed.messages()).isTrue();
        Path index = proposal.resolve("index.json");
        assertThat(index).exists();
        String indexJson = Files.readString(index);
        String schemaId = java.util.regex.Pattern.compile("\\\"schemaId\\\": \\\"([^\\\"]+)")
                .matcher(indexJson).results().findFirst().orElseThrow().group(1);
        String lock = Files.readString(proposal.resolve(schemaId + ".schema.json"));
        assertThat(lock)
                .contains("\"id\": 16,\n          \"javaName\": \"id\"")
                .contains("\"id\": 17,\n          \"javaName\": \"title\"")
                .contains("\"id\": 18,\n          \"javaName\": \"completed\"");

        Path committed = temporaryDirectory.resolve("committed");
        copyDirectory(proposal, committed);
        Compilation first = compile(source, committed, null, false);
        Compilation reordered = compile(
                automaticRecord(1, "boolean completed, String title, String id"),
                committed, null, false);
        assertThat(first.success).isTrue();
        assertThat(reordered.success).isTrue();
        Path descriptor = Path.of("META-INF/aether/schemas", schemaId, "1.aesch");
        assertThat(Files.readAllBytes(first.classes.resolve(descriptor)))
                .isEqualTo(Files.readAllBytes(reordered.classes.resolve(descriptor)));
        assertThat(encode(first, new Class<?>[] {String.class, String.class, boolean.class},
                new Object[] {"todo-1", "Title", true}))
                .isEqualTo(encode(reordered,
                        new Class<?>[] {boolean.class, String.class, String.class},
                        new Object[] {true, "Title", "todo-1"}));
    }

    @Test void updateAllocatesNextIdAndRenameHintRetainsIdentity() throws Exception {
        Path initial = temporaryDirectory.resolve("initial-proposal");
        Compilation initialCompilation = compile(
                automaticRecord(1, "String id, String title, boolean completed"),
                null, initial, true);
        assertThat(initialCompilation.success)
                .withFailMessage(initialCompilation.messages()).isTrue();
        Path committed = temporaryDirectory.resolve("accepted");
        copyDirectory(initial, committed);

        Path added = temporaryDirectory.resolve("added-proposal");
        assertThat(compile(
                automaticRecord(2,
                        "String id, String title, boolean completed, "
                                + "java.util.Optional<java.time.Instant> dueAt"),
                committed, added, true).success).isTrue();
        String addedLock = schemaLock(added);
        assertThat(addedLock).contains("\"id\": 19,\n          \"javaName\": \"dueAt\"");

        Path renamed = temporaryDirectory.resolve("renamed-proposal");
        String renamedSource = """
                package test;
                import io.aetherdb.codec.annotation.*;
                @AetherRecord(version=2)
                public record InvalidRecord(
                    String id,
                    @AetherField(previousName="title") String description,
                    boolean completed) {}
                """;
        assertThat(compile(renamedSource, committed, renamed, true).success).isTrue();
        assertThat(schemaLock(renamed))
                .contains("\"id\": 17,\n          \"javaName\": \"description\"");
    }

    @Test void removalReservesIdentityAndAmbiguousRenameRequiresHint() throws Exception {
        Path initial = temporaryDirectory.resolve("remove-initial");
        assertThat(compile(
                automaticRecord(1, "String id, String title, boolean completed"),
                null, initial, true).success).isTrue();
        Path committed = temporaryDirectory.resolve("remove-accepted");
        copyDirectory(initial, committed);

        Path removed = temporaryDirectory.resolve("removed-proposal");
        Compilation removal = compile(
                automaticRecord(2, "String id, boolean completed"),
                committed, removed, true);
        assertThat(removal.success).withFailMessage(removal.messages()).isTrue();
        assertThat(schemaLock(removed))
                .contains("\"reservedFieldIds\": [17]")
                .contains("\"javaName\": \"title\"")
                .contains("\"requiredness\": \"RETIRED\"");

        Path removedAccepted = temporaryDirectory.resolve("removed-accepted");
        copyDirectory(removed, removedAccepted);
        Path later = temporaryDirectory.resolve("later-proposal");
        Compilation addition = compile(
                automaticRecord(3, "String id, boolean completed, java.time.Instant dueAt"),
                removedAccepted, later, true);
        assertThat(addition.success).withFailMessage(addition.messages()).isTrue();
        assertThat(schemaLock(later)).contains("\"id\": 19,\n          \"javaName\": \"dueAt\"");

        Compilation ambiguous = compile(
                automaticRecord(2, "String id, String description, boolean completed"),
                committed, temporaryDirectory.resolve("ambiguous"), true);
        assertThat(ambiguous.success).isFalse();
        assertThat(ambiguous.messages()).contains("AETHER_RENAME_AMBIGUOUS");
    }

    @Test void explicitIdsAssertTheLockAndUpdatesRequireVersionIncrement() throws Exception {
        Path proposal = temporaryDirectory.resolve("explicit-initial");
        assertThat(compile(automaticRecord(1, "long value"), null, proposal, true).success).isTrue();
        Path committed = temporaryDirectory.resolve("explicit-accepted");
        copyDirectory(proposal, committed);

        String matching = explicitAutomaticRecord(1, 16, "value");
        assertThat(compile(matching, committed, null, false).success).isTrue();
        Compilation conflicting = compile(
                explicitAutomaticRecord(1, 17, "value"), committed, null, false);
        assertThat(conflicting.success).isFalse();
        assertThat(conflicting.messages()).contains("AETHER_FIELD_ID_LOCK_MISMATCH");

        Compilation staleVersion = compile(
                automaticRecord(1, "long value, boolean added"),
                committed, temporaryDirectory.resolve("stale-version"), true);
        assertThat(staleVersion.success).isFalse();
        assertThat(staleVersion.messages()).contains("AETHER_SCHEMA_VERSION_NOT_INCREMENTED");
    }

    @Test void versionOnePayloadDecodesAfterOptionalVersionTwoAddition() throws Exception {
        String versionOne = automaticRecord(1, "String id, String title, boolean completed");
        Path initial = temporaryDirectory.resolve("history-initial");
        assertThat(compile(versionOne, null, initial, true).success).isTrue();
        Path versionOneLock = temporaryDirectory.resolve("history-v1-lock");
        copyDirectory(initial, versionOneLock);
        Compilation compiledV1 = compile(versionOne, versionOneLock, null, false);
        byte[] stored = encode(compiledV1,
                new Class<?>[] {String.class, String.class, boolean.class},
                new Object[] {"todo-1", "Title", false});

        String versionTwo = automaticRecord(2,
                "String id, String title, boolean completed, "
                        + "java.util.Optional<java.time.Instant> dueAt");
        Path update = temporaryDirectory.resolve("history-update");
        Compilation proposedV2 = compile(versionTwo, versionOneLock, update, true);
        assertThat(proposedV2.success).withFailMessage(proposedV2.messages()).isTrue();
        Path versionTwoLock = temporaryDirectory.resolve("history-v2-lock");
        copyDirectory(update, versionTwoLock);
        Compilation compiledV2 = compile(versionTwo, versionTwoLock, null, false);

        try (var loader = new java.net.URLClassLoader(
                new java.net.URL[] {compiledV2.classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> recordType = loader.loadClass("test.InvalidRecord");
            Class<?> codecType = loader.loadClass("test.InvalidRecord_AetherCodec");
            Object codec = codecType.getField("INSTANCE").get(null);
            Object decoded = codecType.getMethod("decode", int.class, byte[].class)
                    .invoke(codec, 1, stored);
            assertThat(recordType.getMethod("id").invoke(decoded)).isEqualTo("todo-1");
            assertThat(recordType.getMethod("dueAt").invoke(decoded))
                    .isEqualTo(java.util.Optional.empty());
        }
    }

    private void assertFailure(String source, String expectedDiagnostic) throws Exception {
        Compilation result = compile(source);
        assertThat(result.success).isFalse();
        assertThat(result.messages()).contains(expectedDiagnostic);
    }

    private Compilation compile(String source) throws Exception {
        return compile(source, null, null, false);
    }

    private Compilation compile(
            String source, Path schemaDirectory, Path proposalDirectory, boolean propose)
            throws Exception {
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
            List<String> options = new java.util.ArrayList<>(List.of(
                    "--release", "21",
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classes.toString()));
            if (schemaDirectory != null) {
                options.add("-Aaether.schemaDirectory=" + schemaDirectory);
            }
            if (propose) {
                options.add("-Aaether.schemaMode=PROPOSE");
                options.add("-Aaether.schemaProposalDirectory=" + proposalDirectory);
            }
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    files,
                    diagnostics,
                    options,
                    null,
                    units);
            task.setProcessors(List.of(new AetherRecordProcessor()));
            return new Compilation(
                    Boolean.TRUE.equals(task.call()), diagnostics.getDiagnostics(), classes);
        }
    }

    private static String automaticRecord(int version, String fields) {
        return """
                package test;
                import io.aetherdb.codec.annotation.AetherRecord;
                @AetherRecord(version=%d)
                public record InvalidRecord(%s) {}
                """.formatted(version, fields);
    }

    private static String explicitAutomaticRecord(int version, int id, String name) {
        return """
                package test;
                import io.aetherdb.codec.annotation.*;
                @AetherRecord(version=%d)
                public record InvalidRecord(@AetherField(id=%d) long %s) {}
                """.formatted(version, id, name);
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }

    private static String schemaLock(Path directory) throws Exception {
        try (var paths = Files.list(directory)) {
            Path lock = paths.filter(path -> path.getFileName().toString().endsWith(".schema.json"))
                    .findFirst().orElseThrow();
            return Files.readString(lock);
        }
    }

    private byte[] encode(Compilation compilation, Class<?>[] parameterTypes, Object[] arguments)
            throws Exception {
        try (var loader = new java.net.URLClassLoader(
                new java.net.URL[] {compilation.classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> recordType = loader.loadClass("test.InvalidRecord");
            Object record = recordType.getConstructor(parameterTypes).newInstance(arguments);
            Class<?> codecType = loader.loadClass("test.InvalidRecord_AetherCodec");
            Object codec = codecType.getField("INSTANCE").get(null);
            return (byte[]) codecType.getMethod("encode", recordType).invoke(codec, record);
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
            boolean success,
            List<Diagnostic<? extends JavaFileObject>> diagnostics,
            Path classes) {
        String messages() {
            return diagnostics.stream()
                    .map(diagnostic -> diagnostic.getMessage(null))
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
    }
}
