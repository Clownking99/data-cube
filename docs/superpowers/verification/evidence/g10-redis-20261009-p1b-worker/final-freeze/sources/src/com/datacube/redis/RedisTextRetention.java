package com.datacube.redis;

import java.util.ArrayDeque;
import java.util.List;

/** Independent count and UTF-16 retention budgets; UI owns matching node removal. */
public final class RedisTextRetention {
    private final int entries,chars;
    private final ArrayDeque<String> values=new ArrayDeque<>();
    private int used;
    public RedisTextRetention(int entries,int chars) { if(entries<1 || chars<1) throw new IllegalArgumentException(); this.entries=entries; this.chars=chars; }
    public int add(String text) {
        int removed=0;
        while(!values.isEmpty() && (values.size()>=entries || text.length()>chars-used)) { used-=values.removeFirst().length(); removed++; }
        if(text.length()<=chars) { values.addLast(text); used+=text.length(); }
        return removed;
    }
    public List<String> values() { return List.copyOf(values); }
    public int size() { return values.size(); }
    public int characters() { return used; }
}
