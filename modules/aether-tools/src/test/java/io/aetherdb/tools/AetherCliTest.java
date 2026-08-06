package io.aetherdb.tools;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.aetherdb.api.AetherDatabase;
import io.aetherdb.engine.Aether;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AetherCliTest {
    @TempDir Path temporaryDirectory;

    @Test void inspectAndFullVerifyReportCurrentStorageTopology() {
        Path root = database();
        Invocation inspect = invoke("inspect", root.toString());
        assertThat(inspect.exitCode).isZero();
        assertThat(inspect.standardOut).contains("Current manifest: MANIFEST-")
                .contains("L0 files/bytes: 1/").contains("Health: valid");

        Invocation verify = invoke("verify", root.toString(), "--level", "FULL", "--json");
        assertThat(verify.exitCode).isZero();
        assertThat(verify.standardOut).contains("\"mode\":\"verify\"")
                .contains("\"manifestRecords\":2").contains("\"warnings\":0");
    }

    @Test void lockFailureAndUnsafeForensicOverrideHaveDistinctOutcomes() {
        Path root = database();
        try (AetherDatabase database = Aether.open(root)) {
            assertThat(database.isClosed()).isFalse();
            assertThat(invoke("inspect", root.toString()).exitCode).isEqualTo(4);
            Invocation unsafe = invoke("inspect", root.toString(), "--unsafe-no-lock");
            assertThat(unsafe.exitCode).isZero();
            assertThat(unsafe.standardError).contains("forensic read-only mode");
        }
    }

    @Test void incompleteManifestTailIsReportedWithoutBeingMutated() throws Exception {
        Path root = database();
        Path manifest;
        try (var files = Files.list(root)) {
            manifest = files.filter(path -> path.getFileName().toString().endsWith(".aeman")).findFirst().orElseThrow();
        }
        long completeBytes = Files.size(manifest); Files.write(manifest, new byte[] {1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        Invocation result = invoke("inspect", root.toString());
        assertThat(result.exitCode).isEqualTo(2); assertThat(result.standardOut).contains("incomplete trailing bytes");
        assertThat(manifest).hasSize(completeBytes + 3);
    }

    @Test void checkpointHasNoWalVerifiesAndBecomesWritableOnFirstOpen() throws Exception {
        Path source = database(), checkpoint = temporaryDirectory.resolve("checkpoint");
        Invocation creation = invoke("checkpoint", source.toString(), checkpoint.toString());
        assertThat(creation.exitCode).isZero(); assertThat(checkpoint.resolve("CHECKPOINT-METADATA")).exists().hasSize(256);
        try (var files = Files.list(checkpoint)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList()).noneMatch(name -> name.endsWith(".aewal"));
        }
        assertThat(invoke("restore-verify", checkpoint.toString()).exitCode).isZero();
        try (AetherDatabase database = Aether.open(checkpoint)) {
            assertThat(new String(database.get("key".getBytes(UTF_8)).value(), UTF_8)).isEqualTo("value");
            database.put("new".getBytes(UTF_8), "writable".getBytes(UTF_8));
        }
        try (AetherDatabase database = Aether.open(checkpoint)) {
            assertThat(new String(database.get("new".getBytes(UTF_8)).value(), UTF_8)).isEqualTo("writable");
        }
    }

    @Test void repairTailRequiresConfirmationBacksUpAndVerifiesBothFiles() throws Exception {
        Path root = database(); Path manifest, wal;
        try (var files = Files.list(root)) {
            List<Path> paths = files.toList();
            manifest = paths.stream().filter(path -> path.getFileName().toString().endsWith(".aeman")).findFirst().orElseThrow();
            wal = paths.stream().filter(path -> path.getFileName().toString().endsWith(".aewal")).findFirst().orElseThrow();
        }
        long manifestBytes = Files.size(manifest), walBytes = Files.size(wal);
        Files.write(manifest, new byte[] {1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        Files.write(wal, new byte[] {4, 5, 6, 7}, java.nio.file.StandardOpenOption.APPEND);
        assertThat(invoke("repair-tail", root.toString()).exitCode).isEqualTo(2);
        assertThat(manifest).hasSize(manifestBytes + 3); assertThat(wal).hasSize(walBytes + 4);
        Invocation repaired = invoke("repair-tail", root.toString(), "--yes");
        assertThat(repaired.exitCode).isZero(); assertThat(manifest).hasSize(manifestBytes); assertThat(wal).hasSize(walBytes);
        try (var files = Files.list(root)) {
            assertThat(files.map(path -> path.getFileName().toString()).filter(name -> name.contains(".pre-repair-")).count()).isEqualTo(2);
        }
        assertThat(invoke("verify", root.toString()).exitCode).isZero();
    }

    @Test void rebuildCurrentRequiresUniqueValidatedManifestAndConfirmation() throws Exception {
        Path root = database(), current = root.resolve("CURRENT"); Files.delete(current);
        Invocation plan = invoke("rebuild-current", root.toString());
        assertThat(plan.exitCode).isEqualTo(2); assertThat(current).doesNotExist();
        Invocation applied = invoke("rebuild-current", root.toString(), "--yes");
        assertThat(applied.exitCode).isZero(); assertThat(current).exists().hasSize(128);
        assertThat(invoke("verify", root.toString()).exitCode).isZero();
    }

    @Test void salvageCreatesNewIdentityAndSupportsLatestAndHistoryModes() throws Exception {
        Path source = temporaryDirectory.resolve("salvage-source"); UUID sourceId;
        try (AetherDatabase database = Aether.open(source)) {
            database.put("key".getBytes(UTF_8), "old".getBytes(UTF_8));
            database.put("key".getBytes(UTF_8), "new".getBytes(UTF_8));
        }
        sourceId = io.aetherdb.io.DatabaseIdentityV1.decode(Files.readAllBytes(source.resolve("DB-IDENTITY"))).databaseId();
        writeSalvageWal(source, sourceId, 99, 100, "wal-only", "from-wal");
        Path latest = temporaryDirectory.resolve("salvage-latest");
        assertThat(invoke("salvage", source.toString(), latest.toString()).exitCode).isZero();
        UUID latestId = io.aetherdb.io.DatabaseIdentityV1.decode(Files.readAllBytes(latest.resolve("DB-IDENTITY"))).databaseId();
        assertThat(latestId).isNotEqualTo(sourceId);
        try (AetherDatabase database = Aether.open(latest)) {
            assertThat(new String(database.get("key".getBytes(UTF_8)).value(), UTF_8)).isEqualTo("new");
            assertThat(new String(database.get("wal-only".getBytes(UTF_8)).value(), UTF_8)).isEqualTo("from-wal");
        }
        Path history = temporaryDirectory.resolve("salvage-history");
        assertThat(invoke("salvage", source.toString(), history.toString(), "--mode", "PRESERVE_VALID_HISTORY").exitCode).isZero();
        assertThat(history.resolve("SALVAGE-REPORT.json")).content().contains("PRESERVE_VALID_HISTORY");
    }

    @Test void salvageSkipsCorruptTableAndPublishesExplicitDataLossReport() throws Exception {
        Path source = database(), destination = temporaryDirectory.resolve("damaged-salvage"); Path table;
        try (var files = Files.list(source)) {
            table = files.filter(path -> path.getFileName().toString().endsWith(".aess")).findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(table); bytes[4096] ^= 1; Files.write(table, bytes);
        Invocation salvage = invoke("salvage", source.toString(), destination.toString());
        assertThat(salvage.exitCode).isEqualTo(2);
        assertThat(destination.resolve("SALVAGE-REPORT.json")).content().contains("\"possibleDataLoss\":true");
        try (AetherDatabase database = Aether.open(destination)) {
            assertThat(database.get("key".getBytes(UTF_8)).isFound()).isFalse();
        }
    }

    private Path database() {
        Path root = temporaryDirectory.resolve("database");
        try (AetherDatabase database = Aether.open(root)) { database.put("key".getBytes(UTF_8), "value".getBytes(UTF_8)); }
        return root;
    }

    private static void writeSalvageWal(Path root, UUID databaseId, long segment, long sequence,
                                        String keyText, String valueText) throws Exception {
        byte[] key = keyText.getBytes(UTF_8), value = valueText.getBytes(UTF_8);
        byte[] logical = new byte[io.aetherdb.wal.format.WalFormatV1.GROUP_HEADER_BYTES
                + io.aetherdb.wal.format.WalFormatV1.OPERATION_HEADER_BYTES + key.length + value.length];
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(logical).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bytes.put("AETHGRP1".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putShort((short) 1)
                .putShort((short) io.aetherdb.wal.format.WalFormatV1.GROUP_HEADER_BYTES).putInt(logical.length)
                .putLong(sequence).putLong(sequence).putInt(1).putInt(0).putInt(0).putInt(0)
                .put((byte) 1).put(new byte[3]).putInt(key.length).putInt(value.length).put(key).put(value);
        bytes.putInt(44, io.aetherdb.format.checksum.MaskedCrc32c.masked(logical, 0, 44));
        byte[] header = new io.aetherdb.wal.format.WalSegmentHeader(databaseId, segment, 0, sequence,
                System.currentTimeMillis()).encodeBlock();
        byte[] fragments = io.aetherdb.wal.format.WalFragmentCodec.fragment(logical,
                io.aetherdb.wal.format.WalFormatV1.HEADER_BLOCK_BYTES, 1);
        byte[] file = new byte[header.length + fragments.length];
        System.arraycopy(header, 0, file, 0, header.length);
        System.arraycopy(fragments, 0, file, header.length, fragments.length);
        Files.write(root.resolve(io.aetherdb.wal.format.WalFormatV1.fileName(segment)), file);
    }

    private static Invocation invoke(String... arguments) {
        PrintStream originalOut = System.out, originalError = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream(), error = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, UTF_8)); System.setErr(new PrintStream(error, true, UTF_8));
            return new Invocation(AetherCli.run(arguments), output.toString(UTF_8), error.toString(UTF_8));
        } finally { System.setOut(originalOut); System.setErr(originalError); }
    }

    private record Invocation(int exitCode, String standardOut, String standardError) { }
}
