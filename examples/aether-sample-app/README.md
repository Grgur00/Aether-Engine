# Aether Typed Social Profile Sample

This sample demonstrates the developer-facing typed Java API. Application code uses `String` keys and `UserProfile` values; Aether’s physical key and value envelopes remain internal.

Run it from the repository root:

```shell
./gradlew :examples:aether-sample-app:run
```

The application demonstrates:

- an explicitly versioned collection UUID and schema UUID;
- an ordered UTF-8 key codec and application-owned `UserProfile` value codec;
- typed point reads and structured write outcomes;
- one atomic batch containing multiple typed profile writes;
- typed snapshot reads that remain stable across later writes;
- ordered collection scans without physical key handling; and
- absence represented by `Optional`, never `null`.

The value codec is the only application component that handles serialization. Its stable schema identity, version, size bound, and fingerprint make encoding changes explicit.

The sample uses the in-memory embedded engine, so stopping the application discards its profiles.
