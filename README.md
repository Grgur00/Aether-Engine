# Aether Engine

Aether Engine is a modular Java storage-engine project built around LSM-tree, replication, RPC, Raft, and typed application API foundations.

The current release includes a deterministic in-memory engine suitable for development, semantic testing, and learning. Persistent and distributed components are under active development and are not yet exposed as a production database.

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

For a local multi-module consumer, use:

```kotlin
dependencies {
    implementation(project(":modules:aether-api"))
    implementation(project(":modules:aether-codec"))
    implementation(project(":modules:aether-embedded-typed"))
}
```

Artifacts are not currently published to Maven Central.

## Sample application

The typed social-profile sample demonstrates:

- a domain-specific, versioned value codec;
- typed point operations;
- atomic multi-profile batches;
- snapshot isolation;
- ordered scans; and
- explicit absence through `Optional`.

Run it with:

```bash
./gradlew :examples:aether-sample-app:run
```

Expected output:

```text
Ada before campaign: 128450 followers
Ada latest:          130000 followers
Profiles in the social service:
  @ada.codes — Ada Lovelace
  @grace.debugs — Grace Hopper
```

See [the sample README](examples/aether-sample-app/README.md) and its [main class](examples/aether-sample-app/src/main/java/io/aetherdb/examples/social/SocialNetworkApplication.java).

## Module overview

| Area | Modules |
|---|---|
| Public and typed APIs | `aether-api`, `aether-codec`, `aether-embedded-typed` |
| Reference engine | `aether-engine` |
| Storage | `aether-memory`, `aether-memtable`, `aether-wal`, `aether-sstable`, `aether-lsm`, `aether-cache` |
| Networking | `aether-rpc-api`, `aether-rpc-codec`, `aether-rpc-transport` |
| Replication | `aether-replication-api`, `aether-replicated-log`, `aether-state-machine` |
| Consensus | `aether-raft-api`, `aether-raft-core`, `aether-raft-storage` |
| Cluster membership | `aether-cluster-api`, `aether-cluster-codec`, `aether-cluster-core` |
| Tooling | `aether-tools`, `aether-workbench`, testkits and benchmarks |

## Current limitations

- The public embedded entry point is in-memory only.
- Database contents are discarded when the process exits.
- Persistent reopen and recovery are not exposed through the public API.
- TCP/TLS cluster clients and servers are not yet complete.
- Dynamic membership foundations exist, but full runtime orchestration is incomplete.
- API and persistent formats may change before the first stable release.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), and [SECURITY.md](SECURITY.md).

## License

Licensed under the terms in [LICENSE](LICENSE).
