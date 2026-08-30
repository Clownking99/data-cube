package com.datacube.config;

import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlDraftCodecTest {
    private static final UUID ID = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private static final long MODIFIED = 1788000000000L;
    private static final int SQL_LIMIT = 1024 * 1024;

    @Test void writesExactVersionOneBytesAndReadsIndependentFixture() throws Exception {
        String sql = " \r\nselect '中文😀', '\u0000';\n\t ";
        SqlDraft v = new SqlDraft(ID, MODIFIED, "saved-id", DbType.ORACLE, "Synthetic connection", "  schema  ", sql);
        byte[] expected = wire("saved-id", "ORACLE", "Synthetic connection", "  schema  ", sql);
        assertArrayEquals(expected, SqlDraftCodec.encode(v)); assertEquals(v, SqlDraftCodec.decode(expected));
    }

    @Test void distinguishesNullMetadataEmptyMetadataAndEmptySql() throws Exception {
        SqlDraft v = new SqlDraft(ID, 0, null, null, null, "", "");
        byte[] expected = wireAt(0, null, null, null, "", "");
        assertArrayEquals(expected, SqlDraftCodec.encode(v)); assertEquals(v, SqlDraftCodec.decode(expected));
    }

    @ParameterizedTest @ValueSource(strings={"POSTGRESQL","ORACLE"})
    void retainsIdentityAcrossSupportedTypesWithoutNameMatching(String type) throws Exception {
        SqlDraft v = new SqlDraft(ID, MODIFIED, "stable-id", DbType.valueOf(type), null, null, "\u2003\t\n");
        assertEquals(v, SqlDraftCodec.decode(wire("stable-id", type, null, null, "\u2003\t\n")));
    }

    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void sqlByteLimitRejectsOnlyAboveBoundary(int delta) throws Exception {
        String sql = "😀".repeat(SQL_LIMIT / 4 - 1) + "x".repeat(4 + delta);
        SqlDraft v = new SqlDraft(ID, MODIFIED, null, null, null, null, sql); byte[] f = wire(null,null,null,null,sql);
        if (delta <= 0) { assertArrayEquals(f, SqlDraftCodec.encode(v)); assertEquals(sql, SqlDraftCodec.decode(f).sql()); }
        else { assertThrows(IOException.class, () -> SqlDraftCodec.encode(v)); assertThrows(IOException.class, () -> SqlDraftCodec.decode(f)); }
    }

    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void everyMetadataFieldUsesUtf8ByteLimit(int delta) throws Exception {
        String m="界".repeat(1365)+"x".repeat(1+delta);
        for(int slot:new int[]{0,2,3}) { String id=slot==0?m:"id", name=slot==2?m:null, schema=slot==3?m:null;
            SqlDraft v=new SqlDraft(ID,MODIFIED,id,DbType.POSTGRESQL,name,schema,"select 1"); byte[] f=wire(id,"POSTGRESQL",name,schema,"select 1");
            if(delta<=0) assertEquals(v,SqlDraftCodec.decode(f)); else { assertThrows(IOException.class,()->SqlDraftCodec.encode(v)); assertThrows(IOException.class,()->SqlDraftCodec.decode(f)); }
        }
    }

    @Test void maximumCombinedPayloadIsAcceptedAndWholeFileLimitIsBounded() throws Exception {
        SqlDraft v=new SqlDraft(ID,MODIFIED,"i".repeat(4096),DbType.POSTGRESQL,"n".repeat(4096),"s".repeat(4096),"x".repeat(SQL_LIMIT));
        byte[] f=wire(v.connectionId(),"POSTGRESQL",v.connectionName(),v.schema(),v.sql()); assertArrayEquals(f,SqlDraftCodec.encode(v));
        assertEquals(v,SqlDraftCodec.decode(f)); assertThrows(IOException.class,()->SqlDraftCodec.decode(new byte[SqlDraftCodec.MAX_FILE_BYTES+1]));
    }

    @Test void rejectsBadHeadersEveryTruncationAndTrailingData() throws Exception {
        byte[] valid=wire("id","ORACLE","name","schema","select 1");
        for(int n=0;n<valid.length;n++) { byte[] t=Arrays.copyOf(valid,n); assertThrows(IOException.class,()->SqlDraftCodec.decode(t)); }
        for(int off:new int[]{0,4}) { byte[] c=valid.clone(); ByteBuffer.wrap(c).putInt(off,off==0?0:2); assertThrows(IOException.class,()->SqlDraftCodec.decode(c)); }
        assertThrows(IOException.class,()->SqlDraftCodec.decode(Arrays.copyOf(valid,valid.length+1))); assertThrows(IOException.class,()->SqlDraftCodec.decode(null)); assertThrows(IOException.class,()->SqlDraftCodec.encode(null));
    }

    @ParameterizedTest @ValueSource(ints={-2,Integer.MIN_VALUE,4097,Integer.MAX_VALUE})
    void rejectsInvalidLengthsBeforeReadingPayload(int length) throws Exception { byte[] b=wire(null,null,null,null,""); ByteBuffer.wrap(b).putInt(32,length); assertThrows(IOException.class,()->SqlDraftCodec.decode(b)); }

    @Test void rejectsInvalidIdentityTypeAndNullSqlOnWire() throws Exception {
        String[][] bad={{"id",null,null,null,"select 1"},{null,"ORACLE",null,null,"select 1"},{" ","ORACLE",null,null,"select 1"},{"id","REDIS",null,null,"select 1"},{"id","NEW_DB",null,null,"select 1"},{"id","x".repeat(4097),null,null,"select 1"},{null,null,null,null,null}};
        for(String[] f:bad) assertThrows(IOException.class,()->SqlDraftCodec.decode(wire(f[0],f[1],f[2],f[3],f[4]))); assertThrows(IOException.class,()->SqlDraftCodec.decode(wireAt(-1,null,null,null,null,"x")));
    }

    @Test void rejectsMalformedUtf8AndUnpairedSurrogatesWithoutSubstitution() throws Exception {
        byte[] malformed=wire(null,null,null,null,"ab"); malformed[malformed.length-2]=(byte)0xc3; malformed[malformed.length-1]=0x28; assertThrows(IOException.class,()->SqlDraftCodec.decode(malformed));
        byte[] id=wire("a","ORACLE",null,null,"select 1"); id[36]=(byte)0xff; assertThrows(IOException.class,()->SqlDraftCodec.decode(id));
        for(String s:new String[]{"\ud800","\udc00","secret\ud800text"}) { assertThrows(IOException.class,()->SqlDraftCodec.encode(new SqlDraft(ID,MODIFIED,null,null,null,null,s))); assertThrows(IOException.class,()->SqlDraftCodec.encode(new SqlDraft(ID,MODIFIED,"id",DbType.ORACLE,s,null,"ok"))); }
    }

    @Test void valueValidationAndDiagnosticsNeverExposePrivateText() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new SqlDraft(null,MODIFIED,null,null,null,null,"secret")); assertThrows(IllegalArgumentException.class,()->new SqlDraft(ID,-1,null,null,null,null,"secret")); assertThrows(IllegalArgumentException.class,()->new SqlDraft(ID,MODIFIED,null,null,null,null,null)); assertThrows(IllegalArgumentException.class,()->new SqlDraft(ID,MODIFIED,"id",null,null,null,"secret")); assertThrows(IllegalArgumentException.class,()->new SqlDraft(ID,MODIFIED," ",DbType.ORACLE,null,null,"secret")); assertThrows(IllegalArgumentException.class,()->new SqlDraft(ID,MODIFIED,"id",DbType.REDIS,null,null,"secret"));
        SqlDraft v=new SqlDraft(ID,MODIFIED,"private-id",DbType.ORACLE,"private-name","private-schema","private-sql"); assertEquals("SqlDraft[id="+ID+", modifiedAt="+MODIFIED+", sqlChars=11]",v.toString()); IOException e=assertThrows(IOException.class,()->SqlDraftCodec.decode(wire("private-id","private-unknown-type",null,null,"private-sql"))); assertEquals("Invalid SQL draft format",e.getMessage()); assertNull(e.getCause());
    }

    private static byte[] wire(String id,String type,String name,String schema,String sql)throws IOException{return wireAt(MODIFIED,id,type,name,schema,sql);}
    private static byte[] wireAt(long modified,String id,String type,String name,String schema,String sql)throws IOException { ByteArrayOutputStream b=new ByteArrayOutputStream(); try(DataOutputStream o=new DataOutputStream(b)){o.writeInt(0x44434452);o.writeInt(1);o.writeLong(ID.getMostSignificantBits());o.writeLong(ID.getLeastSignificantBits());o.writeLong(modified);for(String s:new String[]{id,type,name,schema,sql}){if(s==null)o.writeInt(-1);else{byte[] x=s.getBytes(StandardCharsets.UTF_8);o.writeInt(x.length);o.write(x);}}}return b.toByteArray(); }
}
