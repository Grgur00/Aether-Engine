package io.aetherdb.raft.storage;

import io.aetherdb.format.checksum.MaskedCrc32c;
import io.aetherdb.raft.api.*;
import java.nio.*;
import java.util.UUID;

public final class VoteCodecV1 {
    public static final int REQUEST_BYTES = 128, RESPONSE_BYTES = 96;
    private VoteCodecV1() {}
    public static byte[] encodeRequest(VoteRequest value) {
        byte[] out = new byte[REQUEST_BYTES]; ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        b.putInt(0x41455652).putShort((short)1).putShort((short)128).put((byte)value.kind().code).put((byte)0).putShort((short)0);
        b.putLong(value.term()); putUuid(b, value.candidateId()); putUuid(b, value.sessionId()); b.putLong(value.lastLogIndex()).putLong(value.lastLogTerm()).putLong(value.lastStateSequence());
        b.put(value.lastEntryHash()).putLong(value.nonce()).putLong(value.configurationVersion()); b.putInt(MaskedCrc32c.masked(out, 0, 124)); return out;
    }
    public static VoteRequest decodeRequest(byte[] in) {
        require(in, REQUEST_BYTES, 0x41455652, 124); ByteBuffer b = ByteBuffer.wrap(in).order(ByteOrder.BIG_ENDIAN); b.position(4);
        if (b.getShort()!=1 || b.getShort()!=128) throw invalid(); int kind=b.get()&255; if(b.get()!=0||b.getShort()!=0) throw invalid();
        long term=b.getLong(); UUID node=getUuid(b), session=getUuid(b); long index=b.getLong(), logTerm=b.getLong(), sequence=b.getLong(); byte[] hash=new byte[32]; b.get(hash);
        return new VoteRequest(kind==1?VoteKind.PRE_VOTE:kind==2?VoteKind.REQUEST_VOTE:null, term,node,session,index,logTerm,sequence,hash,b.getLong(),b.getLong());
    }
    public static byte[] encodeResponse(VoteResponse value) {
        byte[] out=new byte[RESPONSE_BYTES]; ByteBuffer b=ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        b.putInt(0x41455650).putShort((short)1).putShort((short)96).put((byte)value.kind().code).put((byte)(value.granted()?1:0)).putShort((short)value.reason().ordinal()).putLong(value.term());
        putUuid(b,value.responderId()); putUuid(b,value.sessionId()); b.putLong(value.nonce()).putLong(value.lastLogIndex()).putLong(value.lastLogTerm()).putLong(value.configurationVersion()).putLong(0); b.putInt(MaskedCrc32c.masked(out,0,92)); return out;
    }
    public static VoteResponse decodeResponse(byte[] in) {
        require(in,RESPONSE_BYTES,0x41455650,92); ByteBuffer b=ByteBuffer.wrap(in).order(ByteOrder.BIG_ENDIAN); b.position(4);
        if(b.getShort()!=1||b.getShort()!=96) throw invalid(); int kind=b.get()&255, granted=b.get()&255, reason=b.getShort()&0xffff; long term=b.getLong(); UUID node=getUuid(b),session=getUuid(b);
        long nonce=b.getLong(),index=b.getLong(),logTerm=b.getLong(),config=b.getLong(); if(b.getLong()!=0||granted>1||reason>=VoteReason.values().length) throw invalid();
        return new VoteResponse(kind==1?VoteKind.PRE_VOTE:kind==2?VoteKind.REQUEST_VOTE:null,granted==1,VoteReason.values()[reason],term,node,session,nonce,index,logTerm,config);
    }
    private static void require(byte[] in,int size,int magic,int crcOffset) { if(in==null||in.length!=size) throw invalid(); ByteBuffer b=ByteBuffer.wrap(in).order(ByteOrder.BIG_ENDIAN); if(b.getInt()!=magic||b.getInt(crcOffset)!=MaskedCrc32c.masked(in,0,crcOffset)) throw invalid(); }
    private static void putUuid(ByteBuffer b,UUID id){ b.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()); }
    private static UUID getUuid(ByteBuffer b){ return new UUID(b.getLong(),b.getLong()); }
    private static IllegalArgumentException invalid(){ return new IllegalArgumentException("invalid Raft vote body"); }
}
