package com.datacube.export;

import com.datacube.spi.SqlDialect;
import com.datacube.spi.model.DbType;
import java.io.*;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Complete, finite value adaptation for table export only. Query/display writers are unchanged. */
final class TableExportValues {
    static final int VALUE_BYTES=1_048_576,ROW_BYTES=4_194_304,DDL_BYTES=4_194_304;
    static final int XLSX_ROWS=1_048_575,XLSX_COLUMNS=16_384,XLSX_TEXT=32_767;
    private static final Set<Class<?>> NUMBERS=Set.of(Byte.class,Short.class,Integer.class,Long.class,BigInteger.class,BigDecimal.class,Float.class,Double.class);
    private static final Set<Class<?>> TEMPORAL=Set.of(java.sql.Date.class,java.sql.Time.class,Timestamp.class,LocalDate.class,LocalTime.class,LocalDateTime.class,OffsetDateTime.class,OffsetTime.class,Instant.class,ZonedDateTime.class);
    private TableExportValues(){}
    static TableExportFailure type(){return new TableExportFailure(TableExportFailure.Kind.TYPE);}
    static TableExportFailure limit(){return new TableExportFailure(TableExportFailure.Kind.LIMIT);}
    static final class TextBudget {
        final int maxBytes,maxChars;
        int bytes,chars;
        boolean high;
        TextBudget(int maxBytes,int maxChars){this.maxBytes=maxBytes;this.maxChars=maxChars;}
        void add(char ch){
            if(++chars>maxChars)throw limit();
            if(high){if(!Character.isLowSurrogate(ch))throw type();high=false;bytes+=4;}
            else if(Character.isHighSurrogate(ch)){high=true;return;}
            else if(Character.isLowSurrogate(ch))throw type();
            else bytes+=ch<128?1:ch<2048?2:3;
            if(bytes>maxBytes)throw limit();
        }
        int finish(){if(high)throw type();return bytes;}
    }
    static int textBytes(String text,int maxBytes,int maxChars){
        if(text.length()>maxChars)throw limit();
        var budget=new TextBudget(maxBytes,maxChars);for(int i=0;i<text.length();i++)budget.add(text.charAt(i));return budget.finish();
    }
    static String text(Reader reader,int bytes,int chars,TableExportJdbcJob job)throws Exception{
        if(reader==null)return null;
        var lease=job.ownValue("value-reader",()->{reader.close();return null;});
        try{
            var budget=new TextBudget(bytes,chars);var out=new StringBuilder();char[] buffer=new char[4096];
            int n;while(true){job.check();n=reader.read(buffer);job.check();if(n<0)break;if(n==0){Thread.yield();continue;}
                for(int i=0;i<n;i++)budget.add(buffer[i]);out.append(buffer,0,n);}
            budget.finish();return out.toString();
        }catch(Error error){job.fatal.compareAndSet(null,error);throw error;
        }finally{job.closeValue(lease);}
    }
    static byte[] binary(InputStream stream,int max,TableExportJdbcJob job)throws Exception{
        if(stream==null)return null;
        var lease=job.ownValue("value-stream",()->{stream.close();return null;});
        try{
            var out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n,total=0;
            while(true){job.check();n=stream.read(buffer);job.check();if(n<0)break;if(n==0){Thread.yield();continue;}
                if(n>max-total)throw limit();total+=n;out.write(buffer,0,n);}
            return out.toByteArray();
        }catch(Error error){job.fatal.compareAndSet(null,error);throw error;
        }finally{job.closeValue(lease);}
    }
    static List<String> columns(ResultSetMetaData metadata,ExportFormat format,TableExportJdbcJob job)throws Exception{
        job.check();int count=metadata.getColumnCount();job.check();
        if(count<1||count>XLSX_COLUMNS)throw limit();
        var columns=new ArrayList<String>(count);int total=0;
        for(int c=1;c<=count;c++){job.check();String label=metadata.getColumnLabel(c);job.check();if(label==null)throw type();
            total+=textBytes(label,VALUE_BYTES,format==ExportFormat.XLSX?XLSX_TEXT:VALUE_BYTES);if(total>ROW_BYTES)throw limit();columns.add(label);}
        return List.copyOf(columns);
    }
    static List<Object> row(ResultSet rows,ResultSetMetaData metadata,int count,ExportFormat format,DbType db,SqlDialect dialect,TableExportJdbcJob job)throws Exception{
        var values=new ArrayList<Object>(count);int bytes=0;
        for(int c=1;c<=count;c++){
            job.check();int kind=metadata.getColumnType(c);job.check();
            if(Set.of(Types.ARRAY,Types.STRUCT,Types.REF,Types.ROWID,Types.JAVA_OBJECT).contains(kind))throw type();
            String typeName=metadata.getColumnTypeName(c);job.check();if(typeName!=null&&typeName.equalsIgnoreCase("BFILE"))throw type();
            Object value;
            switch(kind){
                case Types.CLOB,Types.NCLOB -> {
                    Clob lob=kind==Types.NCLOB?rows.getNClob(c):rows.getClob(c);
                    if(lob==null)value=null;
                    else{var lease=job.ownValue("clob",()->{lob.free();return null;});
                        try{job.check();value=text(lob.getCharacterStream(),VALUE_BYTES,format==ExportFormat.XLSX?XLSX_TEXT:VALUE_BYTES,job);}
                        catch(Error error){job.fatal.compareAndSet(null,error);throw error;}
                        finally{job.closeValue(lease);}}
                }
                case Types.SQLXML -> {
                    SQLXML xml=rows.getSQLXML(c);if(xml==null)value=null;
                    else{var lease=job.ownValue("sqlxml",()->{xml.free();return null;});
                        try{job.check();value=text(xml.getCharacterStream(),VALUE_BYTES,format==ExportFormat.XLSX?XLSX_TEXT:VALUE_BYTES,job);}
                        catch(Error error){job.fatal.compareAndSet(null,error);throw error;}
                        finally{job.closeValue(lease);}}
                }
                case Types.BLOB -> {
                    ensureBinary(format,dialect);Blob blob=rows.getBlob(c);if(blob==null)value=null;
                    else{var lease=job.ownValue("blob",()->{blob.free();return null;});
                        try{job.check();value=binary(blob.getBinaryStream(),binaryLimit(db),job);}
                        catch(Error error){job.fatal.compareAndSet(null,error);throw error;}finally{job.closeValue(lease);}}
                }
                case Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY -> {
                    ensureBinary(format,dialect);value=binary(rows.getBinaryStream(c),binaryLimit(db),job);
                }
                case Types.LONGVARCHAR,Types.LONGNVARCHAR -> value=text(rows.getCharacterStream(c),VALUE_BYTES,format==ExportFormat.XLSX?XLSX_TEXT:VALUE_BYTES,job);
                default -> {value=rows.getObject(c);job.check();}
            }
            value=scalar(value,format,db,dialect);
            bytes+=size(value,format);if(bytes>ROW_BYTES)throw limit();values.add(value);job.check();
        }
        return Collections.unmodifiableList(values);
    }
    static void ensureBinary(ExportFormat format,SqlDialect dialect){if(format!=ExportFormat.SQL||!dialect.supportsBinaryLiteral())throw type();}
    static int binaryLimit(DbType db){return db==DbType.ORACLE?2000:VALUE_BYTES;}
    static Object scalar(Object value,ExportFormat format,DbType db,SqlDialect dialect){
        if(value==null)return null;Class<?> clazz=value.getClass();
        if(clazz==String.class||clazz==Character.class){String text=clazz==String.class?(String)value:Character.toString((Character)value);
            textBytes(text,VALUE_BYTES,format==ExportFormat.XLSX?XLSX_TEXT:VALUE_BYTES);return text;}
        if(clazz==Boolean.class)return value;
        if(clazz==byte[].class){ensureBinary(format,dialect);if(((byte[])value).length>binaryLimit(db))throw limit();return value;}
        if(NUMBERS.contains(clazz)){
            if(value instanceof Double d&&!Double.isFinite(d)||value instanceof Float f&&!Float.isFinite(f))throw type();
            if(value instanceof BigInteger n&&n.bitLength()>3_500_000)throw limit();
            if(value instanceof BigDecimal n&&(n.precision()>VALUE_BYTES||Math.abs((long)n.scale())>VALUE_BYTES))throw limit();
            String literal=value.toString();textBytes(literal,VALUE_BYTES,VALUE_BYTES);
            if(format==ExportFormat.XLSX){
                var number=new BigDecimal(literal).stripTrailingZeros();double parsed=Double.parseDouble(literal);
                double magnitude=Math.abs(parsed);
                if(number.precision()>15||!Double.isFinite(parsed)||magnitude>9.99999999999999E307
                        ||magnitude!=0&&magnitude<2.2250738585072014E-308
                        ||BigDecimal.valueOf(parsed).compareTo(number)!=0)throw type();
            }
            return value;
        }
        if(TEMPORAL.contains(clazz)){
            if(format==ExportFormat.SQL&&db==DbType.ORACLE){
                if(clazz==LocalDate.class)return java.sql.Date.valueOf((LocalDate)value);
                if(clazz==LocalDateTime.class)return Timestamp.valueOf((LocalDateTime)value);
                if(clazz!=java.sql.Date.class&&clazz!=Timestamp.class)throw type();
            }
            textBytes(value.toString(),VALUE_BYTES,format==ExportFormat.XLSX?XLSX_TEXT:VALUE_BYTES);return value;
        }
        throw type();
    }
    static int size(Object value,ExportFormat format){
        if(value==null)return 0;if(value instanceof byte[] bytes)return bytes.length;
        return textBytes(value.toString(),VALUE_BYTES,format==ExportFormat.XLSX?XLSX_TEXT:VALUE_BYTES);
    }
    static void rowNumber(long count,ExportFormat format){if(format==ExportFormat.XLSX&&count>XLSX_ROWS)throw limit();}
}
