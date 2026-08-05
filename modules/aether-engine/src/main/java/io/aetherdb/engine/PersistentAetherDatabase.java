package io.aetherdb.engine;

import io.aetherdb.api.*;
import io.aetherdb.api.exceptions.*;
import io.aetherdb.api.result.LookupResult;
import io.aetherdb.format.checksum.MaskedCrc32c;
import io.aetherdb.io.*;
import io.aetherdb.wal.format.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** WAL-first persistent local coordinator backed by atomic checkpoint publication. */
final class PersistentAetherDatabase implements AetherDatabase {
    private static final String IDENTITY="DB-IDENTITY",OPTIONS="FORMAT-OPTIONS",CURRENT="CURRENT";
    private static final int MANIFEST_BYTES=128,CHECKPOINT_HEADER_BYTES=64,GROUP_HEADER_BYTES=48;
    private final Path root,walPath;private final DatabaseLock lock;private final UUID databaseId;private final FileChannel wal;
    private InMemoryAetherDatabase memory;private long generation;private int walRecordNumber;private boolean closed;

    static PersistentAetherDatabase open(Path requested){
        DatabaseLock lock=null;FileChannel wal=null;
        try{
            Path absolute=requested.toAbsolutePath().normalize();Files.createDirectories(absolute);Path root=PathSecurityValidator.validateRoot(absolute,true);lock=DatabaseLock.acquire(root);
            boolean hasIdentity=Files.exists(root.resolve(IDENTITY),LinkOption.NOFOLLOW_LINKS);
            if(!hasIdentity)initialize(root);else validateIdentityPair(root);
            DatabaseIdentityV1 identity=DatabaseIdentityV1.decode(Files.readAllBytes(root.resolve(IDENTITY)));
            Manifest manifest=readManifest(root,identity.databaseId());
            InMemoryAetherDatabase memory=new InMemoryAetherDatabase(manifest.watermark());
            readCheckpoint(root.resolve(manifest.checkpoint()),identity.databaseId(),manifest.watermark(),memory);
            Path walPath=root.resolve(WalFormatV1.fileName(1));
            wal=FileChannel.open(walPath,StandardOpenOption.READ,StandardOpenOption.WRITE);
            WalRecovery recovery=recoverWal(wal,identity.databaseId(),manifest.watermark(),memory);
            wal.truncate(recovery.validEnd());wal.position(recovery.validEnd());
            return new PersistentAetherDatabase(root,lock,identity.databaseId(),walPath,wal,memory,manifest.generation(),recovery.records());
        }catch(Throwable failure){closeSuppressed(wal,failure);closeSuppressed(lock,failure);if(failure instanceof DatabaseOpenException e)throw e;throw new DatabaseOpenException("cannot open persistent database: "+requested,failure);}
    }
    private PersistentAetherDatabase(Path root,DatabaseLock lock,UUID id,Path walPath,FileChannel wal,InMemoryAetherDatabase memory,long generation,int records){this.root=root;this.lock=lock;databaseId=id;this.walPath=walPath;this.wal=wal;this.memory=memory;this.generation=generation;walRecordNumber=records;}

    @Override public synchronized void put(byte[]key,byte[]value){try(WriteBatch batch=new WriteBatch()){batch.put(key,value);write(batch);}}
    @Override public synchronized void delete(byte[]key){try(WriteBatch batch=new WriteBatch()){batch.delete(key);write(batch);}}
    @Override public synchronized LookupResult get(byte[]key){ensureOpen();return memory.get(key);}
    @Override public synchronized LookupResult get(byte[]key,Snapshot snapshot){ensureOpen();return memory.get(key,snapshot);}
    @Override public synchronized Snapshot newSnapshot(){ensureOpen();return memory.newSnapshot();}
    @Override public synchronized AetherCursor scan(byte[]start,byte[]end){ensureOpen();return memory.scan(start,end);}
    @Override public synchronized AetherCursor scan(byte[]start,byte[]end,Snapshot snapshot){ensureOpen();return memory.scan(start,end,snapshot);}
    @Override public synchronized AetherCursor scanAll(){ensureOpen();return memory.scanAll();}
    @Override public synchronized AetherCursor scanAll(Snapshot snapshot){ensureOpen();return memory.scanAll(snapshot);}
    @Override public synchronized void write(WriteBatch batch){write(batch,WriteOptions.defaults());}
    @Override public synchronized WriteResult write(WriteBatch batch,WriteOptions options){
        ensureOpen();Objects.requireNonNull(batch,"batch");Objects.requireNonNull(options,"options");
        if(batch.operationCount()==0)return memory.write(batch,options);
        long first=Math.addExact(memory.lastVisibleSequence(),1),last=Math.addExact(first,batch.operationCount()-1L);
        byte[] logical=encodeGroup(batch,first,last);byte[] physical=WalFragmentCodec.fragment(logical,position(),++walRecordNumber);
        try{writeFully(wal,ByteBuffer.wrap(physical));boolean forced=options.durabilityMode()!=DurabilityMode.ASYNC_WAL;if(forced)wal.force(false);WriteResult applied=memory.write(batch,options);if(applied.firstSequence()!=first||applied.lastSequence()!=last)throw new IllegalStateException("sequence publication diverged from WAL");return new WriteResult(applied.operationCount(),first,last,options.durabilityMode(),forced);}catch(IOException e){throw new AetherException("WAL write failed; outcome may be indeterminate",e);}
    }
    @Override public synchronized boolean isClosed(){return closed;}
    @Override public synchronized void close(){if(closed)return;closed=true;Throwable failure=null;try{wal.force(false);publishCheckpoint();}catch(Throwable e){failure=e;}try{memory.close();}catch(Throwable e){failure=merge(failure,e);}try{wal.close();}catch(Throwable e){failure=merge(failure,e);}try{lock.close();}catch(Throwable e){failure=merge(failure,e);}if(failure!=null)throw new AetherException("persistent database close failed",failure);}

