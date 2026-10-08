package com.datacube.migration;

import java.io.*;
import java.math.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.sql.*;
import java.util.*;

/** Strict data-only INSERT reader. It never executes SQL from a file. */
public final class MigrationDataFile {
    public static final int MAX_LINE_CHARS=1024*1024;
    private static final BigInteger MASK=BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    @FunctionalInterface public interface RowConsumer { void accept(List<Object> row) throws Exception; }
    public record Statistics(long rows,List<Long> nulls,String multisetDigest) {
        public Statistics { nulls=List.copyOf(nulls); }
    }
    public static final class Accumulator {
        private long rows;
        private final long[] nulls;
        private BigInteger sum=BigInteger.ZERO;
        public Accumulator(int columns) { nulls=new long[columns]; }
        public void add(List<Object> row) throws IOException {
            if(row.size()!=nulls.length) throw new IOException("Column count changed");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(DataOutputStream out=new DataOutputStream(bytes)) {
                for(int i=0;i<row.size();i++) {
                    Object value=row.get(i);
                    if(value==null) { nulls[i]++; out.writeByte(0); continue; }
                    String canonical;
                    if(value instanceof BigDecimal number) { out.writeByte(1); canonical=number.signum()==0 ? "0" : number.stripTrailingZeros().toPlainString(); }
                    else if(value instanceof String text) { out.writeByte(2); canonical=text; }
                    else throw new IOException("Unsupported comparison value");
                    byte[] encoded=canonical.getBytes(StandardCharsets.UTF_8); out.writeInt(encoded.length); out.write(encoded);
                }
            }
            sum=sum.add(new BigInteger(1,MigrationFiles.digest().digest(bytes.toByteArray()))).and(MASK);
            rows=Math.incrementExact(rows);
        }
        public Statistics finish() { return new Statistics(rows,Arrays.stream(nulls).boxed().toList(),String.format(Locale.ROOT,"%064x",sum)); }
    }
    private MigrationDataFile() { }

