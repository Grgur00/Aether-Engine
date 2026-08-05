package io.aetherdb.examples.social;

import io.aetherdb.api.typed.ValueCodec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.UUID;

/** Explicit schema-v1 codec; byte handling is isolated at this application boundary. */
public enum UserProfileCodec implements ValueCodec<UserProfile> {
    INSTANCE;
    private static final UUID SCHEMA_ID=UUID.fromString("28f98ef5-d249-4f64-9791-09267036396a");
    public UUID schemaId(){return SCHEMA_ID;}public int currentSchemaVersion(){return 1;}public int maximumEncodedSize(UserProfile value){return 64*1024;}
    public byte[] fingerprint(){try{return MessageDigest.getInstance("SHA-256").digest("social-profile:v1:utf8-fields+i64+bool+instant".getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public byte[] encode(UserProfile p){try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){write(out,p.id());write(out,p.username());write(out,p.displayName());write(out,p.email());write(out,p.bio());write(out,p.location());out.writeLong(p.followerCount());out.writeBoolean(p.verified());write(out,p.createdAt().toString());}return bytes.toByteArray();}catch(IOException e){throw new IllegalArgumentException("cannot encode profile",e);}}
    public UserProfile decode(int version,byte[] encoded){if(version!=1)throw new IllegalArgumentException("unsupported profile schema version: "+version);try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(encoded))){UserProfile p=new UserProfile(read(in),read(in),read(in),read(in),read(in),read(in),in.readLong(),in.readBoolean(),Instant.parse(read(in)));if(in.available()!=0)throw new IllegalArgumentException("trailing profile bytes");return p;}catch(IOException e){throw new IllegalArgumentException("malformed profile",e);}}
    private static void write(DataOutputStream out,String value)throws IOException{byte[]b=value.getBytes(StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);}private static String read(DataInputStream in)throws IOException{int n=in.readInt();if(n<0||n>64*1024||n>in.available())throw new IllegalArgumentException("invalid string length");return new String(in.readNBytes(n),StandardCharsets.UTF_8);}
}
