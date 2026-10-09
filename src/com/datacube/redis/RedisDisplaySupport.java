package com.datacube.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure bounded projections. Raw identities are never obtained from display text. */
public final class RedisDisplaySupport {
    public record Preview(String text, boolean complete) {}
    public enum Mode {
        TEXT("文本"), HEX("十六进制"), JSON("JSON 美化");
        private final String label;
        Mode(String label) { this.label=label; }
        @Override public String toString() { return label; }
    }
    public record StringViews(byte[] raw, boolean readComplete, Preview text, Preview hex, Preview json, boolean printable) {
        public Preview view(Mode mode) { return switch (mode) { case TEXT -> text; case HEX -> hex; case JSON -> json; }; }
        public boolean editable(Mode mode) { return readComplete && view(mode).complete(); }
    }
    public record Row(String a, String b, byte[] rawA, byte[] rawB, long index) {}
    public record CollectionPage(List<Row> rows, long next, long length) {}
    private static final String OMITTED = " … [预览，已省略]";
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private RedisDisplaySupport() {}
    public static IllegalArgumentException rejected() { return new IllegalArgumentException("Redis 展示预算超限或内容无法安全展示；未完整加载，请缩小 MATCH/页面或重试"); }

    public static final class Bounded {
        private final int cap;
        private final StringBuilder out;
        private boolean truncated;
        public Bounded(int cap) { if (cap < 1) throw new IllegalArgumentException("Positive cap required"); this.cap=cap; out=new StringBuilder(Math.min(128,cap)); }
        public boolean full() { return truncated; }
        public void omit() { truncated=true; }
        public void append(char c) { if (out.length() < cap && !truncated) out.append(c); else truncated=true; }
        public void append(String value) { for (int i=0; i<value.length() && !truncated; i++) append(value.charAt(i)); }
        public void codePoint(int cp) {
            int count=Character.charCount(cp);
            if (count > cap-out.length() || truncated) { truncated=true; return; }
            out.appendCodePoint(cp);
        }
        public Preview finish() {
            if (!truncated) return new Preview(out.toString(),true);
            String mark=OMITTED.substring(0,Math.min(cap,OMITTED.length()));
            int keep=Math.min(out.length(),cap-mark.length());
            if (keep>0 && Character.isHighSurrogate(out.charAt(keep-1))) keep--;
            out.setLength(keep); out.append(mark);
            return new Preview(out.toString(),false);
        }
    }
    public static Preview label(String value, int cap) {
        Bounded out=new Bounded(cap); out.append(value==null ? "" : value); return out.finish();
    }
    /** Returns null for malformed UTF-8/control bytes, without decoding a large intermediate String. */
    public static Preview text(byte[] bytes, int cap, boolean printableOnly) {
        Bounded out=new Bounded(cap);
        for (int p=0; p<bytes.length;) {
            int first=bytes[p++]&255, cp, extra;
            if (first<128) { cp=first; extra=0; }
            else if (first>=0xc2 && first<=0xdf) { cp=first&31; extra=1; }
            else if (first>=0xe0 && first<=0xef) { cp=first&15; extra=2; }
            else if (first>=0xf0 && first<=0xf4) { cp=first&7; extra=3; }
            else return null;
            if (extra>bytes.length-p) return null;
            for (int i=0; i<extra; i++) { int b=bytes[p++]&255; if ((b&0xc0)!=0x80) return null; cp=(cp<<6)|(b&63); }
            if ((extra==1 && cp<128) || (extra==2 && cp<0x800) || (extra==3 && cp<0x10000)
                    || cp>0x10ffff || cp>=0xd800 && cp<=0xdfff) return null;
            if (printableOnly && Character.isISOControl(cp) && cp!='\n' && cp!='\r' && cp!='\t') return null;
            out.codePoint(cp); // Validate the rest even after the bounded output is full.
        }
        return out.finish();
    }
    public static Preview hex(byte[] bytes, int cap, String prefix, String delimiter) {
        Bounded out=new Bounded(cap); out.append(prefix);
        for (int i=0; i<bytes.length && !out.full(); i++) {
            if (i>0 || !prefix.isEmpty()) out.append(delimiter);
            int b=bytes[i]&255; out.append(HEX[b>>>4]); out.append(HEX[b&15]);
        }
        return out.finish();
    }
    public static Preview cell(byte[] bytes, int cap) {
        if (bytes==null) return label("(nil)",cap);
        Preview text=text(bytes,cap,true); return text!=null ? text : hex(bytes,cap,"0x","");
    }
    public static StringViews stringViews(byte[] raw, boolean complete, RedisDisplayLimits limits) {
        byte[] bytes=raw==null ? new byte[0] : raw;
        Preview text=text(bytes,limits.editorChars(),false);
        Preview json;
        if (text==null) { text=new Preview(label("无法按 UTF-8 完整显示，请选择十六进制",limits.editorChars()).text(),false); json=text; }
        else json=prettyJson(text,limits.editorChars(),limits.depth());
        return new StringViews(bytes,complete,text,hex(bytes,limits.editorChars(),""," "),json,text(bytes,1,true)!=null);
    }
    public static Preview prettyJson(Preview source, int cap, int depthCap) {
        Bounded out=new Bounded(cap); int indent=0; boolean quoted=false,escaped=false;
        String value=source.text();
        for (int i=0; i<value.length() && !out.full(); i++) {
            char c=value.charAt(i);
            if (quoted) { out.append(c); if (escaped) escaped=false; else if(c=='\\') escaped=true; else if(c=='"') quoted=false; continue; }
            switch(c) {
                case '"' -> { quoted=true; out.append(c); }
                case '{','[' -> { if(++indent>depthCap) { out.omit(); break; } out.append(c); out.append('\n'); indent(out,indent); }
                case '}',']' -> { out.append('\n'); indent=Math.max(0,indent-1); indent(out,indent); out.append(c); }
                case ',' -> { out.append(c); out.append('\n'); indent(out,indent); }
                case ':' -> out.append(": ");
                default -> { if (!Character.isWhitespace(c)) out.append(c); }
            }
        }
        if (!source.complete()) out.omit();
        return out.finish();
    }
    private static void indent(Bounded out,int depth) { for(int i=0;i<depth*2 && !out.full();i++) out.append(' '); }

