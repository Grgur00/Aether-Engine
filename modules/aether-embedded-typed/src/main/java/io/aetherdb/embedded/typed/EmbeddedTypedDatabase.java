package io.aetherdb.embedded.typed;

import io.aetherdb.api.*;
import io.aetherdb.api.result.LookupResult;
import io.aetherdb.api.typed.*;
import io.aetherdb.codec.*;

import java.util.*;

/**
 * Adapts the byte-oriented embedded database to typed collections and manages the collection
 * definitions used by those adapters.
 */
final class EmbeddedTypedDatabase implements TypedAetherDatabase {
    private final AetherDatabase raw;
    private final boolean owns;
    private final Map<CollectionId, CollectionDefinition<?, ?>> definitions = new HashMap<>();
    private final Map<CollectionId, Integer> minimumWriterVersions = new HashMap<>();
    private boolean closed;

    EmbeddedTypedDatabase(AetherDatabase raw, boolean owns) {
        this.raw = Objects.requireNonNull(raw);
        this.owns = owns;
    }

    public synchronized <K, V> TypedAetherCollection<K, V> collection(
            CollectionDefinition<K, V> d) {
        ensureOpen();
        register(d);
        return new Handle<>(this, d, null, false);
    }

    public synchronized <K, V> TypedAetherCollection<K, V> defineCollection(
            CollectionId id, String name, Class<K> keyType, Class<V> valueType) {
        ensureOpen();
        CollectionDefinition<K, V> definition =
                new CollectionDefinition<>(
                        id,
                        name,
                        BuiltInKeyCodecs.forType(keyType),
                        io.aetherdb.codec.generated.GeneratedCodecs.forRecord(valueType),
                        Set.of(
                                CollectionCapability.POINT_READ,
                                CollectionCapability.POINT_WRITE,
                                CollectionCapability.RANGE_SCAN,
                                CollectionCapability.SNAPSHOT_READ));
        return collection(definition);
    }

    public synchronized TypedWriteBatch batch() {
        ensureOpen();
        return new Batch(this);
    }

    public synchronized TypedWriteResult write(TypedWriteBatch candidate) {
        ensureOpen();
        if (!(candidate instanceof Batch b) || b.owner != this)
            throw new IllegalArgumentException("batch belongs to another database");
        return b.submit();
    }

