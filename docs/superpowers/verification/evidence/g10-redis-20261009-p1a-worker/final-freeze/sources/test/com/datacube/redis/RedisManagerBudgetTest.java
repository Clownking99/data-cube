package com.datacube.redis;

import com.datacube.config.CredentialCipher;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RedisManagerBudgetTest {
    private static ConnConfig config(String db) { return new ConnConfig("synth", "synth", DbType.REDIS, "127.0.0.1", 1, db, "", "", Map.of()); }
    @Test void terminalCachedPingNeverReopensButNextExplicitAcquireCan() {
        for (RedisException.Kind kind : new RedisException.Kind[]{RedisException.Kind.RESOURCE, RedisException.Kind.PROTOCOL, RedisException.Kind.DEADLINE, RedisException.Kind.NESTED_SERVER_ERROR, RedisException.Kind.SERVER, RedisException.Kind.CONTEXT}) {
            AtomicInteger opens = new AtomicInteger(), calls = new AtomicInteger(), closes = new AtomicInteger();
            RedisSessionManager manager = new RedisSessionManager(new CredentialCipher(), (config, db, password) -> {
                opens.incrementAndGet(); return new RedisSession(args -> {
                    if (calls.incrementAndGet() == 2) throw RedisException.rejected(kind, RedisException.Delivery.REPLIED);
                    return "PONG".getBytes(UTF_8);
                }, closes::incrementAndGet);
            });
            manager.register(config("0")); manager.acquire("synth");
            assertEquals(kind, assertThrows(RedisException.class, () -> manager.acquire("synth")).kind());
            assertEquals(1, opens.get()); assertEquals(1, closes.get()); assertFalse(manager.isConnected("synth"));
            manager.acquire("synth"); assertEquals(2, opens.get()); manager.closeAll(); assertEquals(2, closes.get());
        }
    }
    @Test void transportHealthCloseFailureIsPreservedAndStopsReplacement() {
        AtomicInteger opens = new AtomicInteger(), calls = new AtomicInteger();
        RuntimeException closing = new IllegalStateException("synthetic close failure");
        RedisSessionManager manager = new RedisSessionManager(new CredentialCipher(), (config, db, password) -> {
            opens.incrementAndGet(); return new RedisSession(args -> {
                if (calls.incrementAndGet() == 2) throw RedisException.rejected(RedisException.Kind.TRANSPORT, RedisException.Delivery.MAY_HAVE_SENT);
                return "PONG".getBytes(UTF_8);
            }, () -> { throw closing; });
        });
        manager.register(config("0")); manager.acquire("synth");
        RedisException failure = assertThrows(RedisException.class, () -> manager.acquire("synth"));
        assertEquals(RedisException.Kind.TRANSPORT, failure.kind()); assertArrayEquals(new Throwable[]{closing}, failure.getSuppressed());
        assertEquals(1, opens.get()); assertFalse(manager.isConnected("synth"));
    }
    @Test void closeAllAttemptsEverySnapshotAndPrioritizesErrorWithoutLosingFailures() {
        AtomicInteger closes = new AtomicInteger(), created = new AtomicInteger();
        RuntimeException first = new IllegalStateException("first"); Error second = new AssertionError("second");
        RuntimeException third = new IllegalStateException("third");
        RedisSessionManager manager = new RedisSessionManager(new CredentialCipher(), (config, db, password) -> {
            int number = created.getAndIncrement();
            return new RedisSession(args -> "PONG".getBytes(UTF_8), () -> { closes.incrementAndGet(); if (number == 0) throw first; if (number == 1) throw second; throw third; });
        });
        manager.register(config("0")); manager.acquire("synth"); manager.openSession("synth", 1); manager.openSession("synth", 2);
        assertSame(second, assertThrows(AssertionError.class, manager::closeAll));
        assertEquals(3, closes.get()); assertArrayEquals(new Throwable[]{first, third}, second.getSuppressed());
        assertFalse(manager.isConnected("synth")); manager.closeAll(); assertEquals(3, closes.get());
    }
    @Test void factoryReturningAfterCloseAllOrConfigChangeIsClosedAndNotPublished() throws Exception {
        for (boolean change : new boolean[]{false, true}) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger closes = new AtomicInteger(); AtomicReference<Throwable> result = new AtomicReference<>();
            RedisSessionManager manager = new RedisSessionManager(new CredentialCipher(), (config, db, password) -> {
                entered.countDown(); await(release); return new RedisSession(args -> "PONG".getBytes(UTF_8), closes::incrementAndGet);
            });
            manager.register(config("0"));
            Thread worker = Thread.ofVirtual().start(() -> { try { manager.openSession("synth", 0); result.set(new AssertionError("late open accepted")); } catch (Throwable error) { result.set(error); } });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                if (change) manager.register(config("1")); else manager.closeAll();
                release.countDown(); worker.join(2000); assertFalse(worker.isAlive()); assertEquals(1, closes.get());
                assertEquals(RedisException.Kind.CONTEXT, assertInstanceOf(RedisException.class, result.get()).kind());
                RedisSession fresh = manager.openSession("synth", 0); manager.closeIndependent(fresh); assertEquals(2, closes.get());
            } finally { release.countDown(); worker.join(2000); assertFalse(worker.isAlive()); manager.closeAll(); }
        }
    }
    private static void await(CountDownLatch latch) { try { assertTrue(latch.await(3, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }
}
