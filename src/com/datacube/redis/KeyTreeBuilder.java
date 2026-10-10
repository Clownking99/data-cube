package com.datacube.redis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Stable prefix tree; checks precede splitting, node allocation and freezing. */
public final class KeyTreeBuilder {
    public record Node(String segment,RedisKey key,List<Node> children,int keyCount) {
        /** Compatibility projection for text-only callers; never use this as command identity. */
        public String fullKey() { return key==null?null:key.text(); }
    }
    private KeyTreeBuilder() {}
    public static Node build(List<String> keys,String separator) { return build(keys,separator,RedisDisplayLimits.DEFAULT); }
    public static Node build(List<String> keys,String separator,RedisDisplayLimits limits) {
        if(keys!=null && keys.size()>RedisResourceLimits.DEFAULT.arrayElements()) throw RedisDisplaySupport.rejected();
        List<RedisKey> identities=new AbstractList<>() {
            @Override public int size() { return keys==null?0:keys.size(); }
            @Override public RedisKey get(int index) {
                String key=keys.get(index); if(key==null) return null;
                RespCodec.utf8Length(key,limits.singleKeyBytes());
                return RedisKey.utf8(key);
            }
        };
        return buildKeys(identities,separator,limits);
    }
    public static Node buildKeys(List<RedisKey> keys,String separator,RedisDisplayLimits limits) {
        String delimiter=separator==null ? "" : separator;
        if(delimiter.length()>limits.separatorChars() || keys!=null && keys.size()>RedisResourceLimits.DEFAULT.arrayElements()) throw RedisDisplaySupport.rejected();
        Mutable root=new Mutable(""); int nodes=1; long bytes=0; LinkedHashSet<RedisKey> seen=new LinkedHashSet<>();
        for(RedisKey identity:keys==null ? List.<RedisKey>of() : keys) {
            if(identity==null || seen.contains(identity)) continue;
            int length=identity.size();
            if(length>limits.singleKeyBytes()) throw RedisDisplaySupport.rejected();
            if(seen.size()>=limits.keys() || length>limits.keyBytes()-bytes) throw RedisDisplaySupport.rejected();
            seen.add(identity); bytes+=length;
            String key=identity.text();
            if(key==null || key.isEmpty()) {
                if(nodes>=limits.treeNodes()) throw RedisDisplaySupport.rejected();
                nodes++; Mutable leaf=new Mutable(identity.treeLabel(limits.labelChars(),seen.size()));
                leaf.key=identity; root.rawLeaves.add(leaf); continue;
            }
            Mutable current=root; int from=0,depth=0;
            while(true) {
                if(++depth>limits.depth()) throw RedisDisplaySupport.rejected();
                int next=delimiter.isEmpty() ? -1 : key.indexOf(delimiter,from);
                String part=key.substring(from,next<0 ? key.length() : next);
                Mutable child=current.children.get(part);
                if(child==null) {
                    if(nodes>=limits.treeNodes()) throw RedisDisplaySupport.rejected();
                    nodes++; child=new Mutable(RedisKey.textSegment(part,limits.labelChars())); current.children.put(part,child);
                }
                current=child;
                if(next<0) break;
                from=next+delimiter.length();
            }
            current.key=identity;
        }
        ArrayDeque<Frame> stack=new ArrayDeque<>(); stack.push(new Frame(root));
        while(true) {
            Frame frame=stack.peek();
            if(frame.children.hasNext()) { stack.push(new Frame(frame.children.next())); continue; }
            Node frozen=new Node(frame.source.segment,frame.source.key,List.copyOf(frame.frozen),frame.count);
            stack.pop(); if(stack.isEmpty()) return frozen;
            stack.peek().frozen.add(frozen); stack.peek().count+=frozen.keyCount();
        }
    }
    private static final class Mutable {
        final String segment; final Map<String,Mutable> children=new TreeMap<>(); final List<Mutable> rawLeaves=new ArrayList<>(); RedisKey key;
        Mutable(String segment) { this.segment=segment; }
    }
    private static final class Frame {
        final Mutable source; final Iterator<Mutable> children; final List<Node> frozen=new ArrayList<>(0); int count;
        Frame(Mutable source) {
            this.source=source; List<Mutable> ordered=new ArrayList<>(source.children.values()); ordered.addAll(source.rawLeaves);
            children=ordered.iterator(); count=source.key==null ? 0 : 1;
        }
    }
}