    private void publishCheckpoint()throws IOException{
        long next=Math.addExact(generation,1),watermark=memory.lastVisibleSequence();String table="SST-%020d.aesst".formatted(next),manifest="MANIFEST-%020d".formatted(next);
        byte[] checkpoint=encodeCheckpoint(databaseId,watermark,memory);atomicWrite(root,table,checkpoint);atomicWrite(root,manifest,encodeManifest(databaseId,next,watermark,table));atomicWrite(root,CURRENT,(manifest+"\n").getBytes(StandardCharsets.US_ASCII));generation=next;
    }
    private long position(){try{return wal.position();}catch(IOException e){throw new AetherException("cannot query WAL position",e);}}
    private void ensureOpen(){if(closed)throw new AetherClosedException("database is closed");}

    private static void initialize(Path root)throws IOException{
        try(var entries=Files.list(root)){if(entries.anyMatch(p->!p.getFileName().toString().equals("LOCK")))throw new IOException("database directory is nonempty but has no DB-IDENTITY");}
        UUID id=UUID.randomUUID();long now=System.currentTimeMillis();atomicWrite(root,IDENTITY,new DatabaseIdentityV1(id,now,0,1).encode());atomicWrite(root,OPTIONS,new FormatOptionsV1(id,now).encode());
        String table="SST-%020d.aesst".formatted(1),manifest="MANIFEST-%020d".formatted(1);atomicWrite(root,table,encodeEmptyCheckpoint(id));atomicWrite(root,manifest,encodeManifest(id,1,0,table));atomicWrite(root,CURRENT,(manifest+"\n").getBytes(StandardCharsets.US_ASCII));
        Path wal=root.resolve(WalFormatV1.fileName(1));try(FileChannel channel=FileChannel.open(wal,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){writeFully(channel,ByteBuffer.wrap(new WalSegmentHeader(id,1,0,1,now).encodeBlock()));channel.force(true);}syncDirectory(root);
    }
    private static void validateIdentityPair(Path root)throws IOException{if(!Files.exists(root.resolve(OPTIONS))||!Files.exists(root.resolve(CURRENT)))throw new IOException("existing database metadata is incomplete");DatabaseIdentityV1 id=DatabaseIdentityV1.decode(Files.readAllBytes(root.resolve(IDENTITY)));FormatOptionsV1 options=FormatOptionsV1.decode(Files.readAllBytes(root.resolve(OPTIONS)));if(!id.databaseId().equals(options.databaseId()))throw new IOException("database identity/options mismatch");}
    private static Manifest readManifest(Path root,UUID id)throws IOException{String name=Files.readString(root.resolve(CURRENT),StandardCharsets.US_ASCII).strip();if(!name.matches("MANIFEST-[0-9]{20}"))throw new IOException("invalid CURRENT");byte[]data=Files.readAllBytes(root.resolve(name));if(data.length!=MANIFEST_BYTES)throw new IOException("invalid manifest length");ByteBuffer b=le(data);expect(b,"AETHMAN1");if(b.getInt()!=1)throw new IOException("unsupported manifest");UUID actual=getUuid(b);long gen=b.getLong(),watermark=b.getLong();int n=b.getInt();if(!actual.equals(id)||gen<1||watermark<0||n<1||n>40)throw new IOException("invalid manifest fields");byte[]nameBytes=new byte[n];b.get(nameBytes);for(int i=b.position();i<124;i++)if(data[i]!=0)throw new IOException("invalid manifest reserved bytes");if(le(data).getInt(124)!=MaskedCrc32c.masked(data,0,124))throw new IOException("manifest checksum mismatch");return new Manifest(gen,watermark,new String(nameBytes,StandardCharsets.US_ASCII));}
    private static byte[] encodeManifest(UUID id,long generation,long watermark,String checkpoint){byte[]out=new byte[MANIFEST_BYTES],name=checkpoint.getBytes(StandardCharsets.US_ASCII);ByteBuffer b=le(out);b.put("AETHMAN1".getBytes(StandardCharsets.US_ASCII)).putInt(1);putUuid(b,id);b.putLong(generation).putLong(watermark).putInt(name.length).put(name);b.putInt(124,MaskedCrc32c.masked(out,0,124));return out;}
    private static byte[] encodeEmptyCheckpoint(UUID id){byte[]out=new byte[CHECKPOINT_HEADER_BYTES+4];ByteBuffer b=le(out);b.put("AETHSST1".getBytes(StandardCharsets.US_ASCII)).putInt(1);putUuid(b,id);b.putLong(0).putInt(0).putInt(CHECKPOINT_HEADER_BYTES);b.putInt(out.length-4,MaskedCrc32c.masked(out,0,out.length-4));return out;}
    private static byte[] encodeCheckpoint(UUID id,long watermark,InMemoryAetherDatabase memory)throws IOException{ByteArrayOutputStream body=new ByteArrayOutputStream();int count=0;try(AetherCursor cursor=memory.scanAll()){while(cursor.next()){byte[]k=cursor.key(),v=cursor.value();writeInt(body,k.length);writeInt(body,v.length);body.writeBytes(k);body.writeBytes(v);count++;}}byte[]out=new byte[CHECKPOINT_HEADER_BYTES+body.size()+4];ByteBuffer b=le(out);b.put("AETHSST1".getBytes(StandardCharsets.US_ASCII)).putInt(1);putUuid(b,id);b.putLong(watermark).putInt(count).putInt(CHECKPOINT_HEADER_BYTES);System.arraycopy(body.toByteArray(),0,out,CHECKPOINT_HEADER_BYTES,body.size());b.putInt(out.length-4,MaskedCrc32c.masked(out,0,out.length-4));return out;}
    private static void readCheckpoint(Path path,UUID id,long watermark,InMemoryAetherDatabase memory)throws IOException{byte[]in=Files.readAllBytes(path);if(in.length<CHECKPOINT_HEADER_BYTES+4||le(in).getInt(in.length-4)!=MaskedCrc32c.masked(in,0,in.length-4))throw new IOException("checkpoint checksum mismatch");ByteBuffer b=le(in);expect(b,"AETHSST1");if(b.getInt()!=1||!getUuid(b).equals(id)||b.getLong()!=watermark)throw new IOException("checkpoint identity/watermark mismatch");int count=b.getInt(),offset=b.getInt();if(count<0||offset!=CHECKPOINT_HEADER_BYTES)throw new IOException("invalid checkpoint header");b.position(offset);for(int i=0;i<count;i++){if(b.remaining()<12)throw new IOException("truncated checkpoint");int kl=b.getInt(),vl=b.getInt();if(kl<0||kl>WriteBatch.MAX_KEY_BYTES||vl<0||vl>WriteBatch.MAX_VALUE_BYTES||b.remaining()-4<kl+vl)throw new IOException("invalid checkpoint record");byte[]k=new byte[kl],v=new byte[vl];b.get(k).get(v);memory.restoreVisible(k,v,watermark);}if(b.position()!=in.length-4)throw new IOException("checkpoint trailing bytes");}
    private static WalRecovery recoverWal(FileChannel wal,UUID id,long watermark,InMemoryAetherDatabase memory)throws IOException{if(wal.size()<WalFormatV1.HEADER_BLOCK_BYTES)throw new IOException("WAL header missing");byte[]header=readRange(wal,0,WalFormatV1.HEADER_BLOCK_BYTES);WalSegmentHeader.decode(header,id,1);byte[]physical=readRange(wal,WalFormatV1.HEADER_BLOCK_BYTES,(int)(wal.size()-WalFormatV1.HEADER_BLOCK_BYTES));List<byte[]>groups=WalFragmentCodec.reassemble(physical,WalFormatV1.HEADER_BLOCK_BYTES);long valid=WalFormatV1.HEADER_BLOCK_BYTES;int record=0;long expected=watermark+1;for(byte[]logical:groups){record++;valid=WalFormatV1.estimateEndOffset(valid,logical.length);DecodedGroup group=decodeGroup(logical);if(group.last()<=watermark)continue;if(group.first()<=watermark||group.first()!=expected)throw new IOException("WAL sequence discontinuity");try(WriteBatch batch=new WriteBatch()){for(Mutation m:group.mutations())if(m.delete())batch.delete(m.key());else batch.put(m.key(),m.value());WriteResult result=memory.write(batch,WriteOptions.defaults());if(result.firstSequence()!=group.first()||result.lastSequence()!=group.last())throw new IOException("WAL recovery sequence mismatch");}expected=group.last()+1;}return new WalRecovery(valid,record);}
    private static byte[] encodeGroup(WriteBatch batch,long first,long last){int bytes=GROUP_HEADER_BYTES;for(var m:batch.mutations()){bytes=Math.addExact(bytes,12+m.key().length+(m instanceof WriteBatch.Put p?p.value().length:0));}byte[]out=new byte[bytes];ByteBuffer b=le(out);b.put("AETHGRP1".getBytes(StandardCharsets.US_ASCII)).putShort((short)1).putShort((short)GROUP_HEADER_BYTES).putInt(bytes).putLong(first).putLong(last).putInt(batch.operationCount()).putInt(0).putInt(0).putInt(0);for(var m:batch.mutations()){byte[]k=m.key(),v=m instanceof WriteBatch.Put p?p.value():new byte[0];b.put((byte)(m instanceof WriteBatch.Delete?2:1)).put(new byte[3]).putInt(k.length).putInt(v.length).put(k).put(v);}b.putInt(44,MaskedCrc32c.masked(out,0,44));return out;}
    private static DecodedGroup decodeGroup(byte[]in)throws IOException{if(in.length<GROUP_HEADER_BYTES)throw new IOException("short WAL group");ByteBuffer b=le(in);expect(b,"AETHGRP1");if(b.getShort()!=1||b.getShort()!=GROUP_HEADER_BYTES||b.getInt()!=in.length)throw new IOException("invalid WAL group header");long first=b.getLong(),last=b.getLong();int count=b.getInt();if(b.getInt()!=0||b.getInt()!=0||b.getInt()!=MaskedCrc32c.masked(in,0,44)||first<1||last<first||last-first+1!=count)throw new IOException("invalid WAL group metadata");List<Mutation>mutations=new ArrayList<>();for(int i=0;i<count;i++){if(b.remaining()<12)throw new IOException("truncated WAL operation");int type=b.get()&255;if(b.get()!=0||b.get()!=0||b.get()!=0)throw new IOException("invalid WAL operation flags");int kl=b.getInt(),vl=b.getInt();if(kl<0||kl>WriteBatch.MAX_KEY_BYTES||vl<0||vl>WriteBatch.MAX_VALUE_BYTES||b.remaining()<kl+vl||(type==2&&vl!=0)||(type!=1&&type!=2))throw new IOException("invalid WAL operation");byte[]k=new byte[kl],v=new byte[vl];b.get(k).get(v);mutations.add(new Mutation(k,v,type==2));}if(b.hasRemaining())throw new IOException("WAL group trailing bytes");return new DecodedGroup(first,last,mutations);}
    private static void atomicWrite(Path root,String name,byte[]data)throws IOException{Path target=PathSecurityValidator.managed(root,name),temp=root.resolve(name+".tmp-"+UUID.randomUUID());try(FileChannel c=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){writeFully(c,ByteBuffer.wrap(data));c.force(true);}try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}syncDirectory(root);}
    private static void syncDirectory(Path root)throws IOException{try(FileChannel c=FileChannel.open(root,StandardOpenOption.READ)){c.force(true);}}
    private static void writeFully(FileChannel c,ByteBuffer b)throws IOException{while(b.hasRemaining())c.write(b);}
    private static byte[]readRange(FileChannel c,long offset,int length)throws IOException{byte[]out=new byte[length];ByteBuffer b=ByteBuffer.wrap(out);while(b.hasRemaining()){int n=c.read(b,offset+b.position());if(n<0)throw new EOFException();}return out;}
    private static ByteBuffer le(byte[]b){return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);}private static void putUuid(ByteBuffer b,UUID id){b.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());}private static UUID getUuid(ByteBuffer b){return new UUID(b.getLong(),b.getLong());}private static void expect(ByteBuffer b,String magic)throws IOException{byte[]m=new byte[8];b.get(m);if(!Arrays.equals(m,magic.getBytes(StandardCharsets.US_ASCII)))throw new IOException("bad magic: "+magic);}private static void writeInt(OutputStream out,int v)throws IOException{out.write(v);out.write(v>>>8);out.write(v>>>16);out.write(v>>>24);}private static Throwable merge(Throwable first,Throwable next){if(first==null)return next;first.addSuppressed(next);return first;}private static void closeSuppressed(AutoCloseable c,Throwable f){if(c!=null)try{c.close();}catch(Throwable e){f.addSuppressed(e);}}
    private record Manifest(long generation,long watermark,String checkpoint){}private record WalRecovery(long validEnd,int records){}private record Mutation(byte[]key,byte[]value,boolean delete){}private record DecodedGroup(long first,long last,List<Mutation>mutations){}
}
