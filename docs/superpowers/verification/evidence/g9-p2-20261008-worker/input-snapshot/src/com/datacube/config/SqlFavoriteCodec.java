package com.datacube.config;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/** Versioned bounded UTF-8 with corruption detection; neither encryption nor authenticity. */
final class SqlFavoriteCodec {
    static final int MAX_SQL_BYTES=256*1024, MAX_FILE_BYTES=MAX_SQL_BYTES+2048;
    private static final int MAGIC=0x44434656;
    private SqlFavoriteCodec() {}
    static byte[] encode(SqlFavorite value) throws IOException {
        try {
            var bytes=new ByteArrayOutputStream();
            try (var out=new DataOutputStream(bytes)) {
                out.writeInt(MAGIC); out.writeInt(1); out.writeLong(value.id().getMostSignificantBits()); out.writeLong(value.id().getLeastSignificantBits());
                out.writeLong(value.modifiedAt()); text(out,value.name(),1024); text(out,value.group(),512); text(out,value.sql(),MAX_SQL_BYTES);
            }
            byte[] payload=bytes.toByteArray(); bytes.write(digest(payload));
            return bytes.toByteArray();
        } catch (IOException | RuntimeException invalid) { throw invalid(); }
    }
    static SqlFavorite decode(byte[] bytes) throws IOException {
        if (bytes==null || bytes.length<64 || bytes.length>MAX_FILE_BYTES) throw invalid();
        byte[] payload=Arrays.copyOf(bytes,bytes.length-32);
        if (!MessageDigest.isEqual(digest(payload),Arrays.copyOfRange(bytes,bytes.length-32,bytes.length))) throw invalid();
        try (var in=new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt()!=MAGIC || in.readInt()!=1) throw invalid();
            UUID id=new UUID(in.readLong(),in.readLong()); long time=in.readLong();
            var value=new SqlFavorite(id,text(in,1024),text(in,512),text(in,MAX_SQL_BYTES),time);
            if (in.available()!=0) throw invalid(); return value;
        } catch (IOException | RuntimeException invalid) { throw invalid(); }
    }
    private static void text(DataOutputStream out,String value,int limit) throws IOException {
        if (value==null || value.length()>limit) throw invalid();
        var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
        if (encoded.remaining()>limit) throw invalid();
        byte[] bytes=new byte[encoded.remaining()]; encoded.get(bytes); out.writeInt(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in,int limit) throws IOException {
        int length=in.readInt(); if (length<0 || length>limit || length>in.available()) throw invalid();
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(in.readNBytes(length))).toString();
    }
    private static byte[] digest(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static IOException invalid() { return new IOException("Invalid SQL favorite format"); }
}