    public static CollectionPage hash(RedisSession.HashScanPage page,RedisDisplayLimits limits) {
        rowCount(page.entries().size(),limits); PageBuilder rows=new PageBuilder(limits);
        for(var entry:page.entries()) rows.add(row(entry.field(),entry.value(),0,limits));
        return rows.finish(page.cursor(),-1);
    }
    public static CollectionPage set(RedisSession.ScanPage page,RedisDisplayLimits limits) {
        rowCount(page.values().size(),limits); PageBuilder rows=new PageBuilder(limits);
        for(byte[] member:page.values()) rows.add(row(member,null,0,limits));
        return rows.finish(page.cursor(),-1);
    }
    public static CollectionPage zset(RedisSession.ZScanPage page,RedisDisplayLimits limits) {
        rowCount(page.entries().size(),limits); PageBuilder rows=new PageBuilder(limits);
        for(var entry:page.entries()) rows.add(new Row(cell(entry.member(),limits.cellChars()).text(),
                label(Double.toString(entry.score()),limits.cellChars()).text(),entry.member(),null,0));
        return rows.finish(page.cursor(),-1);
    }
    public static CollectionPage list(List<byte[]> values,long offset,long length,RedisDisplayLimits limits) {
        rowCount(values.size(),limits); PageBuilder rows=new PageBuilder(limits);
        for(int i=0;i<values.size();i++) { long index=Math.addExact(offset,i); rows.add(new Row(label(Long.toString(index),limits.cellChars()).text(),cell(values.get(i),limits.cellChars()).text(),null,values.get(i),index)); }
        return rows.finish(Math.addExact(offset,values.size()),length);
    }
    private static Row row(byte[] a,byte[] b,long index,RedisDisplayLimits limits) { return new Row(cell(a,limits.cellChars()).text(), b==null ? "" : cell(b,limits.cellChars()).text(),a,b,index); }
    private static void rowCount(int count,RedisDisplayLimits limits) { if(count>limits.rows()) throw rejected(); }
    private static final class PageBuilder {
        final List<Row> rows=new ArrayList<>(0);
        int remaining;
        PageBuilder(RedisDisplayLimits limits) { remaining=limits.pageChars()-64; if(remaining<0) throw rejected(); }
        void add(Row row) {
            if(row.a().length()>remaining || row.b().length()>remaining-row.a().length()) throw rejected();
            remaining-=row.a().length()+row.b().length(); rows.add(row);
        }
        CollectionPage finish(long next,long length) { return new CollectionPage(List.copyOf(rows),next,length); }
    }

    public static byte[] valueBytes(String value,boolean hex,Object... prefix) {
        if (!hex) {
            Object[] args=Arrays.copyOf(prefix,prefix.length+1); args[prefix.length]=value;
            RespCodec.preflight(args,RedisResourceLimits.DEFAULT);
            return value.getBytes(StandardCharsets.UTF_8);
        }
        int nibbles=0;
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i); if(space(c)) continue;
            if(Character.digit(c,16)<0 || c>127) throw new IllegalArgumentException("十六进制格式无效");
            if(++nibbles>2*RedisResourceLimits.DEFAULT.requestPayloadBytes()) throw rejected();
        }
        if((nibbles&1)!=0) throw new IllegalArgumentException("十六进制格式无效");
        int[] sizes=RespCodec.preflight(prefix,RedisResourceLimits.DEFAULT);
        long payload=nibbles/2,frame=1L+Integer.toString(sizes.length+1).length()+2;
        for(int size:sizes) { payload+=size; frame+=1L+Integer.toString(size).length()+2+size+2; }
        frame+=1L+Integer.toString(nibbles/2).length()+2+nibbles/2+2;
        if(payload>RedisResourceLimits.DEFAULT.requestPayloadBytes() || frame>RedisResourceLimits.DEFAULT.requestFrameBytes()
                || prefix.length>=RedisResourceLimits.DEFAULT.arguments()) throw rejected();
        byte[] bytes=new byte[nibbles/2]; int p=0,high=-1;
        for(int i=0;i<value.length();i++) { char c=value.charAt(i); if(space(c)) continue; int digit=Character.digit(c,16); if(high<0) high=digit; else { bytes[p++]=(byte)(high*16+digit); high=-1; } }
        return bytes;
    }
    private static boolean space(char c) { return c==' ' || c>='\t' && c<='\r'; }
    public static void requireKey(String key,RedisDisplayLimits limits) { RespCodec.utf8Length(key,limits.singleKeyBytes()); }
}
