package io.aetherdb.format.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

/** Registry of canonical binary fixtures for durable and wire format certification. */
public final class FormatGoldenFixtureCatalog {
    private final Map<String, FormatGoldenFixture> fixtures;

    private FormatGoldenFixtureCatalog(Map<String, FormatGoldenFixture> fixtures) {
        this.fixtures = Map.copyOf(fixtures);
    }

    public static FormatGoldenFixtureCatalog current(AetherFormatCatalog formats) {
        LinkedHashMap<String, FormatGoldenFixture> fixtures = new LinkedHashMap<>();
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.wal_segment.v1",
                        "canonical-header-v1",
                        32 * 1024,
                        "d06d87a5fc0b0b5cfb9300663714b053735bbc0942d53d2813db4090dd40ebe1",
                        "WAL segment header block encoded for database "
                                + "12345678-1234-5678-9abc-def012345678, segment 42, previous "
                                + "segment 41, first sequence 100, and creation epoch millis 1234."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.wal_group.v1",
                        "canonical-put-delete-group-v1",
                        84,
                        "70cde6278eb1ce7e33bfd9a2b1fadad2c72beb50f986546eff6e3c9154b84af0",
                        "WAL logical mutation group encoded for sequence range 101..102 with "
                                + "one put of key alpha to value one and one delete of key beta."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.sstable.v1",
                        "canonical-header-region-v1",
                        4_096,
                        "bd175a35129bd456f84cc2ba42cf0b25bb1876466c28b1031090c60a5bbdb321",
                        "SSTable header region encoded for file number 7, database "
                                + "3b80c2d5-5044-4e3c-b34d-c574805d47e2, 19 entries, "
                                + "sequence range 2..31, three data blocks, creation epoch "
                                + "millis 1234, and file size 8192."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.sstable_block_envelope.v1",
                        "canonical-data-block-envelope-v1",
                        15,
                        "bcb6ac697f0378f2fd7301ac09e983467e689959dc60401ce040f55c7f009102",
                        "SSTable DATA block envelope encoded around the seven-byte raw block "
                                + "payload with v1 uncompressed trailer metadata."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.sstable_restart_block.v1",
                        "canonical-four-entry-block-v1",
                        40,
                        "7a80a581715a275101319dbf45570c812e9490d0f7eafec652d86e54abc61fc2",
                        "SSTable restart block encoded for keys car, carbon, cart, and dog "
                                + "with restart interval 2 and bytewise ordering."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.sstable_block_handle.v1",
                        "canonical-offset-length-v1",
                        16,
                        "54b273ee14ddb7ada27d59d9552c995c75f51304c7adac06d5b45cdee93fb0aa",
                        "SSTable block handle encoded for offset 4096 and physical block "
                                + "length 200."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.sstable_footer.v1",
                        "canonical-four-handle-footer-v1",
                        128,
                        "1b74da5d89248113b86d8a3b58297a296ff9e562d9b53e7252c1a6eee72a176a",
                        "SSTable footer encoded for metaindex, index, filter, and properties "
                                + "handles at offsets 4096, 4176, 4256, and 4336 with length 80, "
                                + "file number 9, file size 8192, and database "
                                + "a791fb43-012a-4af8-93a9-34ae5ed988a7."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.sstable_bloom.v1",
                        "canonical-three-key-filter-v1",
                        32,
                        "1b3bf008425f119ff60a875a9b67d7f4036d3252dba876a839ce1a8f4734c670",
                        "SSTable Bloom filter encoded for user keys alpha, bravo, and charlie "
                                + "with 10 bits per key, seven probes, and a 64-bit minimum filter."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.manifest.v1",
                        "canonical-header-region-v1",
                        4_096,
                        "72ee0cea2c1182a4380b2559de340bb7f73b88728b1a903c8106f3fc0768c148",
                        "Manifest header region encoded for database "
                                + "d9ce3e42-1d8d-48b4-a55b-04c81dbfed9c, manifest file number "
                                + "7, creation epoch millis 1234, initial next file number 9, and "
                                + "initial last sequence 12."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.manifest.v1",
                        "canonical-snapshot-record-v1",
                        256,
                        "f9fd5dca5ecc1d8027d85a5a51bbd95480c8d92f655827e0863222064e55a68d",
                        "Manifest snapshot record encoded for edit 1, next file 10, last "
                                + "sequence 51, persisted sequence 50, minimum WAL file 3, and "
                                + "two live SSTable additions."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.current.v1",
                        "canonical-pointer-v1",
                        128,
                        "0e22c473628071e2d3a323b14089cf9e50d9ef7d3280ee8969a0d6a71794cba0",
                        "CURRENT pointer encoded for database "
                                + "d9ce3e42-1d8d-48b4-a55b-04c81dbfed9c and manifest "
                                + "generation 42."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.database_identity.v1",
                        "canonical-db-identity-v1",
                        128,
                        "ffddc324942ef9fa037f2e90f2a3cc88eeca6c4c0405dfa36b0d3f827cb38251",
                        "DB-IDENTITY encoded for database "
                                + "12345678-1234-5678-9abc-def012345678, creation epoch millis "
                                + "42, and creator version 1.7."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.format_options.v1",
                        "canonical-format-options-v1",
                        4_096,
                        "175825b89f3034dd55b7335f8b226ff3cf0720654839be11775f7761c9135a4d",
                        "FORMAT-OPTIONS encoded for database "
                                + "12345678-1234-5678-9abc-def012345678, creation epoch millis "
                                + "42, and the v1 compatibility vector."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.checkpoint_metadata.v1",
                        "canonical-checkpoint-metadata-v1",
                        256,
                        "bfc0e1362861ff238d7dcdbcb776374e30b64b0d9ad0520faf71b9a78faede1e",
                        "Checkpoint metadata encoded for database "
                                + "3bd39131-3aca-4569-bfe5-cd7d5a1a3421, checkpoint sequence "
                                + "42, creation epoch millis 100, source read-view and manifest "
                                + "generation 7, checkpoint manifest generation 9, three SSTables, "
                                + "1234 SSTable bytes, and a 0..31 compatibility fingerprint."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.rpc_frame.v1",
                        "canonical-request-frame-v1",
                        67,
                        "9145f98a25458e706d8104de1c88718e53bdde68ab955816600acb3312357426",
                        "RPC request frame encoded with a 64-byte header, three-byte payload, "
                                + "stream 1, operation code 42, timeout 5000 millis, and invocation "
                                + "12345678-1234-5678-9abc-def012345678."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.rpc_hello.v1",
                        "canonical-dialer-hello-v1",
                        192,
                        "c36bf47e7ab8c912607ee0520570c19675d5aeeaa1ca33b253c596a53bd41093",
                        "RPC HELLO payload encoded for a dialer with cluster "
                                + "11111111-1111-1111-8111-111111111111, node "
                                + "22222222-2222-2222-8222-222222222222, session "
                                + "33333333-3333-3333-8333-333333333333, nonce 7, "
                                + "1 MiB frame payload, 16 MiB message limit, 1024 streams, "
                                + "64 MiB receive window, 30s keepalive idle, 10s timeout, "
                                + "engine version 1.0.0, and Java 21."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.replicated_log_segment.v1",
                        "canonical-first-segment-header-v1",
                        4_096,
                        "55e230ca96408b8fd12a1b7c6f495387080087d127717ff3fc97d62d87081ed4",
                        "Replicated log first segment header region encoded for cluster "
                                + "11111111-1111-4111-8111-111111111111, node "
                                + "22222222-2222-4222-8222-222222222222, segment 1, first "
                                + "index 1, genesis previous index and term, zero previous entry "
                                + "hash, and creation epoch millis 1234."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.replicated_log_entry.v1",
                        "canonical-command-entry-v1",
                        384,
                        "2b8c4f5d18984aac2f94a8119e5a525d4a1eae2a1965742938d349ba90773b29",
                        "Replicated log command entry record encoded for index 1, term 1, "
                                + "state sequence 1, zero previous entry hash, command UUID "
                                + "derived from command-1-1, and a write command that puts "
                                + "key-1 to value a."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.raft_vote_request.v1",
                        "canonical-request-vote-v1",
                        128,
                        "4b4abc45632bc14b1a4e144c770cf1d7f888cebe2dc92e95d8b010d81f041de3",
                        "Raft REQUEST_VOTE request encoded for term 7, candidate node "
                                + "00000000-0000-0000-0000-000000000001, session "
                                + "00000000-0000-0000-0000-000000000002, last log index 12, "
                                + "last log term 6, last state sequence 20, zero last-entry hash, "
                                + "nonce 99, and configuration version 3."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.raft_vote_response.v1",
                        "canonical-granted-vote-v1",
                        96,
                        "7ed861d37f3eda65c18bfaf348e71853886f8af2795c0a34eed79e5b5eef7e44",
                        "Raft REQUEST_VOTE response encoded as granted for term 7 by node "
                                + "00000000-0000-0000-0000-000000000001 in session "
                                + "00000000-0000-0000-0000-000000000002, nonce 99, last log "
                                + "index 12, last log term 6, and configuration version 3."));
        add(
                formats,
                fixtures,
                new FormatGoldenFixture(
                        "aether.raft_state_slot.v1",
                        "canonical-voted-state-slot-v1",
                        512,
                        "5fca3bc5897136c011b987ee1d5b03ba207ea573eeac94b1c19508321870ee41",
                        "Raft persistent state slot encoded for cluster/session "
                                + "00000000-0000-0000-0000-000000000002, node "
                                + "00000000-0000-0000-0000-000000000001, generation 4, "
                                + "current term 9, voted-for node "
                                + "00000000-0000-0000-0000-000000000001, update reason 4, "
                                + "epoch millis 123, and a 32-byte fingerprint filled with 7."));
        return new FormatGoldenFixtureCatalog(fixtures);
    }

    public Map<String, FormatGoldenFixture> fixtures() {
        return fixtures;
    }

    public FormatGoldenFixture require(String formatId, String fixtureName) {
        FormatGoldenFixture fixture = fixtures.get(key(formatId, fixtureName));
        if (fixture == null)
            throw new IllegalArgumentException(
                    "unknown golden fixture: " + formatId + "/" + fixtureName);
        return fixture;
    }

    private static void add(
            AetherFormatCatalog formats,
            Map<String, FormatGoldenFixture> fixtures,
            FormatGoldenFixture fixture) {
        formats.require(fixture.formatId());
        FormatGoldenFixture previous =
                fixtures.put(key(fixture.formatId(), fixture.fixtureName()), fixture);
        if (previous != null)
            throw new IllegalStateException(
                    "duplicate golden fixture: "
                            + fixture.formatId()
                            + "/"
                            + fixture.fixtureName());
    }

    private static String key(String formatId, String fixtureName) {
        return formatId + "/" + fixtureName;
    }
}
