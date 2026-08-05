package io.aetherdb.memtable.skiplist;

/** Native MemTable point result distinguishes absence from a tombstone. */
public record MemTableLookupResult(Kind kind, byte[] value) {
    public enum Kind { VALUE, TOMBSTONE, NOT_FOUND }
    public MemTableLookupResult { value = value == null ? null : value.clone(); }
    @Override public byte[] value() { return value == null ? null : value.clone(); }
    public static MemTableLookupResult value(byte[] value) { return new MemTableLookupResult(Kind.VALUE, value); }
    public static MemTableLookupResult tombstone() { return new MemTableLookupResult(Kind.TOMBSTONE, null); }
    public static MemTableLookupResult notFound() { return new MemTableLookupResult(Kind.NOT_FOUND, null); }
}
