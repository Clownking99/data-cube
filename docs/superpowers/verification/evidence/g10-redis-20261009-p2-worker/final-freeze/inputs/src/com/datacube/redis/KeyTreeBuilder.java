package com.datacube.redis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Stable prefix tree; checks precede splitting, node allocation and freezing. */
public final class KeyTreeBuilder {
    public record Node(String segment,String fullKey,List<Node> children,int keyCount) {}
    private KeyTreeBuilder() {}
    public static Node build(List<String> keys,String separator) { return build(keys,separator,RedisDisplayLimits.DEFAULT); }
    public static Node build(List<String> keys,String separator,RedisDisplayLimits limits) {
        String delimiter=separator==null ? "" : separator;
        if(delimiter.length()>limits.separatorChars() || keys!=null && keys.size()>RedisResourceLimits.DEFAULT.arrayElements()) throw RedisDisplaySupport.rejected();
        Mutable root=new Mutable(""); int nodes=1; long bytes=0; LinkedHashSet<String> seen=new LinkedHashSet<>();
        for(String key:keys==null ? List.<String>of() : keys) {
            if(key==null || seen.contains(key)) continue;
            int length=RespCodec.utf8Length(key,limits.singleKeyBytes());
            if(seen.size()>=limits.keys() || length>limits.keyBytes()-bytes) throw RedisDisplaySupport.rejected();
            seen.add(key); bytes+=length;
            Mutable current=root; int from=0,depth=0;
            while(true) {
                if(++depth>limits.depth()) throw RedisDisplaySupport.rejected();
                int next=delimiter.isEmpty() ? -1 : key.indexOf(delimiter,from);
                String part=key.substring(from,next<0 ? key.length() : next);
                Mutable child=current.children.get(part);
                if(child==null) {
                    if(nodes>=limits.treeNodes()) throw RedisDisplaySupport.rejected();
                    nodes++; child=new Mutable(part); current.children.put(part,child);
                }
                current=child;
                if(next<0) break;
                from=next+delimiter.length();
            }
            current.fullKey=key;
        }
        ArrayDeque<Frame> stack=new ArrayDeque<>(); stack.push(new Frame(root));
        while(true) {
            Frame frame=stack.peek();
            if(frame.children.hasNext()) { stack.push(new Frame(frame.children.next())); continue; }
            Node frozen=new Node(frame.source.segment,frame.source.fullKey,List.copyOf(frame.frozen),frame.count);
            stack.pop(); if(stack.isEmpty()) return frozen;
            stack.peek().frozen.add(frozen); stack.peek().count+=frozen.keyCount();
        }
    }
    private static final class Mutable {
        final String segment; final Map<String,Mutable> children=new TreeMap<>(); String fullKey;
        Mutable(String segment) { this.segment=segment; }
    }
    private static final class Frame {
        final Mutable source; final Iterator<Mutable> children; final List<Node> frozen=new ArrayList<>(0); int count;
        Frame(Mutable source) { this.source=source; children=source.children.values().iterator(); count=source.fullKey==null ? 0 : 1; }
    }
}