    public static Statistics read(MigrationFiles.Snapshot input,MigrationPlan.Table table,
                                  MigrationCancellation cancellation,RowConsumer consumer) throws Exception {
        MigrationFiles.verify(input,cancellation);
        var digest=MigrationFiles.digest(); Accumulator stats=new Accumulator(table.columns().size());
        CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try(InputStream stream=Files.newInputStream(input.path(),LinkOption.NOFOLLOW_LINKS);
            DigestInputStream hashed=new DigestInputStream(stream,digest);
            Reader reader=new BufferedReader(new InputStreamReader(hashed,decoder))) {
            StringBuilder line=new StringBuilder(); int ch; long characters=0;
            while((ch=reader.read())!=-1) {
                if((characters & 4095)==0)cancellation.checkCancelled();
                if(++characters>MigrationFiles.MAX_FILE_BYTES) throw new IOException("Data file exceeds limit");
                if(ch=='\n') { consume(line.toString(),table,stats,consumer); line.setLength(0); }
                else { if(line.length()>=MAX_LINE_CHARS) throw new IOException("Data row exceeds limit"); line.append((char)ch); }
            }
            consume(line.toString(),table,stats,consumer);
        }
        if(!input.sha256().equals(HexFormat.of().formatHex(digest.digest()))) throw new IOException("Data file changed during import");
        MigrationFiles.verify(input,cancellation);
        cancellation.checkCancelled(); return stats.finish();
    }
    private static void consume(String line,MigrationPlan.Table table,Accumulator stats,RowConsumer consumer) throws Exception {
        String trimmed=line.trim();
        if(trimmed.isEmpty() || trimmed.startsWith("--") || trimmed.equalsIgnoreCase("COMMIT;")) return;
        List<Object> row=parse(trimmed,table); stats.add(row); consumer.accept(row);
    }
    static List<Object> parse(String line,MigrationPlan.Table table) throws IOException {
        Parser p=new Parser(line); p.word("INSERT"); p.word("INTO");
        if(!p.identifier().equals(table.targetName())) throw new IOException("Unexpected data table");
        p.character('(');
        for(int i=0;i<table.columns().size();i++) { if(i>0)p.character(','); if(!p.identifier().equals(table.columns().get(i).targetName())) throw new IOException("Unexpected data column"); }
        p.character(')'); p.word("VALUES"); p.character('('); List<Object> values=new ArrayList<>();
        for(int i=0;i<table.columns().size();i++) {
            if(i>0)p.character(','); Object value=p.value(); MigrationPlan.Column column=table.columns().get(i);
            if(value==null && !column.nullable()) throw new IOException("NULL in required column");
            if(value!=null && (column.numeric() != (value instanceof BigDecimal))) throw new IOException("Data type does not match reviewed mapping");
            values.add(value);
        }
        p.character(')'); p.character(';'); p.space();
        if(p.at!=p.text.length()) throw new IOException("Trailing SQL is not allowed");
        return Collections.unmodifiableList(values);
    }
    /** Produces only the grammar accepted above; no lossy LOB/time/boolean fallback. */
    public static String format(String table,List<MigrationPlan.Column> columns,List<Object> values) throws IOException {
        if(!MigrationPreflight.simpleName(table) || columns.size()!=values.size()) throw new IOException("Unsupported export shape");
        StringBuilder line=new StringBuilder("INSERT INTO ").append(MigrationPreflight.quote(table)).append(" (");
        for(int i=0;i<columns.size();i++) { if(i>0)line.append(", "); line.append(MigrationPreflight.quote(columns.get(i).targetName())); }
        line.append(") VALUES (");
        for(int i=0;i<values.size();i++) {
            if(i>0)line.append(", "); Object value=values.get(i);
            if(value==null)line.append("NULL");
            else if(value instanceof BigDecimal number) { if(Math.abs((long)number.scale())>1000 || number.precision()>1000) throw new IOException("Numeric value exceeds limit"); line.append(number.toPlainString()); }
            else if(value instanceof String text) {
                if(text.indexOf(0)>=0) throw new IOException("PostgreSQL text cannot represent NUL");
                line.append("E'").append(text.replace("\\","\\\\").replace("'","''").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")).append("'");
            } else throw new IOException("Unsupported export value");
        }
        line.append(");"); if(line.length()>MAX_LINE_CHARS) throw new IOException("Data row exceeds limit"); return line.toString();
    }
    static List<Object> jdbcRow(ResultSet result,List<MigrationPlan.Column> columns) throws SQLException {
        List<Object> row=new ArrayList<>();
        for(int i=0;i<columns.size();i++) row.add(columns.get(i).numeric() ? result.getBigDecimal(i+1) : result.getString(i+1));
        return row;
    }
    private static final class Parser {
        final String text; int at;
        Parser(String text) { this.text=text; }
        void space() { while(at<text.length() && Character.isWhitespace(text.charAt(at)))at++; }
        void character(char expected) throws IOException { space(); if(at>=text.length() || text.charAt(at++)!=expected)throw new IOException("Unsupported data syntax"); }
        void word(String expected) throws IOException { space();if(at<text.length() && text.charAt(at)=='"' || !identifier().equals(expected.toLowerCase(Locale.ROOT)))throw new IOException("Unsupported data statement"); }
        String identifier() throws IOException {
            space(); boolean quoted=at<text.length() && text.charAt(at)=='"';if(quoted)at++;
            int start=at; while(at<text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at)=='_'))at++;
            String value=text.substring(start,at);if(quoted){if(at>=text.length() || text.charAt(at++)!='"')throw new IOException("Unsupported quoted identifier");}
            if(!MigrationPreflight.simpleName(value))throw new IOException("Unsupported data identifier");return quoted?value:value.toLowerCase(Locale.ROOT);
        }
        Object value() throws IOException {
            space(); if(at>=text.length())throw new IOException("Missing data value");
            boolean escaped=(text.charAt(at)=='E' || text.charAt(at)=='e') && at+1<text.length() && text.charAt(at+1)=='\'';
            if(escaped)at++;
            if(text.charAt(at)=='\'') {
                at++; StringBuilder value=new StringBuilder(); boolean closed=false;
                while(at<text.length()) {
                    char ch=text.charAt(at++);
                    if(ch=='\'') { if(at<text.length() && text.charAt(at)=='\'') { at++; value.append('\''); } else { closed=true; break; } }
                    else if(ch=='\\') {
                        if(!escaped || at>=text.length())throw new IOException("Ambiguous legacy string; regenerate export");
                        char escape=text.charAt(at++); value.append(switch(escape) {case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case '\\' -> '\\'; default -> throw new IOException("Unsupported string escape");});
                    } else { if(ch==0)throw new IOException("NUL is not supported"); value.append(ch); }
                }
                if(!closed)throw new IOException("Unclosed string"); return value.toString();
            }
            int start=at; while(at<text.length() && text.charAt(at)!=',' && text.charAt(at)!=')')at++;
            String raw=text.substring(start,at).trim(); if(raw.equalsIgnoreCase("NULL"))return null;
            if(raw.length()>1024 || !raw.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))throw new IOException("Only literal values are allowed");
            try { BigDecimal number=new BigDecimal(raw); if(Math.abs((long)number.scale())>1000 || number.precision()>1000)throw new NumberFormatException(); return number; }
            catch(NumberFormatException error) { throw new IOException("Numeric value exceeds limit"); }
        }
    }
}
