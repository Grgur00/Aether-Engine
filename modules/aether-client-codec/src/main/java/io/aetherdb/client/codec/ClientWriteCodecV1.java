package io.aetherdb.client.codec;

import io.aetherdb.client.api.*;
import io.aetherdb.format.checksum.MaskedCrc32c;
import java.nio.*;
import java.security.*;
import java.util.*;

/** Exact v1 codec for bounded client write requests. */
public final class ClientWriteCodecV1 {
    /** Fixed request-header length in bytes. */
    public static final int HEADER_BYTES=128;
    private ClientWriteCodecV1() {}
    /** Encodes a client write request with body hash and header checksum.
     * @param request request to encode
     * @return newly allocated wire bytes */
    public static byte[] encode(ClientWriteRequest request){
        int regionBytes=0; long keys=0,values=0; for(var op:request.operations()){regionBytes=Math.addExact(regionBytes,16+op.key().length+op.value().length);keys+=op.key().length;values+=op.value().length;} if(regionBytes>32*1024*1024) throw new IllegalArgumentException("write body too large");
        byte[] out=new byte[128+regionBytes]; ByteBuffer b=ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN); b.putInt(0x41454357).putShort((short)1).putShort((short)128).putInt(0).putInt(request.operations().size()).putInt(regionBytes).putInt(1).putLong(request.configurationVersion()).putLong(0).putLong(0).putLong(0).putLong(keys).putLong(values); int hashPosition=b.position();b.position(hashPosition+32+20);b.putInt(0);
        b.position(128);int ordinal=0;for(var op:request.operations()){b.put((byte)(op.type()==ClientWriteOperation.Type.PUT?1:2)).put((byte)0).putShort((short)0).putInt(op.key().length).putInt(op.value().length).putInt(ordinal++).put(op.key()).put(op.value());}
        byte[] hash=sha(Arrays.copyOfRange(out,128,out.length));System.arraycopy(hash,0,out,72,32);ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).putInt(124,MaskedCrc32c.masked(out,0,124));return out;
    }
    /** Decodes and validates a client write request.
     * @param in complete wire payload
     * @return decoded request */
    public static ClientWriteRequest decode(byte[] in){
        if(in==null||in.length<128||in.length>32*1024*1024+128||ByteBuffer.wrap(in).order(ByteOrder.BIG_ENDIAN).getInt(124)!=MaskedCrc32c.masked(in,0,124)) throw invalid(); ByteBuffer b=ByteBuffer.wrap(in).order(ByteOrder.BIG_ENDIAN);if(b.getInt()!=0x41454357||b.getShort()!=1||b.getShort()!=128||b.getInt()!=0)throw invalid();int count=b.getInt(),region=b.getInt();if(b.getInt()!=1||region!=in.length-128)throw invalid();long config=b.getLong();if(b.getLong()!=0||b.getLong()!=0||b.getLong()!=0)throw invalid();long keys=b.getLong(),values=b.getLong();byte[] hash=new byte[32];b.get(hash);byte[] reserved=new byte[20];b.get(reserved);if(!Arrays.equals(reserved,new byte[20])||!MessageDigest.isEqual(hash,sha(Arrays.copyOfRange(in,128,in.length))))throw invalid();b.position(128);List<ClientWriteOperation> ops=new ArrayList<>();long actualKeys=0,actualValues=0;for(int i=0;i<count;i++){if(b.remaining()<16)throw invalid();int type=b.get()&255;if(b.get()!=0||b.getShort()!=0)throw invalid();int kl=b.getInt(),vl=b.getInt(),ordinal=b.getInt();if(kl<0||vl<0||ordinal!=i||b.remaining()<kl+vl)throw invalid();byte[] key=new byte[kl],value=new byte[vl];b.get(key).get(value);ops.add(new ClientWriteOperation(type==1?ClientWriteOperation.Type.PUT:type==2?ClientWriteOperation.Type.DELETE:null,key,value));actualKeys+=kl;actualValues+=vl;}if(b.hasRemaining()||keys!=actualKeys||values!=actualValues)throw invalid();return new ClientWriteRequest(config,ops);
    }
    private static byte[] sha(byte[] value){try{return MessageDigest.getInstance("SHA-256").digest(value);}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}} private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid CLIENT_WRITE body");}
}
