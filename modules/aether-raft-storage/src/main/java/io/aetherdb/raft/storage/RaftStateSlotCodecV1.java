package io.aetherdb.raft.storage;

import io.aetherdb.format.checksum.MaskedCrc32c;
import java.nio.*;
import java.security.*;
import java.util.*;

public final class RaftStateSlotCodecV1 {
    public static final int SLOT_BYTES=512;
    private RaftStateSlotCodecV1() {}
    public static byte[] encode(UUID cluster,UUID node,RaftPersistentState state,int reason,byte[] fingerprint,long epochMillis){
        if(fingerprint.length!=32||reason<1||reason>5) throw new IllegalArgumentException(); byte[] out=new byte[512]; ByteBuffer b=ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        b.put("AETHRFS1".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putShort((short)1).putShort((short)512).putInt(0); put(b,cluster);put(b,node);b.putLong(state.generation()).putLong(state.currentTerm());
        b.put((byte)(state.votedFor().isPresent()?1:0)).position(72); put(b,state.votedFor().orElse(new UUID(0,0))); b.putLong(epochMillis).putInt(reason).putInt(0).put(fingerprint); b.put(hash(cluster,node,state,fingerprint)); b.position(508).putInt(MaskedCrc32c.masked(out,0,508)); return out;
    }
    public static RaftPersistentState decode(byte[] in,UUID cluster,UUID node,byte[] fingerprint){
        if(in.length!=512||ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN).getInt(508)!=MaskedCrc32c.masked(in,0,508)) throw invalid(); ByteBuffer b=ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN); byte[] magic=new byte[8];b.get(magic);
        if(!Arrays.equals(magic,"AETHRFS1".getBytes(java.nio.charset.StandardCharsets.US_ASCII))||b.getShort()!=1||b.getShort()!=512||b.getInt()!=0||!get(b).equals(cluster)||!get(b).equals(node)) throw invalid();
        long generation=b.getLong(),term=b.getLong(); int present=b.get()&255;b.position(72);UUID vote=get(b);b.getLong();int reason=b.getInt();b.getInt();byte[] fp=new byte[32];b.get(fp);byte[] storedHash=new byte[32];b.get(storedHash);
        RaftPersistentState state=new RaftPersistentState(generation,term,present==1?Optional.of(vote):Optional.empty()); if(present>1||reason<1||reason>5||!Arrays.equals(fp,fingerprint)||!MessageDigest.isEqual(storedHash,hash(cluster,node,state,fingerprint))) throw invalid(); return state;
    }
    private static byte[] hash(UUID c,UUID n,RaftPersistentState s,byte[] fp){ try{MessageDigest d=MessageDigest.getInstance("SHA-256");d.update("AETHER-RAFT-STATE-V1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));ByteBuffer b=ByteBuffer.allocate(57).order(ByteOrder.LITTLE_ENDIAN);put(b,c);put(b,n);b.putLong(s.generation()).putLong(s.currentTerm()).put((byte)(s.votedFor().isPresent()?1:0));d.update(b.array());ByteBuffer v=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);put(v,s.votedFor().orElse(new UUID(0,0)));d.update(v.array());d.update(fp);return d.digest();}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);} }
    private static void put(ByteBuffer b,UUID u){b.putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits());} private static UUID get(ByteBuffer b){return new UUID(b.getLong(),b.getLong());} private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid RAFT-STATE slot");}
}