    public synchronized TypedAetherSnapshot snapshot() {
        ensureOpen();
        return new Snap(this, raw.newSnapshot());
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized void close() {
        if (!closed && owns) raw.close();
        closed = true;
    }

    private void register(CollectionDefinition<?, ?> d) {
        CollectionDefinition<?, ?> prior = definitions.get(d.id());
        if (prior != null) {
            if (!prior.keyCodec().codecId().equals(d.keyCodec().codecId())
                    || prior.keyCodec().encodingVersion() != d.keyCodec().encodingVersion()
                    || !prior.valueCodec().schemaId().equals(d.valueCodec().schemaId())
                    || !Arrays.equals(prior.keyCodec().fingerprint(), d.keyCodec().fingerprint())
                    || prior.valueCodec().currentSchemaVersion()
                                    == d.valueCodec().currentSchemaVersion()
                            && !Arrays.equals(
                                    prior.valueCodec().fingerprint(), d.valueCodec().fingerprint()))
                throw new IllegalArgumentException("COLLECTION_SCHEMA_CONFLICT for " + d.id());
            if (prior.valueCodec().currentSchemaVersion() >= d.valueCodec().currentSchemaVersion())
                return;
        }
        var descriptor =
                io.aetherdb.codec.generated.GeneratedCodecs.descriptor(
                        d.valueCodec().schemaId(), d.valueCodec().currentSchemaVersion());
        var metadata = CollectionMetadata.from(d, descriptor);
        LookupResult stored = raw.get(metadata.key());
        if (stored.isFound()) {
            CollectionMetadata existing =
                    CollectionMetadata.decode(metadata.key(), stored.value()).orElseThrow();
            if (!existing.sameFamilyAs(metadata))
                throw new IllegalArgumentException("COLLECTION_SCHEMA_CONFLICT for " + d.id());
            if (existing.schemaVersion() == metadata.schemaVersion()) {
                if (!Arrays.equals(existing.schemaFingerprint(), metadata.schemaFingerprint()))
                    throw new IllegalArgumentException("SCHEMA_DESCRIPTOR_CONFLICT for " + d.id());
                minimumWriterVersions.put(d.id(), existing.schemaVersion());
            } else if (existing.schemaVersion() < metadata.schemaVersion()) {
                existing.requireCompatibleUpgradeTo(metadata);
                raw.put(metadata.key(), metadata.encode());
                minimumWriterVersions.put(d.id(), metadata.schemaVersion());
            } else minimumWriterVersions.put(d.id(), existing.schemaVersion());
        } else {
            raw.put(metadata.key(), metadata.encode());
            minimumWriterVersions.put(d.id(), metadata.schemaVersion());
        }
        definitions.put(d.id(), d);
    }

    private void ensureWriterAllowed(CollectionDefinition<?, ?> d) {
        int minimum =
                minimumWriterVersions.getOrDefault(d.id(), d.valueCodec().currentSchemaVersion());
        if (d.valueCodec().currentSchemaVersion() < minimum)
            throw new IllegalStateException(
                    "OLDER_WRITER_REJECTED: collection "
                            + d.id()
                            + " requires writer schema version "
                            + minimum);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("typed database is closed");
    }

    private static final class Handle<K, V> implements TypedAetherCollection<K, V> {
        private final EmbeddedTypedDatabase owner;
        private final CollectionDefinition<K, V> d;
        private final Snapshot snapshot;
        private final boolean readOnly;

        Handle(EmbeddedTypedDatabase o, CollectionDefinition<K, V> d, Snapshot s, boolean ro) {
            owner = o;
            this.d = d;
            snapshot = s;
            readOnly = ro;
        }

        public CollectionDefinition<K, V> definition() {
            return d;
        }

        public ReadResult<V> get(K key) {
            owner.ensureOpen();
            LookupResult r =
                    snapshot == null
                            ? owner.raw.get(TypedKeyEnvelope.encode(d, key))
                            : owner.raw.get(TypedKeyEnvelope.encode(d, key), snapshot);
            return r.isFound()
                    ? new ReadResult.Found<>(TypedValueEnvelope.decode(d.valueCodec(), r.value()))
                    : new ReadResult.NotFound<>();
        }

        public TypedWriteResult put(K key, V value) {
            writable();
            Batch b = new Batch(owner);
            b.put(this, key, value);
            return b.submit();
        }

        public TypedWriteResult delete(K key) {
            writable();
            Batch b = new Batch(owner);
            b.delete(this, key);
            return b.submit();
        }

        public List<TypedKeyValue<K, V>> scanAll() {
            owner.ensureOpen();
            if (!d.capabilities().contains(CollectionCapability.RANGE_SCAN))
                throw new UnsupportedOperationException("collection does not support range scans");
            List<TypedKeyValue<K, V>> values = new ArrayList<>();
            try (AetherCursor cursor =
                    snapshot == null
                            ? owner.raw.scan(
                                    TypedKeyEnvelope.prefix(d), TypedKeyEnvelope.prefixEnd(d))
                            : owner.raw.scan(
                                    TypedKeyEnvelope.prefix(d),
                                    TypedKeyEnvelope.prefixEnd(d),
                                    snapshot)) {
                while (cursor.next())
                    values.add(
                            new TypedKeyValue<>(
                                    TypedKeyEnvelope.decode(d, cursor.key()),
                                    TypedValueEnvelope.decode(d.valueCodec(), cursor.value())));
            }
            return List.copyOf(values);
        }

        private void writable() {
            owner.ensureOpen();
            if (readOnly)
                throw new UnsupportedOperationException("snapshot collection is read-only");
            owner.ensureWriterAllowed(d);
        }
    }

    private static final class Batch implements TypedWriteBatch {
        private final EmbeddedTypedDatabase owner;
        private final WriteBatch raw = new WriteBatch();
        private boolean submitted;

        Batch(EmbeddedTypedDatabase o) {
            owner = o;
        }

        public <K, V> TypedWriteBatch put(TypedAetherCollection<K, V> c, K k, V v) {
            Handle<K, V> h = handle(c);
            owner.ensureWriterAllowed(h.d);
            raw.put(
                    TypedKeyEnvelope.encode(h.d, k),
                    TypedValueEnvelope.encode(h.d.valueCodec(), v));
            return this;
        }

        public <K, V> TypedWriteBatch delete(TypedAetherCollection<K, V> c, K k) {
            Handle<K, V> h = handle(c);
            owner.ensureWriterAllowed(h.d);
            raw.delete(TypedKeyEnvelope.encode(h.d, k));
            return this;
        }

        public int operationCount() {
            return raw.operationCount();
        }

        @SuppressWarnings("unchecked")
        private <K, V> Handle<K, V> handle(TypedAetherCollection<K, V> c) {
            if (submitted) throw new IllegalStateException("batch already submitted");
            if (!(c instanceof Handle<?, ?> h) || h.owner != owner || h.readOnly)
                throw new IllegalArgumentException(
                        "collection does not belong to writable database");
            return (Handle<K, V>) h;
        }

        TypedWriteResult submit() {
            if (submitted) throw new IllegalStateException("batch already submitted");
            submitted = true;
            UUID command = UUID.randomUUID();
            if (raw.operationCount() == 0)
                return new TypedWriteResult.Rejected(command, "EMPTY_BATCH", false);
            WriteResult result = owner.raw.write(raw, WriteOptions.defaults());
            return new TypedWriteResult.Applied(
                    command,
                    result.operationCount(),
                    result.firstSequence(),
                    result.lastSequence());
        }
    }

    private static final class Snap implements TypedAetherSnapshot {
        private final EmbeddedTypedDatabase owner;
        private final Snapshot raw;
        private boolean closed;

        Snap(EmbeddedTypedDatabase o, Snapshot s) {
            owner = o;
            raw = s;
        }

        public <K, V> TypedAetherCollection<K, V> collection(CollectionDefinition<K, V> d) {
            if (closed) throw new IllegalStateException("snapshot closed");
            owner.register(d);
            return new Handle<>(owner, d, raw, true);
        }

        public boolean isClosed() {
            return closed;
        }

        public void close() {
            if (!closed) raw.close();
            closed = true;
        }
    }
}
