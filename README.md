# Aether Engine

Aether Engine is a modular Java storage-engine project built around LSM-tree, replication, RPC, Raft, and typed application API foundations.

The current release includes a deterministic in-memory engine and a first local persistent embedded path. The persistent path provides WAL-backed close/reopen durability and atomic checkpoint recovery, but remains pre-production.

> **Status:** pre-release (`0.1.0-SNAPSHOT`). Do not use it for durable production data yet.

## Highlights

- Typed collections with stable UUID identities
- Versioned key and value codecs with compatibility fingerprints
- Point reads, writes, and deletes
- Atomic cross-collection write batches
- Stable MVCC snapshots
- Ordered typed collection scans
- Structured applied, rejected, and indeterminate write outcomes
- Native-memory and skip-list MemTable foundations
- WAL, SSTable, manifest, cache, compaction, and read-path components
- Binary RPC and replicated-command codecs
- Raft persistence, quorum, and joint-consensus configuration foundations
- Cluster identity and stable/joint membership codecs

## Requirements

- JDK 21 or newer
- The checked-in Gradle wrapper

A global Gradle installation is not required.

## Build and test

Clone the repository and run the complete test suite:

```bash
git clone https://github.com/Grgur00/Aether-Engine.git
cd Aether-Engine
./gradlew test
```

Run all verification tasks with:

```bash
./gradlew check
```

## Runtime latency metrics

The optional metered database decorator reports rolling p50, p95, and p99
latency together with cumulative throughput, average/min/max latency, operation
counts, failures, and error rate. It has no external monitoring dependency and
uses a bounded 16,384-sample reservoir per operation.

```java
import io.aetherdb.engine.Aether;
import io.aetherdb.engine.DatabaseOperation;

try (var database = Aether.openWithMetrics(Path.of("./data/metrics-demo"))) {
    for (int i = 0; i < 10_000; i++) {
        byte[] key = ("key-" + i).getBytes(StandardCharsets.UTF_8);
        database.put(key, new byte[] {1});
        database.get(key);
    }

    var reads = database.metrics().operation(DatabaseOperation.GET);
    System.out.printf(
            "GET throughput=%.0f ops/s p50=%.3f ms p95=%.3f ms p99=%.3f ms errors=%d%n",
            reads.operationsPerSecond(),
            reads.p50LatencyNanos() / 1_000_000.0,
            reads.p95LatencyNanos() / 1_000_000.0,
            reads.p99LatencyMillis(),
            reads.errors());
}
```

Use `Aether.openInMemoryWithMetrics()` for ephemeral workloads or
`Aether.instrument(existingDatabase)` to instrument an existing implementation.
Call `resetMetrics()` before a warm benchmark measurement interval.

## Typed API quick start

Define a typed collection with stable collection and schema identities:

```java
import io.aetherdb.api.typed.CollectionDefinition;
import io.aetherdb.api.typed.CollectionId;
import io.aetherdb.codec.BuiltInKeyCodecs;
import io.aetherdb.codec.BuiltInValueCodecs;
import io.aetherdb.embedded.typed.AetherEmbedded;
import java.util.UUID;

var greetings = CollectionDefinition.of(
        CollectionId.of("c64ef96c-4ef4-40ae-aef8-f30c555665c2"),
        "greetings",
        BuiltInKeyCodecs.utf8String(),
        BuiltInValueCodecs.utf8String(
                UUID.fromString("4a191d19-71fe-43e8-b941-4ce11b44961e")));

try (var database = AetherEmbedded.openInMemory()) {
    var collection = database.collection(greetings);

    collection.put("en", "Hello, Aether!");
    String message = collection.get("en").requireValue();
    System.out.println(message);
}
```

Use a path to retain the same typed data after close and reopen:

```java
import java.nio.file.Path;

Path directory = Path.of("./data/aether");

try (var database = AetherEmbedded.open(directory)) {
    database.collection(greetings).put("en", "Persistent hello");
}

try (var database = AetherEmbedded.open(directory)) {
    System.out.println(database.collection(greetings).get("en").requireValue());
}
```

`openInMemory()` discards data on close. `open(path)` creates or reopens a local database, holds an operating-system-backed exclusive writer lock, and rejects a second concurrent writer. The default `GROUP_SYNC` mode forces the WAL before publishing a successful write.

For a local multi-module consumer, use:

```kotlin
dependencies {
    implementation(project(":modules:aether-api"))
    implementation(project(":modules:aether-codec"))
    implementation(project(":modules:aether-embedded-typed"))
    annotationProcessor(project(":modules:aether-codec-processor"))
}
```

Artifacts are not currently published to Maven Central.

### Generated record codecs

Custom Java records use compile-time generated codecs rather than handwritten serialization:

```java
@AetherRecord(version = 1)
public record LedgerEntry(
        UUID accountId,
        long amountMinor,
        @AetherMaxLength(3) String currency,
        Instant bookedAt) {}
```

Open it by Java type:

```java
var entries = database.defineCollection(
        CollectionId.of("74ee12fd-6544-44fd-b858-2e51c4101066"),
        "ledger-entries",
        UUID.class,
        LedgerEntry.class);
```

