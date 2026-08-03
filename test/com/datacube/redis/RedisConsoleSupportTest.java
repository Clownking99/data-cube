package com.datacube.redis;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedisConsoleSupportTest {

    @Test
    void tokenizesQuotesEscapesAndEmptyArguments() {
        assertEquals(List.of("SET", "a key", "say \"hi\"", "", "c\\d"),
                RedisConsoleSupport.tokenize("SET 'a key' \"say \\\"hi\\\"\" '' c\\\\d"));
    }

    @Test
    void rejectsUnterminatedQuotesAndDanglingEscape() {
        assertThrows(IllegalArgumentException.class, () -> RedisConsoleSupport.tokenize("GET 'broken"));
        assertThrows(IllegalArgumentException.class, () -> RedisConsoleSupport.tokenize("GET key\\"));
    }

    @Test
    void classifiesDangerousAndBlockingCommandsCaseInsensitively() {
        assertEquals(RedisConsoleSupport.CommandPolicy.CONFIRM,
                RedisConsoleSupport.policy(List.of("flushdb")));
        assertEquals(RedisConsoleSupport.CommandPolicy.CONFIRM,
                RedisConsoleSupport.policy(List.of("CONFIG", "set", "maxmemory", "1mb")));
        assertEquals(RedisConsoleSupport.CommandPolicy.NORMAL,
                RedisConsoleSupport.policy(List.of("CONFIG", "GET", "maxmemory")));
        assertEquals(RedisConsoleSupport.CommandPolicy.BLOCKED,
                RedisConsoleSupport.policy(List.of("xread", "COUNT", "1", "BLOCK", "0", "STREAMS", "s", "$")));
        assertEquals(RedisConsoleSupport.CommandPolicy.BLOCKED,
                RedisConsoleSupport.policy(List.of("BLPOP", "queue", "0")));
    }

    @Test
    void formatsAllRespShapesRecursively() {
        Object value = List.of(bytes("one"), 2L, List.of(bytes("nested"), 3L));

        String formatted = RedisConsoleSupport.format(value);

        assertTrue(formatted.contains("1) \"one\""));
        assertTrue(formatted.contains("2) (integer) 2"));
        assertTrue(formatted.contains("1) \"nested\""));
        assertEquals("(nil)", RedisConsoleSupport.format(null));
        assertEquals("(integer) 9", RedisConsoleSupport.format(9L));
    }

    @Test
    void formatsBinaryBulkAsHex() {
        assertEquals("(hex) 00 ff 01", RedisConsoleSupport.format(new byte[]{0, -1, 1}));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
