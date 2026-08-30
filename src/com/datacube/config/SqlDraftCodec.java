package com.datacube.config;

import java.io.IOException;
import com.datacube.spi.model.DbType;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.UUID;

final class SqlDraftCodec {
    static final int MAX_SQL_BYTES = 1024 * 1024;
    static final int MAX_METADATA_BYTES = 4096;
    static final int MAX_FILE_BYTES = MAX_SQL_BYTES + 4 * MAX_METADATA_BYTES + 64;
    private SqlDraftCodec() { }
    private static final int MAGIC = 0x44434452;

    static byte[] encode(SqlDraft draft) throws IOException {
        if (draft == null) throw invalid();
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(b)) {
                out.writeInt(MAGIC); out.writeInt(1);
                out.writeLong(draft.id().getMostSignificantBits()); out.writeLong(draft.id().getLeastSignificantBits()); out.writeLong(draft.modifiedAt());
                writeText(out,draft.connectionId(),MAX_METADATA_BYTES,true);
                writeText(out,draft.connectionType()==null?null:draft.connectionType().name(),MAX_METADATA_BYTES,true);
                writeText(out,draft.connectionName(),MAX_METADATA_BYTES,true); writeText(out,draft.schema(),MAX_METADATA_BYTES,true);
                writeText(out,draft.sql(),MAX_SQL_BYTES,false);
            }
            if (b.size() > MAX_FILE_BYTES) throw invalid(); return b.toByteArray();
        } catch (IOException | IllegalArgumentException e) { throw invalid(); }
    }

    static SqlDraft decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_FILE_BYTES) throw invalid();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt()!=MAGIC || in.readInt()!=1) throw invalid();
            UUID id=new UUID(in.readLong(),in.readLong()); long modified=in.readLong();
            String connectionId=readText(in,MAX_METADATA_BYTES,true), type=readText(in,MAX_METADATA_BYTES,true);
            String name=readText(in,MAX_METADATA_BYTES,true), schema=readText(in,MAX_METADATA_BYTES,true), sql=readText(in,MAX_SQL_BYTES,false);
            if (in.available()!=0) throw invalid();
            return new SqlDraft(id,modified,connectionId,type==null?null:DbType.valueOf(type),name,schema,sql);
        } catch (IOException | IllegalArgumentException e) { throw invalid(); }
    }

    private static void writeText(DataOutputStream out,String text,int limit,boolean nullable)throws IOException {
        if(text==null){if(!nullable)throw invalid();out.writeInt(-1);return;}
        ByteBuffer b=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
        if(b.remaining()>limit)throw invalid(); byte[] v=new byte[b.remaining()];b.get(v);out.writeInt(v.length);out.write(v);
    }
    private static String readText(DataInputStream in,int limit,boolean nullable)throws IOException {
        int n=in.readInt(); if(n==-1&&nullable)return null; if(n<0||n>limit||n>in.available())throw invalid(); byte[] b=new byte[n];in.readFully(b);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
    }
    private static IOException invalid(){return new IOException("Invalid SQL draft format");}
}