Initialize and explicitly accept its durable identity once:

```bash
./gradlew aetherSchemaInit
./gradlew aetherSchemaAccept
```

For later changes, increment the record version and run:

```bash
./gradlew aetherSchemaUpdate
./gradlew aetherSchemaAccept
```

The processor validates schema identities, lock-managed stable field IDs, supported types, and variable-length bounds. It generates a deterministic AER1 codec, canonical descriptor, SHA-256 fingerprint, and runtime provider registration.

For quick applications, identities may instead come from a committed Chapter 23C schema lock:

```java
@AetherRecord(version = 1)
public record Todo(String id, String title, boolean completed) {}
```

The authoritative project-level `aether-schemas/index.json` and per-schema JSON lock provide the UUID, field IDs, bounds, retired identities, and descriptor fingerprint. Proposals are written below `build/aether-schema/proposal`; only `aetherSchemaAccept` changes committed locks. Normal compilation and `./gradlew aetherSchemaCheck` are verification-only.

## Sample application

The typed social-network sample demonstrates:

- profile and post CRUD;
- three related collections for profiles, posts, and follows;
- foreign-key and uniqueness validation;
- atomic relationship and follower-count updates;
- one-to-many queries and a joined social feed; and
- compile-time generated, versioned value codecs.

Run it with:

```bash
./gradlew :examples:aether-sample-app:run

# Persistent sample storage
./gradlew :examples:aether-sample-app:run --args="./data/social-network"
```

Expected output:

```text
Profiles (READ after CREATE/UPDATE):
  @ada.codes — Ada Lovelace (1 relational follower)
  @grace.debugs — Grace Hopper (0 relational follower)

Ada's posts (profile -> posts relationship):
  The Analytical Engine weaves algebraic patterns.
  Poetical science meets machinery.

Grace's joined feed (follows -> profiles -> posts):
  @ada.codes: Poetical science meets machinery.
  @ada.codes: The Analytical Engine weaves algebraic patterns.

Deleted draft exists: false
```

See [the sample README](examples/aether-sample-app/README.md) and its [main class](examples/aether-sample-app/src/main/java/io/aetherdb/examples/social/SocialNetworkApplication.java).

### Persistent notes desktop demo

The persistent notes sample opens a small desktop window containing seeded notes, a text field, and an **Add and persist** button. Notes added through the UI are stored by Aether Engine and appear again after you close and restart the application.

Run it from the repository root:

```bash
./gradlew :examples:aether-persistent-notes:run
```

By default, its database is stored in `./examples/aether-persistent-notes/data/aether-notes`. Pass a different directory when needed:

```bash
./gradlew :examples:aether-persistent-notes:run --args="./data/my-notes"
```

See [the persistent notes README](examples/aether-persistent-notes/README.md) and its [main class](examples/aether-persistent-notes/src/main/java/io/aetherdb/examples/notes/PersistentNotesApplication.java).

### Open a database in Aether Workbench

Close any application currently using the database, then pass its directory to the Workbench:

```bash
./gradlew :modules:aether-workbench:run \
  --args="./examples/aether-persistent-notes/data/aether-notes"
```

The persistent view recognizes typed record envelopes and displays text payloads, UUID keys, and collection IDs without requiring the original application to be running. **Add entry** and **Edit selected** use registered collection definitions and codecs; the entry dialog identifies the target collection and exposes its typed key and value. Unknown typed collections remain read-only until their definitions and codecs are registered. Aether opens one database directory at a time, and its exclusive lock prevents the Workbench and an application from opening the same database simultaneously.

## Module overview

| Area | Modules |
|---|---|
| Public and typed APIs | `aether-api`, `aether-codec`, `aether-codec-annotations`, `aether-codec-processor`, `aether-embedded-typed` |
| Reference engine | `aether-engine` |
| Storage | `aether-memory`, `aether-memtable`, `aether-wal`, `aether-sstable`, `aether-lsm`, `aether-cache` |
| Networking | `aether-rpc-api`, `aether-rpc-codec`, `aether-rpc-transport` |
| Replication | `aether-replication-api`, `aether-replicated-log`, `aether-state-machine` |
| Consensus | `aether-raft-api`, `aether-raft-core`, `aether-raft-storage` |
| Cluster membership | `aether-cluster-api`, `aether-cluster-codec`, `aether-cluster-core` |
| Tooling | `aether-tools`, `aether-workbench`, testkits and benchmarks |

## Current limitations

- The persistent path uses one WAL segment plus atomic checkpoint/manifest publication; WAL rotation and reclamation are not yet integrated.
- Checkpoints are published during graceful close; size-triggered background MemTable flush is not yet integrated.
- Leveled compaction and obsolete checkpoint reclamation are not yet integrated.
- TCP/TLS cluster clients and servers are not yet complete.
- Dynamic membership foundations exist, but full runtime orchestration is incomplete.
- API and persistent formats may change before the first stable release.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), and [SECURITY.md](SECURITY.md).

## License

Licensed under the terms in [LICENSE](LICENSE).
