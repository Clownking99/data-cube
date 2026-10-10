package com.datacube.redis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Entire SCAN page candidate. The caller commits it only after installation succeeds. */
public record RedisKeySnapshot(Map<RedisKey,Integer> keys,long rawBytes,KeyTreeBuilder.Node tree,
                               long cursor,int database,String match,String separator) {
    public RedisKeySnapshot {
        keys=Collections.unmodifiableMap(new LinkedHashMap<>(keys));
    }
    public static RedisKeySnapshot candidate(RedisKeySnapshot previous,RedisSession.ScanPage page,
                                              int database,String match,String separator,RedisDisplayLimits limits) {
        if(match.length()>limits.matchChars() || separator.length()>limits.separatorChars()
                || page.values().size()>RedisResourceLimits.DEFAULT.arrayElements()) throw RedisDisplaySupport.rejected();
        if(previous!=null && (previous.database()!=database || !previous.match().equals(match) || !previous.separator().equals(separator))) throw RedisDisplaySupport.rejected();
        Map<RedisKey,Integer> keys=new LinkedHashMap<>(); long bytes=0;
        if(previous!=null) { keys.putAll(previous.keys()); bytes=previous.rawBytes(); }
        for(byte[] raw:page.values()) {
            if(raw==null || raw.length>limits.singleKeyBytes()) throw RedisDisplaySupport.rejected();
            RedisKey key=RedisKey.of(raw);
            if(keys.containsKey(key)) continue;
            if(keys.size()>=limits.keys() || raw.length>limits.keyBytes()-bytes) throw RedisDisplaySupport.rejected();
            bytes+=raw.length; keys.put(key,raw.length);
        }
        KeyTreeBuilder.Node tree=KeyTreeBuilder.buildKeys(List.copyOf(keys.keySet()),separator,limits);
        return new RedisKeySnapshot(keys,bytes,tree,page.cursor(),database,match,separator);
    }
}
