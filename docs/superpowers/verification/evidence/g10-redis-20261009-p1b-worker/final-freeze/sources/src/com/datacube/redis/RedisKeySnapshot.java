package com.datacube.redis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Entire SCAN page candidate. The caller commits it only after installation succeeds. */
public record RedisKeySnapshot(Map<String,Integer> keys,long rawBytes,KeyTreeBuilder.Node tree,
                               long cursor,int database,String match,String separator) {
    public static RedisKeySnapshot candidate(RedisKeySnapshot previous,RedisSession.ScanPage page,
                                              int database,String match,String separator,RedisDisplayLimits limits) {
        if(match.length()>limits.matchChars() || separator.length()>limits.separatorChars()
                || page.values().size()>RedisResourceLimits.DEFAULT.arrayElements()) throw RedisDisplaySupport.rejected();
        if(previous!=null && (previous.database()!=database || !previous.match().equals(match) || !previous.separator().equals(separator))) throw RedisDisplaySupport.rejected();
        Map<String,Integer> keys=new LinkedHashMap<>(); long bytes=0;
        if(previous!=null) { keys.putAll(previous.keys()); bytes=previous.rawBytes(); }
        for(byte[] raw:page.values()) {
            if(raw==null || raw.length>limits.singleKeyBytes()) throw RedisDisplaySupport.rejected();
            RedisDisplaySupport.Preview decoded=RedisDisplaySupport.text(raw,limits.singleKeyBytes(),false);
            if(decoded==null || !decoded.complete()) throw new IllegalArgumentException("键页包含无法按 UTF-8 往返的键；未完整加载，保留原结果");
            String key=decoded.text();
            if(keys.containsKey(key)) continue;
            if(keys.size()>=limits.keys() || raw.length>limits.keyBytes()-bytes) throw RedisDisplaySupport.rejected();
            bytes+=raw.length; keys.put(key,raw.length);
        }
        KeyTreeBuilder.Node tree=KeyTreeBuilder.build(List.copyOf(keys.keySet()),separator,limits);
        return new RedisKeySnapshot(Collections.unmodifiableMap(keys),bytes,tree,page.cursor(),database,match,separator);
    }
}
