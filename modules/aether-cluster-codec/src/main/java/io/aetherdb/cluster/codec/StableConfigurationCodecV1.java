package io.aetherdb.cluster.codec;

import io.aetherdb.cluster.api.*;
import io.aetherdb.format.checksum.MaskedCrc32c;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class StableConfigurationCodecV1 {
    public static final int HEADER_BYTES=160, MEMBER_HEADER_BYTES=96, ENDPOINT_HEADER_BYTES=24;
    private StableConfigurationCodecV1(){}
    public static byte[] encode(StableConfiguration value){
        int memberBytes=value.members().stream().mapToInt(StableConfigurationCodecV1::memberLength).sum();
        byte[] out=new byte[Math.addExact(HEADER_BYTES,memberBytes)];ByteBuffer b=le(out);
        b.put("AECF".getBytes(StandardCharsets.US_ASCII)).putShort((short)1).putShort((short)HEADER_BYTES).put((byte)1).put((byte)0).putShort((short)0).putInt(out.length);
        IdentityCodecV1.putUuid(b,value.clusterId());b.putLong(value.stateVersion());IdentityCodecV1.putUuid(b,value.configurationId());b.put(value.previousHash());
        int voters=value.voters().size();b.putInt(voters).putInt(value.members().size()-voters).putInt(value.members().size()).putInt(memberBytes).putLong(value.creationEpochMillis());b.position(HEADER_BYTES);
        for(ClusterMember member:value.members())putMember(b,member);
        byte[] calculated=hash(out,112,144,156,160);byte[] supplied=value.hash();if(!allZero(supplied)&&!MessageDigest.isEqual(supplied,calculated))throw new IllegalArgumentException("configuration hash mismatch");
        b.position(112).put(calculated);le(out).putInt(156,MaskedCrc32c.masked(out,0,156));return out;
    }
    public static StableConfigurationV1 decode(byte[] in){
        if(in==null||in.length<HEADER_BYTES)throw invalid();ByteBuffer b=le(in);byte[] magic=new byte[4];b.get(magic);if(!Arrays.equals(magic,"AECF".getBytes(StandardCharsets.US_ASCII))||b.getShort()!=1||b.getShort()!=HEADER_BYTES||b.get()!=1||b.get()!=0||b.getShort()!=0||b.getInt()!=in.length)throw invalid();
        UUID cluster=IdentityCodecV1.getUuid(b);long version=b.getLong();UUID id=IdentityCodecV1.getUuid(b);byte[] previous=IdentityCodecV1.get(b,32);int voters=b.getInt(),staged=b.getInt(),count=b.getInt(),memberBytes=b.getInt();long created=b.getLong();byte[] stored=IdentityCodecV1.get(b,32);IdentityCodecV1.zero(in,144,156);b.position(156);if(b.getInt()!=MaskedCrc32c.masked(in,0,156)||memberBytes!=in.length-HEADER_BYTES||count!=voters+staged)throw invalid();
        if(!MessageDigest.isEqual(stored,hash(in,112,144,156,160)))throw invalid();b.position(HEADER_BYTES);List<ClusterMember> members=new ArrayList<>();for(int i=0;i<count;i++)members.add(getMember(b));if(b.hasRemaining())throw invalid();long actualVoters=members.stream().filter(m->m.role()==MemberRole.VOTER).count();if(actualVoters!=voters)throw invalid();return new StableConfigurationV1(cluster,version,id,previous,members,created,stored);
    }
    private static void putMember(ByteBuffer b,ClusterMember m){int start=b.position(),length=memberLength(m);IdentityCodecV1.putUuid(b,m.nodeId());b.put((byte)m.role().code()).put((byte)m.flags()).putShort((short)m.endpoints().size()).putInt(length).putLong(m.generation()).putLong(m.addedAtIndex()).putLong(m.addedAtTerm());byte[] name=m.name().getBytes(StandardCharsets.UTF_8);b.putLong(name.length).put(m.identityHash()).putLong(0).put(name);pad8(b);for(var e:m.endpoints())putEndpoint(b,e);if(b.position()-start!=length)throw new IllegalStateException();}
    private static ClusterMember getMember(ByteBuffer b){int start=b.position();UUID id=IdentityCodecV1.getUuid(b);MemberRole role=MemberRole.fromCode(b.get()&255);int flags=b.get()&255,count=b.getShort()&65535,length=b.getInt();long gen=b.getLong(),index=b.getLong(),term=b.getLong(),nameLength=b.getLong();byte[] identity=IdentityCodecV1.get(b,32);if(b.getLong()!=0||nameLength<0||nameLength>128||length<MEMBER_HEADER_BYTES||length>b.remaining()+MEMBER_HEADER_BYTES)throw invalid();byte[] name=IdentityCodecV1.get(b,(int)nameLength);skipZeroPad(b);List<ClusterEndpoint>endpoints=new ArrayList<>();for(int i=0;i<count;i++)endpoints.add(getEndpoint(b));if(b.position()-start!=length)throw invalid();return new ClusterMember(id,role,flags,gen,index,term,new String(name,StandardCharsets.UTF_8),identity,endpoints);}
    private static void putEndpoint(ByteBuffer b,ClusterEndpoint e){int len=aligned(ENDPOINT_HEADER_BYTES+e.address().length);b.put((byte)e.scheme().code()).put((byte)e.addressType().code()).putShort((short)e.flags()).putShort((short)e.port()).putShort((short)e.priority()).putInt(e.address().length).putInt(len).putLong(0).put(e.address());pad8(b);}
    private static ClusterEndpoint getEndpoint(ByteBuffer b){int start=b.position(),scheme=b.get()&255,type=b.get()&255,flags=b.getShort()&65535,port=b.getShort()&65535,priority=b.getShort()&65535,addressLength=b.getInt(),length=b.getInt();if(b.getLong()!=0||length!=aligned(ENDPOINT_HEADER_BYTES+addressLength)||addressLength<1||length>b.remaining()+ENDPOINT_HEADER_BYTES)throw invalid();byte[] address=IdentityCodecV1.get(b,addressLength);while(b.position()-start<length)if(b.get()!=0)throw invalid();return new ClusterEndpoint(enumAt(ClusterEndpoint.Scheme.values(),scheme),enumAt(ClusterEndpoint.AddressType.values(),type),address,port,priority,flags);}
    private static <T> T enumAt(T[] values,int code){if(code<1||code>values.length)throw invalid();return values[code-1];}
    private static int memberLength(ClusterMember m){int n=aligned(MEMBER_HEADER_BYTES+m.name().getBytes(StandardCharsets.UTF_8).length);for(var e:m.endpoints())n=Math.addExact(n,aligned(ENDPOINT_HEADER_BYTES+e.address().length));return n;}
    private static int aligned(int n){return (n+7)&~7;}private static void pad8(ByteBuffer b){while((b.position()&7)!=0)b.put((byte)0);}private static void skipZeroPad(ByteBuffer b){while((b.position()&7)!=0)if(b.get()!=0)throw invalid();}
    private static byte[] hash(byte[] in,int h1,int h2,int c1,int c2){try{byte[] copy=in.clone();Arrays.fill(copy,h1,h2,(byte)0);Arrays.fill(copy,c1,c2,(byte)0);return MessageDigest.getInstance("SHA-256").digest(copy);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static boolean allZero(byte[] v){for(byte b:v)if(b!=0)return false;return true;}private static ByteBuffer le(byte[] in){return ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);}private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid stable configuration v1");}
}
