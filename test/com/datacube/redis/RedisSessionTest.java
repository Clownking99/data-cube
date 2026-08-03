package com.datacube.redis;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RedisSessionTest {

    @Test
    void scanConvertsCursorAndPreservesBinaryKeys() {
        RecordingExecutor executor = new RecordingExecutor(List.of(bytes("17"), List.of(bytes("a"), new byte[]{0, -1})));
        RedisSession session = new RedisSession(executor, () -> {});

        RedisSession.ScanPage page = session.scan(0, "user:*", 500);

        assertEquals(17, page.cursor());
        assertArrayEquals(bytes("a"), page.values().get(0));
        assertArrayEquals(new byte[]{0, -1}, page.values().get(1));
        assertEquals(List.of("SCAN", "0", "MATCH", "user:*", "COUNT", "500"), executor.utf8Command(0));
    }

    @Test
    void stringCommandsKeepValueBytesUnchanged() {
        byte[] binary = new byte[]{0, -1, 1};
        RecordingExecutor executor = new RecordingExecutor(bytes("OK"), binary, 3L, binary);
        RedisSession session = new RedisSession(executor, () -> {});

        session.set("blob", binary);
        byte[] loaded = session.get("blob");

        assertArrayEquals(binary, executor.commands.get(0).get(2));
        assertArrayEquals(binary, loaded);
        assertEquals(List.of("GET", "blob"), executor.utf8Command(1));
        assertEquals(3, session.strlen("blob"));
        assertArrayEquals(binary, session.getrange("blob", 0, 2));
        assertEquals(List.of("GETRANGE", "blob", "0", "2"), executor.utf8Command(3));
    }

    @Test
    void exposesKeyMetadataAndMutations() {
        RecordingExecutor executor = new RecordingExecutor(
                bytes("string"), 60L, 1L, 1L, 1L, bytes("OK"), 3L, 12L, bytes("# Keyspace\r\ndb0:keys=12\r\n"));
        RedisSession session = new RedisSession(executor, () -> {});

        assertEquals("string", session.type("k"));
        assertEquals(60, session.ttl("k"));
        assertTrue(session.expire("k", 60));
        assertTrue(session.persist("k"));
        assertTrue(session.del("k"));
        session.rename("k", "next");
        assertTrue(session.exists("next"));
        assertEquals(12, session.dbsize());
        assertTrue(session.info("keyspace").contains("db0"));
    }

    @Test
    void convertsHashAndSortedSetScanPairs() {
        RecordingExecutor executor = new RecordingExecutor(
                List.of(bytes("0"), List.of(bytes("f1"), bytes("v1"), bytes("f2"), new byte[]{-1})),
                List.of(bytes("9"), List.of(bytes("m1"), bytes("1.25"), bytes("m2"), bytes("-2"))));
        RedisSession session = new RedisSession(executor, () -> {});

        RedisSession.HashScanPage hashes = session.hscan("h", 0, 20);
        RedisSession.ZScanPage scores = session.zscan("z", 0, 20);

        assertEquals(2, hashes.entries().size());
        assertArrayEquals(new byte[]{-1}, hashes.entries().get(1).value());
        assertEquals(9, scores.cursor());
        assertEquals(1.25, scores.entries().get(0).score());
        assertArrayEquals(bytes("m2"), scores.entries().get(1).member());
    }

    @Test
    void rejectsMalformedPairScanResponses() {
        RecordingExecutor executor = new RecordingExecutor(
                List.of(bytes("0"), List.of(bytes("field-without-value"))),
                List.of(bytes("0"), List.of(bytes("member-without-score"))));
        RedisSession session = new RedisSession(executor, () -> {});

        assertThrows(RedisException.class, () -> session.hscan("h", 0, 20));
        assertThrows(RedisException.class, () -> session.zscan("z", 0, 20));
    }

    @Test
    void emitsExactCollectionMutationCommands() {
        RecordingExecutor executor = new RecordingExecutor(1L, 1L, 2L, 3L, bytes("OK"), 1L, 1L, 1L, 1L, 1L, 4L);
        RedisSession session = new RedisSession(executor, () -> {});
        byte[] value = new byte[]{0, -1};

        assertTrue(session.hset("h", bytes("f"), value));
        assertTrue(session.hdel("h", bytes("f")));
        assertEquals(2, session.lpush("l", value));
        assertEquals(3, session.rpush("l", value));
        session.lset("l", 1, value);
        assertEquals(1, session.lrem("l", 1, value));
        assertTrue(session.sadd("s", value));
        assertTrue(session.srem("s", value));
        assertTrue(session.zadd("z", 1.5, value));
        assertTrue(session.zrem("z", value));
        assertEquals(4, session.zcard("z"));

        assertEquals(List.of("LSET", "l", "1", new String(value, StandardCharsets.ISO_8859_1)),
                executor.isoCommand(4));
        assertEquals(List.of("ZADD", "z", "1.5", new String(value, StandardCharsets.ISO_8859_1)),
                executor.isoCommand(8));
    }

    @Test
    void rawAndCloseDelegateToOwnedResources() {
        RecordingExecutor executor = new RecordingExecutor(List.of(1L, bytes("x")));
        boolean[] closed = {false};
        RedisSession session = new RedisSession(executor, () -> closed[0] = true);

        assertInstanceOf(List.class, session.raw("COMMAND", "DOCS"));
        session.close();

        assertEquals(List.of("COMMAND", "DOCS"), executor.utf8Command(0));
        assertTrue(closed[0]);
        RedisException error = assertThrows(RedisException.class, () -> session.raw("PING"));
        assertTrue(error.getMessage().contains("closed"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(UTF_8);
    }

    private static final class RecordingExecutor implements RedisCommandExecutor {
        private final ArrayDeque<Object> responses = new ArrayDeque<>();
        private final List<List<byte[]>> commands = new ArrayList<>();

        RecordingExecutor(Object... responses) {
            for (Object response : responses) this.responses.add(response);
        }

        @Override
        public Object callBytes(byte[]... args) {
            commands.add(List.of(args));
            return responses.removeFirst();
        }

        List<String> utf8Command(int index) {
            return commands.get(index).stream().map(v -> new String(v, UTF_8)).toList();
        }

        List<String> isoCommand(int index) {
            return commands.get(index).stream().map(v -> new String(v, StandardCharsets.ISO_8859_1)).toList();
        }
    }
}
