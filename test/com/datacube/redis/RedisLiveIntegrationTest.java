package com.datacube.redis;

import com.datacube.config.CredentialCipher;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 需要显式环境变量的真实 Redis 冒烟测试；默认构建安全跳过。 */
class RedisLiveIntegrationTest {

    @Test
    void standaloneRedisSupportsFiveTypesScanTtlAndLifecycle() {
        String host = System.getenv("DATACUBE_REDIS_HOST");
        String password = System.getenv("DATACUBE_REDIS_PASSWORD");
        Assumptions.assumeTrue(host != null && !host.isBlank() && password != null,
                "set DATACUBE_REDIS_HOST and DATACUBE_REDIS_PASSWORD to run live Redis smoke test");

        int port = Integer.parseInt(System.getenv().getOrDefault("DATACUBE_REDIS_PORT", "6379"));
        int database = Integer.parseInt(System.getenv().getOrDefault("DATACUBE_REDIS_DB", "0"));
        String username = System.getenv().getOrDefault("DATACUBE_REDIS_USERNAME", "");
        CredentialCipher cipher = new CredentialCipher();
        ConnConfig config = new ConnConfig("redis-live", "Redis live test", DbType.REDIS,
                host, port, Integer.toString(database), username, cipher.encrypt(password), Map.of());
        ConnectionManager manager = new ConnectionManager(cipher);
        String prefix = "datacube:smoke:" + UUID.randomUUID();
        List<String> keys = List.of(prefix + ":string", prefix + ":hash", prefix + ":list",
                prefix + ":set", prefix + ":zset");

        assertNull(manager.test(config), "ConnectionManager.test should authenticate and PING");
        manager.register(config);
        RedisSession session = manager.openRedisSession(config.id(), database);
        try {
            assertTrue(session.ping());

            byte[] binary = new byte[]{0, (byte) 0xff, 1};
            session.set(keys.get(0), binary);
            assertArrayEquals(binary, session.get(keys.get(0)));
            assertTrue(session.expire(keys.get(0), 60));
            assertTrue(session.ttl(keys.get(0)) > 0);

            assertTrue(session.hset(keys.get(1), bytes("field"), binary));
            assertTrue(allHashEntries(session, keys.get(1)).stream()
                    .anyMatch(entry -> java.util.Arrays.equals(binary, entry.value())));

            assertEquals(1, session.rpush(keys.get(2), bytes("first")));
            assertEquals(2, session.rpush(keys.get(2), binary));
            session.lset(keys.get(2), 0, bytes("updated"));
            assertArrayEquals(bytes("updated"), session.lrange(keys.get(2), 0, -1).getFirst());

            assertTrue(session.sadd(keys.get(3), binary));
            assertTrue(allSetValues(session, keys.get(3)).stream()
                    .anyMatch(value -> java.util.Arrays.equals(binary, value)));

            assertTrue(session.zadd(keys.get(4), 1.25, binary));
            assertTrue(allScores(session, keys.get(4)).stream()
                    .anyMatch(value -> value.score() == 1.25 && java.util.Arrays.equals(binary, value.member())));

            List<byte[]> scannedKeys = allKeyScan(session, prefix + ":*");
            assertEquals(5, scannedKeys.size());
            for (String key : keys) {
                assertTrue(session.del(key));
            }
            assertTrue(allKeyScan(session, prefix + ":*").isEmpty(), "smoke-test keys must be cleaned up");
        } finally {
            for (String key : keys) {
                try { session.del(key); } catch (RuntimeException ignored) {}
            }
            manager.closeRedisSession(session);
            manager.closeAll();
        }
    }

    private static List<byte[]> allKeyScan(RedisSession session, String pattern) {
        List<byte[]> out = new ArrayList<>();
        long cursor = 0;
        do {
            RedisSession.ScanPage page = session.scan(cursor, pattern, 100);
            out.addAll(page.values());
            cursor = page.cursor();
        } while (cursor != 0);
        return out;
    }

    private static List<RedisSession.HashEntry> allHashEntries(RedisSession session, String key) {
        List<RedisSession.HashEntry> out = new ArrayList<>();
        long cursor = 0;
        do {
            RedisSession.HashScanPage page = session.hscan(key, cursor, 100);
            out.addAll(page.entries());
            cursor = page.cursor();
        } while (cursor != 0);
        return out;
    }

    private static List<byte[]> allSetValues(RedisSession session, String key) {
        List<byte[]> out = new ArrayList<>();
        long cursor = 0;
        do {
            RedisSession.ScanPage page = session.sscan(key, cursor, 100);
            out.addAll(page.values());
            cursor = page.cursor();
        } while (cursor != 0);
        return out;
    }

    private static List<RedisSession.ScoredValue> allScores(RedisSession session, String key) {
        List<RedisSession.ScoredValue> out = new ArrayList<>();
        long cursor = 0;
        do {
            RedisSession.ZScanPage page = session.zscan(key, cursor, 100);
            out.addAll(page.entries());
            cursor = page.cursor();
        } while (cursor != 0);
        return out;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
