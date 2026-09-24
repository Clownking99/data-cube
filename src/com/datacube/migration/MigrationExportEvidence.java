package com.datacube.migration;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Single-table source read window and completed file binding; not a cross-table SCN. */
public record MigrationExportEvidence(String sourceScope,String columns,String dataSha256,long rows,
                                      Instant began,Instant finished) {
    private static final int MAGIC=0x44434531;
    static String columns(List<MigrationPlan.Column> columns) {
        return MigrationPreflight.fingerprint(columns.stream().map(c -> c.sourceName()+":"+c.sourceType()+":"+c.targetName()+":"+c.pgType()).collect(java.util.stream.Collectors.joining("\n")));
    }
    static String scope(String sourceIdentity,String owner) { return MigrationPreflight.fingerprint(sourceIdentity+":"+owner); }
    static Path path(Path data) { return data.resolveSibling(data.getFileName()+".proof"); }
    static Path pending(Path data) { return data.resolveSibling(data.getFileName()+".pending"); }
    public static MigrationExportEvidence read(Path data) throws IOException {
        if(Files.exists(pending(data),LinkOption.NOFOLLOW_LINKS))throw new IOException("Export publication incomplete; use a new export directory");
        Path proof=path(data); MigrationFiles.checkParents(proof);
        if(!Files.exists(proof,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(proof,LinkOption.NOFOLLOW_LINKS) || Files.size(proof)>2048)throw new IOException("Invalid source evidence");
        byte[] bytes;try(InputStream in=Files.newInputStream(proof,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(2049);}
        if(bytes.length<36 || bytes.length>2048)throw new IOException("Invalid source evidence size");
        byte[] content=Arrays.copyOf(bytes,bytes.length-32);
        if(!java.security.MessageDigest.isEqual(MigrationFiles.digest().digest(content),Arrays.copyOfRange(bytes,bytes.length-32,bytes.length)))throw new IOException("Invalid source evidence checksum");
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(content))) {
            if(in.readInt()!=MAGIC)throw new IOException("Unsupported source evidence version");
            var result=new MigrationExportEvidence(in.readUTF(),in.readUTF(),in.readUTF(),in.readLong(),Instant.parse(in.readUTF()),Instant.parse(in.readUTF()));
            if(result.rows<0 || !result.sourceScope.matches("[0-9a-f]{64}") || !result.columns.matches("[0-9a-f]{64}") || !result.dataSha256.matches("[0-9a-f]{64}") || result.finished.isBefore(result.began) || in.available()!=0)throw new IOException("Invalid source evidence fields");
            return result;
        } catch(java.time.DateTimeException error) { throw new IOException("Invalid source window"); }
    }
    public void write(Path data) throws IOException {
        Path proof=path(data); MigrationFiles.checkParents(proof);
        if(Files.exists(proof,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(proof,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid source evidence destination");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)) {out.writeInt(MAGIC);out.writeUTF(sourceScope);out.writeUTF(columns);out.writeUTF(dataSha256);out.writeLong(rows);out.writeUTF(began.toString());out.writeUTF(finished.toString());}
        Path temporary=Files.createTempFile(data.getParent(),".source-",".part");
        try {
            byte[] content=bytes.toByteArray();
            try(OutputStream out=Files.newOutputStream(temporary,LinkOption.NOFOLLOW_LINKS)){out.write(content);out.write(MigrationFiles.digest().digest(content));}
            try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
            MigrationFiles.checkParents(proof);
            Files.move(temporary,proof,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temporary);}
    }
}
