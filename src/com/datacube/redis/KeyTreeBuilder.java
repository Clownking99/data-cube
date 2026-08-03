package com.datacube.redis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** 将 Redis 键按可配置分隔符聚合为稳定、有序的前缀树。 */
public final class KeyTreeBuilder {

    public record Node(String segment, String fullKey, List<Node> children, int keyCount) {}

    private KeyTreeBuilder() {}

    public static Node build(List<String> keys, String separator) {
        MutableNode root = new MutableNode("");
        String delimiter = separator == null ? "" : separator;
        for (String key : new LinkedHashSet<>(keys == null ? List.of() : keys)) {
            if (key == null) continue;
            String[] parts = delimiter.isEmpty()
                    ? new String[]{key}
                    : key.split(Pattern.quote(delimiter), -1);
            MutableNode current = root;
            for (String part : parts) current = current.children.computeIfAbsent(part, MutableNode::new);
            current.fullKey = key;
        }
        return freeze(root);
    }

    private static Node freeze(MutableNode source) {
        List<Node> children = new ArrayList<>(source.children.size());
        int count = source.fullKey == null ? 0 : 1;
        for (MutableNode child : source.children.values()) {
            Node frozen = freeze(child);
            children.add(frozen);
            count += frozen.keyCount();
        }
        return new Node(source.segment, source.fullKey, List.copyOf(children), count);
    }

    private static final class MutableNode {
        private final String segment;
        private final Map<String, MutableNode> children = new TreeMap<>();
        private String fullKey;

        private MutableNode(String segment) {
            this.segment = segment;
        }
    }
}
