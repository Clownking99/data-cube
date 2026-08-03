package com.datacube.redis;

import com.datacube.config.CredentialCipher;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RedisSessionManagerTest {

    @Test
    void decryptsCredentialsCachesHealthySessionAndReleasesIt() {
        CredentialCipher cipher = new CredentialCipher();
        RecordingFactory factory = new RecordingFactory(List.of(bytes("PONG"), bytes("PONG")));
        RedisSessionManager manager = new RedisSessionManager(cipher, factory);
        ConnConfig config = redisConfig(cipher, "secret", "3");
        manager.register(config);

        RedisSession first = manager.acquire("redis-1");
        RedisSession second = manager.acquire("redis-1");

        assertSame(first, second);
        assertEquals(1, factory.opens.size());
        assertEquals("secret", factory.opens.getFirst().password());
        assertEquals(3, factory.opens.getFirst().database());
        assertTrue(manager.isConnected("redis-1"));
        manager.release("redis-1");
        assertFalse(manager.isConnected("redis-1"));
        assertEquals(1, factory.closeCounts.getFirst()[0]);
    }

    @Test
    void replacesSessionWhenHealthCheckFails() {
        CredentialCipher cipher = new CredentialCipher();
        RecordingFactory factory = new RecordingFactory(
                List.of(bytes("PONG"), new RedisException("connection lost")),
                List.of(bytes("PONG")));
        RedisSessionManager manager = new RedisSessionManager(cipher, factory);
        manager.register(redisConfig(cipher, "", "0"));

        RedisSession first = manager.acquire("redis-1");
        RedisSession second = manager.acquire("redis-1");

        assertNotSame(first, second);
        assertEquals(2, factory.opens.size());
        assertEquals(1, factory.closeCounts.getFirst()[0]);
    }

    @Test
    void opensIndependentSessionAtRequestedDatabase() {
        CredentialCipher cipher = new CredentialCipher();
        RecordingFactory factory = new RecordingFactory(List.of());
        RedisSessionManager manager = new RedisSessionManager(cipher, factory);
        manager.register(redisConfig(cipher, "secret", "0"));

        RedisSession session = manager.openSession("redis-1", 12);
        session.close();

        assertEquals(12, factory.opens.getFirst().database());
        assertEquals(1, factory.closeCounts.getFirst()[0]);
        assertFalse(manager.isConnected("redis-1"), "independent tabs are not manager cache entries");
    }

    @Test
    void closeAllAlsoClosesIndependentTabSessions() {
        CredentialCipher cipher = new CredentialCipher();
        RecordingFactory factory = new RecordingFactory(List.of(), List.of());
        RedisSessionManager manager = new RedisSessionManager(cipher, factory);
        manager.register(redisConfig(cipher, "", "0"));
        RedisSession first = manager.openSession("redis-1", 1);
        RedisSession second = manager.openSession("redis-1", 2);

        manager.closeAll();

        assertEquals(1, factory.closeCounts.get(0)[0]);
        assertEquals(1, factory.closeCounts.get(1)[0]);
        manager.closeIndependent(first);
        manager.closeIndependent(second);
        assertEquals(1, factory.closeCounts.get(0)[0], "closing remains idempotent");
    }

    @Test
    void testConnectionAlwaysClosesTemporarySessionAndReturnsMessage() {
        CredentialCipher cipher = new CredentialCipher();
        RecordingFactory successFactory = new RecordingFactory(List.of(bytes("PONG")));
        RedisSessionManager success = new RedisSessionManager(cipher, successFactory);

        assertNull(success.test(redisConfig(cipher, "secret", "2")));
        assertEquals(1, successFactory.closeCounts.getFirst()[0]);

        RecordingFactory failureFactory = new RecordingFactory(List.of(new RedisException("NOAUTH bad password")));
        RedisSessionManager failure = new RedisSessionManager(cipher, failureFactory);
        assertEquals("NOAUTH bad password", failure.test(redisConfig(cipher, "bad", "2")));
        assertEquals(1, failureFactory.closeCounts.getFirst()[0]);
    }

    @Test
    void redisModelHasExpectedDefaultsAndUrl() {
        ConnConfig config = new ConnConfig("id", "local", DbType.REDIS, "127.0.0.1", 6379,
                "4", "", "", Map.of());

        assertEquals(6379, DbType.REDIS.defaultPort());
        assertEquals("redis://127.0.0.1:6379/4", config.jdbcUrl());
    }

    private static ConnConfig redisConfig(CredentialCipher cipher, String password, String database) {
        return new ConnConfig("redis-1", "local", DbType.REDIS, "127.0.0.1", 6379,
                database, "alice", cipher.encrypt(password), Map.of());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(UTF_8);
    }

    private static final class RecordingFactory implements RedisSessionFactory {
        private final ArrayDeque<List<Object>> scripts = new ArrayDeque<>();
        private final List<Open> opens = new ArrayList<>();
        private final List<int[]> closeCounts = new ArrayList<>();

        @SafeVarargs
        RecordingFactory(List<Object>... scripts) {
            this.scripts.addAll(List.of(scripts));
        }

        @Override
        public RedisSession open(ConnConfig config, int database, String plainPassword) {
            opens.add(new Open(config, database, plainPassword));
            int[] closeCount = {0};
            closeCounts.add(closeCount);
            ArrayDeque<Object> responses = new ArrayDeque<>(scripts.removeFirst());
            return new RedisSession(args -> {
                Object response = responses.removeFirst();
                if (response instanceof RuntimeException error) throw error;
                return response;
            }, () -> closeCount[0]++);
        }
    }

    private record Open(ConnConfig config, int database, String password) {}
}
