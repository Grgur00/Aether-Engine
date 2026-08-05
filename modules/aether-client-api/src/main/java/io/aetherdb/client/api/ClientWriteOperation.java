package io.aetherdb.client.api;

/** One immutable client write mutation.
 * @param type mutation kind
 * @param key copied key bytes
 * @param value copied value bytes, empty for deletes */
public record ClientWriteOperation(Type type, byte[] key, byte[] value) {
    /** Validates bounds and defensively copies key and value. */
    public ClientWriteOperation { if(type==null||key==null||value==null||key.length>65_536||value.length>16*1024*1024||type==Type.DELETE&&value.length!=0) throw new IllegalArgumentException("invalid client operation"); key=key.clone();value=value.clone(); }
    /** Returns the key.
     * @return defensive key copy */
    @Override public byte[] key(){return key.clone();}
    /** Returns the value.
     * @return defensive value copy */
    @Override public byte[] value(){return value.clone();}
    /** Client mutation kind. */
    public enum Type {
        /** Insert or replace a value. */ PUT,
        /** Remove a key. */ DELETE
    }
}
