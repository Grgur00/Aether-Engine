package io.aetherdb.client.api;

public record ClientWriteOperation(Type type, byte[] key, byte[] value) {
    public ClientWriteOperation { if(type==null||key==null||value==null||key.length>65_536||value.length>16*1024*1024||type==Type.DELETE&&value.length!=0) throw new IllegalArgumentException("invalid client operation"); key=key.clone();value=value.clone(); }
    @Override public byte[] key(){return key.clone();} @Override public byte[] value(){return value.clone();}
    public enum Type { PUT, DELETE }
}
