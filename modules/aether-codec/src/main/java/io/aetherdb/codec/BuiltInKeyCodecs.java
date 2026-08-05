package io.aetherdb.codec;
import io.aetherdb.api.typed.*;import java.nio.*;import java.nio.charset.StandardCharsets;import java.security.*;import java.util.*;
/** Factory and type resolver for Aether's stable scalar key codecs. */
public final class BuiltInKeyCodecs{
 private BuiltInKeyCodecs(){}
 /** Returns the lexicographically ordered UTF-8 string codec.
  * @return string key codec */
 public static OrderedKeyCodec<String>utf8String(){return new Base<String>("aether:utf8",65517,Comparator.naturalOrder()){public byte[]encode(String v){if(v==null)throw new IllegalArgumentException("key is null");return v.getBytes(StandardCharsets.UTF_8);}public String decode(byte[]b){return new String(b,StandardCharsets.UTF_8);}};}
 /** Returns the order-preserving signed 64-bit integer codec.
  * @return long key codec */
 public static OrderedKeyCodec<Long>signedLong(){return new Base<Long>("aether:i64",8,Comparator.naturalOrder()){public byte[]encode(Long v){return ByteBuffer.allocate(8).putLong(v^Long.MIN_VALUE).array();}public Long decode(byte[]b){if(b.length!=8)throw new IllegalArgumentException("invalid long key");return ByteBuffer.wrap(b).getLong()^Long.MIN_VALUE;}};}
 /** Returns the unsigned-component-ordered UUID codec.
  * @return UUID key codec */
 public static OrderedKeyCodec<UUID>uuid(){return new Base<UUID>("aether:uuid",16,(a,b)->{int c=Long.compareUnsigned(a.getMostSignificantBits(),b.getMostSignificantBits());return c!=0?c:Long.compareUnsigned(a.getLeastSignificantBits(),b.getLeastSignificantBits());}){public byte[]encode(UUID v){return ByteBuffer.allocate(16).putLong(v.getMostSignificantBits()).putLong(v.getLeastSignificantBits()).array();}public UUID decode(byte[]b){if(b.length!=16)throw new IllegalArgumentException("invalid UUID key");ByteBuffer x=ByteBuffer.wrap(b);return new UUID(x.getLong(),x.getLong());}};}
 /** Resolves a built-in codec for a supported Java key class.
  * @param type key class
  * @param <K> key type
  * @return matching ordered codec */
 public static <K> OrderedKeyCodec<K>forType(Class<K>type){Objects.requireNonNull(type,"type");OrderedKeyCodec<?>codec;if(type==String.class)codec=utf8String();else if(type==Long.class||type==long.class)codec=signedLong();else if(type==UUID.class)codec=uuid();else throw new IllegalArgumentException("CODEC_NOT_AVAILABLE for key type "+type.getName());@SuppressWarnings("unchecked")OrderedKeyCodec<K>typed=(OrderedKeyCodec<K>)codec;return typed;}
 private abstract static class Base<T>implements OrderedKeyCodec<T>{private final String id;private final int max;private final Comparator<T>cmp;Base(String i,int m,Comparator<T>c){id=i;max=m;cmp=c;}public String codecId(){return id;}public int encodingVersion(){return 1;}public int maximumEncodedSize(){return max;}public byte[]fingerprint(){try{return MessageDigest.getInstance("SHA-256").digest((id+":v1").getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}public Comparator<T>comparator(){return cmp;}}
}
