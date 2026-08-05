# Aether Engine

Aether Engine is a byte-oriented storage engine for Java, developed as a small, auditable implementation of log-structured merge-tree (LSM-tree) concepts.

The current public release provides a deterministic in-memory database with point reads, writes, deletes, snapshots, ordered range scans, and atomic write batches. Native-memory, WAL, and SSTable building blocks are under active development and are not yet connected to a durable public database.

> **Project status:** pre-release. Use the in-memory engine for development, education, and semantic testing—not for durable production data.

## Documentation

- [User guide](docs/handbook/User_Guide.md) — installation, quick start, behavior, and troubleshooting
- [API reference](docs/handbook/API_Reference.md) — contracts for database, snapshots, cursors, batches, and lookup results
- [Data Workbench](docs/handbook/Data_Workbench.md) — graphical browsing and editing for an in-memory session
- [Operations and CLI](docs/handbook/Operations_and_CLI.md) — metadata formats, locking, inspection, and limitations
- [Networking and RPC](docs/handbook/Networking_and_RPC.md) — binary framing, limits, and protocol inspector
- [Replication contracts](docs/handbook/Replication.md) — replicated commands, sequence planning, and current limits
- [Social Profile Service sample](examples/aether-sample-app/README.md) — a realistic social-network project using Aether and the live workbench
- [Build and test commands](docs/wiki/Build_and_Test_Commands.md) — contributor workflows
- [Technical specification](docs/Aether_Engine_Master_Technical_Specification.md) — architecture and design requirements

## Requirements

- JDK 21
- The checked-in Gradle wrapper; a global Gradle installation is not required

Aether uses Java 21 preview APIs in its internal native-memory modules. The build configures the required compiler and runtime flags.

## Quick start

```java
import static java.nio.charset.StandardCharsets.UTF_8;

import io.aetherdb.api.AetherDatabase;
import io.aetherdb.api.result.LookupResult;
import io.aetherdb.engine.Aether;

try (AetherDatabase database = Aether.openInMemory()) {
    database.put("greeting".getBytes(UTF_8), "hello".getBytes(UTF_8));

    LookupResult result = database.get("greeting".getBytes(UTF_8));
    if (result.isFound()) {
        System.out.println(new String(result.value(), UTF_8));
    }
}
```

The artifacts are not currently published to a package repository. From this repository, depend on the API and engine projects:

```kotlin
dependencies {
    implementation(project(":modules:aether-api"))
    implementation(project(":modules:aether-engine"))
}
```

## Verify the repository

```shell
./gradlew check
```

## Graphical data workbench

Launch the desktop editor with:

```shell
./gradlew :modules:aether-workbench:run
```

The workbench groups slash-delimited keys by their shared parent path and lets you add, edit, rename, sort, refresh, and delete UTF-8 text records. It uses the current in-memory engine, so closing the window discards the session.

Its **RPC Frame Inspector** tab can encode and decode exact v1 request/response frames, verify checksums, and demonstrate corruption rejection.

Applications can also open the workbench on their own live database:

```java
AetherWorkbench.open(database);
```

The **Replication Inspector** tab encodes and validates deterministic replicated write commands and leader-assigned state sequences.

## Sample project

Run the Social Profile Service example:

```shell
./gradlew :examples:aether-sample-app:run
```

It stores realistic user profiles through a domain repository backed by Aether. The application demonstrates atomic profile updates, historical snapshot reads, ordered profile discovery, and live inspection or editing in the workbench.

## Feature status

| Capability | Status |
|---|---|
| Point reads, puts, and deletes | Available in memory |
| Atomic ordered write batches | Available in memory |
| Snapshots and snapshot scans | Available in memory |
| Unsigned byte-ordered range scans | Available in memory |
| Native-memory allocator and memtable components | Internal / experimental |
| WAL and SSTable format components | Internal / experimental |
| Read-view, iterator, and block-cache components | Internal / experimental |
| Compaction policy, reclamation, and backpressure components | Internal / experimental |
| Snapshot IDs, limits, and bounded one-shot batches | Available in memory |
| Identity/options codecs, locking, and metadata CLI | Internal / experimental |
| RPC v1 framing, HELLO, fragmentation, and flow accounting | Internal / experimental |
| Replicated-log identity, command codec, and sequence planning | Internal / experimental |
| Raft vote codecs, persistent-state slots, quorum and commit foundations | Internal / experimental |
| Client command opcodes, statuses, deduplicated retry class, and write codec | Internal / experimental |
| TCP/TLS RPC server and distributed database | Not available |
| Reopening persisted data | Not available |
| Compaction and production durability | Not available |

## Operational CLI

```shell
./gradlew :modules:aether-tools:installDist
./modules/aether-tools/build/install/aether-tools/bin/aether-tools version
```

The CLI validates and inspects Chapter 16 `DB-IDENTITY` and `FORMAT-OPTIONS` metadata. The public engine still cannot create or reopen a durable database directory.

## License

No license has been declared yet. Until one is added, standard copyright restrictions apply.
