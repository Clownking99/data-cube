package com.datacube.redis;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RedisSessionBudgetTest {
    @Test void allScanCursorsRoundTripUnsignedBits() {
        for (String cursor : List.of("9223372036854775808", "18446744073709551615")) {
            RedisSession session = new RedisSession(args -> {
                assertEquals(cursor, new String(args[1], UTF_8)); return List.of(cursor.getBytes(UTF_8), List.of());
            }, () -> {});
            long bits = Long.parseUnsignedLong(cursor); assertEquals(bits, session.scan(bits, "*", 500).cursor());
        }
        for (String cursor : List.of("18446744073709551616", "-1", "+1", "x")) {
            RedisSession session = new RedisSession(args -> List.of(cursor.getBytes(UTF_8), List.of()), () -> {});
            assertEquals(RedisException.Kind.PROTOCOL, assertThrows(RedisException.class, () -> session.scan(0, "*", 1)).kind());
        }
    }
    @Test void sessionRequestRejectedBeforeExecutorAndMetadataBeforeLargeText() {
        AtomicInteger calls = new AtomicInteger();
        RedisResourceLimits tiny = RespBudgetTest.limits(4, 4, 20, 64, 8, 4, 2, 8, 64, 4, 1000);
        RedisSession session = new RedisSession(args -> { calls.incrementAndGet(); return null; }, () -> {}, tiny);
        assertThrows(RedisException.class, () -> session.raw("SET", "key", "123")); assertEquals(0, calls.get());
        RedisSession metadata = new RedisSession(args -> new byte[129], () -> {});
        assertEquals(RedisException.Kind.RESOURCE, assertThrows(RedisException.class, () -> metadata.type("key")).kind());
        RedisSession info = new RedisSession(args -> new byte[64 * 1024 + 1], () -> {});
        assertEquals(RedisException.Kind.RESOURCE, assertThrows(RedisException.class, () -> info.info("keyspace")).kind());
        RedisSession lines = new RedisSession(args -> "\n".repeat(1024).getBytes(UTF_8), () -> {});
        assertThrows(RedisException.class, () -> lines.info("keyspace"));
    }
}
