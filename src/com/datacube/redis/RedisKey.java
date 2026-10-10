package com.datacube.redis;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Immutable Redis bulk-string identity. Display projections never round-trip into commands. */
public final class RedisKey {
    private final byte[] raw;
    private final int hash;
    private final String text;

    private RedisKey(byte[] raw) {
        this.raw=raw.clone();
        hash=Arrays.hashCode(this.raw);
        var decoded=RedisDisplaySupport.text(this.raw,Math.max(1,this.raw.length),false);
        String candidate=decoded==null?null:decoded.text();
        // Newlines, format/bidi controls and invisible separators are not tree paths.
        text=candidate!=null && candidate.codePoints().allMatch(cp->!Character.isISOControl(cp)
                && Character.getType(cp)!=Character.FORMAT && cp!=0x2028 && cp!=0x2029)?candidate:null;
    }
    /** Factories reject a key exceeding the default request payload before copying/encoding.
     * The browser applies its smaller key/page budgets; RespCodec checks the entire command. */
    public static RedisKey of(byte[] raw) {
        Objects.requireNonNull(raw,"raw key");
        if(raw.length>RedisResourceLimits.DEFAULT.requestPayloadBytes()) throw RedisDisplaySupport.rejected();
        return new RedisKey(raw);
    }
    public static RedisKey utf8(String text) {
        Objects.requireNonNull(text,"key");
        RespCodec.utf8Length(text,RedisResourceLimits.DEFAULT.requestPayloadBytes());
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(++i>=text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("键名包含无效 Unicode");
            } else if(Character.isLowSurrogate(c)) throw new IllegalArgumentException("键名包含无效 Unicode");
        }
        return of(text.getBytes(StandardCharsets.UTF_8));
    }
    public int size() { return raw.length; }
    public byte[] bytes() { return raw.clone(); }
    /** Null means a non-text key; empty String is a valid, distinct key. */
    public String text() { return text; }
    public String display(int cap) {
        if(raw.length==0) return RedisDisplaySupport.label("⟦empty⟧",cap).text();
        if(text!=null) return RedisDisplaySupport.label(text.startsWith("⟦")?"⟦text⟧ "+text:text,cap).text();
        return RedisDisplaySupport.hex(raw,cap,"⟦hex "+raw.length+"B⟧ ","").text();
    }
    String treeLabel(int cap,int ordinal) {
        if(text!=null || raw.length==0) return display(cap);
        return RedisDisplaySupport.hex(raw,cap,"⟦hex#"+ordinal+" "+raw.length+"B⟧ ","").text();
    }
    static String textSegment(String segment,int cap) {
        return RedisDisplaySupport.label(segment.startsWith("⟦")?"⟦text⟧ "+segment:segment,cap).text();
    }
    /** Text remains literal; non-text copies as complete, explicitly labelled hex. */
    public String clipboardText(int cap) {
        if(text!=null) {
            if(text.length()>cap) throw RedisDisplaySupport.rejected();
            return text;
        }
        var hex=RedisDisplaySupport.hex(raw,cap,"hex:","");
        if(!hex.complete()) throw RedisDisplaySupport.rejected();
        return hex.text();
    }
    @Override public boolean equals(Object other) { return other instanceof RedisKey key && Arrays.equals(raw,key.raw); }
    @Override public int hashCode() { return hash; }
    @Override public String toString() { return display(128); }
}
