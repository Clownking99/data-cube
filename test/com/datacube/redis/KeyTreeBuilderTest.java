package com.datacube.redis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeyTreeBuilderTest {

    @Test
    void groupsNestedKeysAndCountsDescendants() {
        KeyTreeBuilder.Node root = KeyTreeBuilder.build(List.of("user:2:name", "user:1", "order:9"), ":");

        assertEquals(List.of("order", "user"), root.children().stream().map(KeyTreeBuilder.Node::segment).toList());
        KeyTreeBuilder.Node users = root.children().get(1);
        assertEquals(2, users.keyCount());
        assertEquals(List.of("1", "2"), users.children().stream().map(KeyTreeBuilder.Node::segment).toList());
    }

    @Test
    void preservesKeyThatIsAlsoFolderPrefix() {
        KeyTreeBuilder.Node root = KeyTreeBuilder.build(List.of("user", "user:name"), ":");

        KeyTreeBuilder.Node user = root.children().getFirst();
        assertEquals("user", user.fullKey());
        assertEquals("user:name", user.children().getFirst().fullKey());
        assertEquals(2, user.keyCount());
    }

    @Test
    void supportsCustomSeparatorEmptySegmentsAndDuplicates() {
        KeyTreeBuilder.Node root = KeyTreeBuilder.build(List.of("a//b", "a//b", "plain"), "/");

        assertEquals(2, root.keyCount());
        KeyTreeBuilder.Node a = root.children().getFirst();
        assertEquals("", a.children().getFirst().segment());
        assertEquals("a//b", a.children().getFirst().children().getFirst().fullKey());
    }

    @Test
    void emptySeparatorLeavesKeysFlat() {
        KeyTreeBuilder.Node root = KeyTreeBuilder.build(List.of("b:c", "a:c"), "");

        assertEquals(List.of("a:c", "b:c"), root.children().stream().map(KeyTreeBuilder.Node::segment).toList());
    }
}
