package com.datacube.redis;

import org.junit.jupiter.api.Test;
import com.datacube.config.CredentialCipher;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RespRecoveryTest {
    private static final RedisResourceLimits SMALL = RespBudgetTest.limits(8, 64, 32, 128, 20, 10, 4, 128, 256, 10, 1_000_000_000);
    private static RespClient client(Server server, String password, int db) { return new RespClient("127.0.0.1", server.port(), "", password, db, SMALL, System::nanoTime); }
    @Test void completeErrorRetainsSocketButNestedErrorDiscardsWithoutReplay() throws Exception {
        try (Server server = new Server(socket -> {
            command(socket, "GET", "k"); reply(socket, "-WRONGTYPE original detail\r\n");
            command(socket, "PING"); reply(socket, "+PONG\r\n");
            command(socket, "GET", "k"); reply(socket, "*2\r\n-SECRET key value\r\n+TAIL\r\n"); eof(socket);
        }, socket -> { command(socket, "PING"); reply(socket, "+PONG\r\n"); }); RespClient client = client(server, "", 0)) {
            RedisException ordinary = assertThrows(RedisException.class, () -> client.call("GET", "k"));
            assertEquals("WRONGTYPE original detail", ordinary.getMessage());
            assertEquals(RedisException.Kind.SERVER, ordinary.kind());
            assertArrayEquals("PONG".getBytes(UTF_8), (byte[]) client.call("PING"));
            RedisException nested = assertThrows(RedisException.class, () -> client.call("GET", "k"));
            assertEquals(RedisException.Kind.NESTED_SERVER_ERROR, nested.kind());
            assertFalse(nested.getMessage().contains("SECRET"));
            assertArrayEquals("PONG".getBytes(UTF_8), (byte[]) client.call("PING"));
            server.finished();
        }
    }
    @Test void authSelectAndBusinessBudgetFailuresDiscardAndRestoreOnNewExplicitCall() throws Exception {
        for (int stage = 0; stage < 3; stage++) {
            final int rejectedStage = stage;
            try (Server server = new Server(socket -> {
                command(socket, "AUTH", "synthetic");
                if (rejectedStage == 0) { reply(socket, "$9\r\n"); eof(socket); return; }
                reply(socket, "+OK\r\n"); command(socket, "SELECT", "2");
                if (rejectedStage == 1) { reply(socket, "$9\r\n"); eof(socket); return; }
                reply(socket, "+OK\r\n"); command(socket, "GET", "k"); reply(socket, "$9\r\n"); eof(socket);
            }, socket -> {
                command(socket, "AUTH", "synthetic"); reply(socket, "+OK\r\n");
                command(socket, "SELECT", "2"); reply(socket, "+OK\r\n");
                command(socket, "PING"); reply(socket, "+PONG\r\n");
            }); RespClient client = client(server, "synthetic", 2)) {
                RedisException error = assertThrows(RedisException.class, () -> client.call("GET", "k"));
                assertEquals(RedisException.Kind.RESOURCE, error.kind());
                assertEquals(stage < 2 ? RedisException.Delivery.NOT_SENT : RedisException.Delivery.MAY_HAVE_SENT, error.delivery());
                client.call("PING"); server.finished(); assertEquals(2, server.accepted);
            }
        }
    }
    @Test void protocolWriteFailureIsUncertainNotReplayedAndRecoversNextCall() throws Exception {
        try (Server server = new Server(socket -> {
            command(socket, "INCR", "counter"); reply(socket, ":secret\r\n"); eof(socket);
        }, socket -> { command(socket, "PING"); reply(socket, "+PONG\r\n"); }); RespClient client = client(server, "", 0)) {
            RedisException error = assertThrows(RedisException.class, () -> client.call("INCR", "counter"));
            assertEquals(RedisException.Kind.PROTOCOL, error.kind());
            assertEquals(RedisException.Delivery.MAY_HAVE_SENT, error.delivery());
            assertTrue(error.getMessage().contains("result is uncertain")); assertFalse(error.getMessage().contains("secret"));
            client.call("PING"); server.finished();
        }
    }
    @Test void confirmedTypedAndRawSelectSurviveSafeReadReconnect() throws Exception {
        for (boolean typed : List.of(true, false)) {
            try (Server server = new Server(socket -> {
                command(socket, "AUTH", "synthetic"); reply(socket, "+OK\r\n"); command(socket, "SELECT", "2"); reply(socket, "+OK\r\n");
                command(socket, typed ? "SELECT" : "select", typed ? "7" : "+0007"); reply(socket, "+OK\r\n"); command(socket, "GET", "k");
            }, socket -> {
                command(socket, "AUTH", "synthetic"); reply(socket, "+OK\r\n"); command(socket, "SELECT", "7"); reply(socket, "+OK\r\n");
                command(socket, "GET", "k"); reply(socket, "$1\r\nx\r\n");
            }); RespClient client = client(server, "synthetic", 2)) {
                RedisSession session = new RedisSession(client);
                if (typed) session.select(7); else session.raw("select", "+0007");
                assertArrayEquals("x".getBytes(UTF_8), session.get("k")); server.finished();
            }
        }
    }
    @Test void failedAndUnacknowledgedSelectDoNotReplayOrChangeConfirmedDb() throws Exception {
        for (boolean partial : List.of(false, true)) {
            try (Server server = new Server(socket -> {
                command(socket, "SELECT", "2"); reply(socket, "+OK\r\n"); command(socket, "SELECT", "7");
                if (partial) reply(socket, "+O"); else { reply(socket, "-ERR invalid DB\r\n"); command(socket, "GET", "k"); }
            }, socket -> { command(socket, "SELECT", "2"); reply(socket, "+OK\r\n"); command(socket, "GET", "k"); reply(socket, "$1\r\nx\r\n"); }); RespClient client = client(server, "", 2)) {
                RedisException error = assertThrows(RedisException.class, () -> client.call("SELECT", "7"));
                assertEquals(partial ? RedisException.Kind.TRANSPORT : RedisException.Kind.SERVER, error.kind());
                if (partial) assertEquals(RedisException.Delivery.MAY_HAVE_SENT, error.delivery());
                assertArrayEquals("x".getBytes(UTF_8), (byte[]) client.call("GET", "k")); server.finished();
            }
        }
    }
    @Test void rawModesRequireNewSessionAfterDisconnectInsteadOfOldAuthDbRestore() throws Exception {
        for (String mode : List.of("AUTH", "MULTI", "RESET")) {
            try (Server server = new Server(socket -> {
                command(socket, "SELECT", "2"); reply(socket, "+OK\r\n");
                switch (mode) {
                    case "AUTH" -> { command(socket, "AUTH", "new-synthetic"); reply(socket, "+OK\r\n"); }
                    case "RESET" -> { command(socket, "RESET"); reply(socket, "+RESET\r\n"); }
                    default -> {
                        command(socket, "MULTI"); reply(socket, "+OK\r\n"); command(socket, "SELECT", "7"); reply(socket, "+QUEUED\r\n");
                        command(socket, "EXEC"); reply(socket, "*1\r\n+OK\r\n");
                    }
                }
                command(socket, "GET", "k");
            }); RespClient client = client(server, "", 2)) {
                if (mode.equals("AUTH")) client.call("AUTH", "new-synthetic");
                else if (mode.equals("RESET")) client.call("RESET");
                else { client.call("MULTI"); client.call("SELECT", "7"); client.call("EXEC"); }
                assertEquals(RedisException.Kind.CONTEXT, assertThrows(RedisException.class, () -> client.call("GET", "k")).kind());
                assertEquals(RedisException.Kind.CONTEXT, assertThrows(RedisException.class, () -> client.call("PING")).kind());
                server.finished(); server.noMoreConnections();
            }
        }
    }
    @Test void unparseableSelectOkPermanentlyInvalidatesContext() throws Exception {
        try (Server server = new Server(socket -> { command(socket, "SELECT", "bad"); reply(socket, "+OK\r\n"); eof(socket); }); RespClient client = client(server, "", 0)) {
            assertEquals(RedisException.Kind.CONTEXT, assertThrows(RedisException.class, () -> client.call("SELECT", "bad")).kind());
            assertEquals(RedisException.Kind.CONTEXT, assertThrows(RedisException.class, () -> client.call("GET", "k")).kind());
            server.finished(); server.noMoreConnections();
        }
    }
    @Test void rejectedRequestSendsNothingIncludingAuthentication() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             RespClient client = new RespClient("127.0.0.1", listener.getLocalPort(), "", "synthetic", 2, SMALL, System::nanoTime)) {
            RedisException error = assertThrows(RedisException.class, () -> client.call("SET", "k", "x".repeat(129)));
            assertEquals(RedisException.Delivery.NOT_SENT, error.delivery());
            listener.setSoTimeout(100); assertThrows(SocketTimeoutException.class, listener::accept);
        }
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             RespClient client = new RespClient("127.0.0.1", listener.getLocalPort(), "", "x".repeat(129), 2, SMALL, System::nanoTime)) {
            assertEquals(RedisException.Delivery.NOT_SENT, assertThrows(RedisException.class, () -> client.call("PING")).delivery());
            listener.setSoTimeout(100); assertThrows(SocketTimeoutException.class, listener::accept);
        }
    }
    @Test void rejectedRequestPreservesHealthyConnectionAndRetryReusesImmutableBinaryFrame() throws Exception {
        try (Server server = new Server(socket -> {
            command(socket, "PING"); reply(socket, "+PONG\r\n"); command(socket, "PING"); reply(socket, "+PONG\r\n");
        }); RespClient client = client(server, "", 0)) {
            client.call("PING"); assertThrows(RedisException.class, () -> client.call("SET", "k", "x".repeat(129))); client.call("PING");
            server.finished(); assertEquals(1, server.accepted);
        }
        byte[][] args = {"GET".getBytes(UTF_8), "k".getBytes(UTF_8)};
        try (Server server = new Server(socket -> {
            command(socket, "GET", "k"); args[0][0] = 'S'; args[0][1] = 'E'; args[0][2] = 'T'; args[1] = "other".getBytes(UTF_8);
        }, socket -> { command(socket, "GET", "k"); reply(socket, "$1\r\nx\r\n"); }); RespClient client = client(server, "", 0)) {
            assertArrayEquals("x".getBytes(UTF_8), (byte[]) client.callBytes(args)); server.finished(); assertEquals(2, server.accepted);
        }
    }
    @Test void requestPolicyComesFromEncodedSnapshotEvenWhenCallerMutatesArguments() throws Exception {
        byte[][] args = {"INCR".getBytes(UTF_8), "counter".getBytes(UTF_8)};
        try (Server server = new Server(socket -> {
            command(socket, "INCR", "counter"); args[0] = "GET".getBytes(UTF_8);
        }); RespClient client = client(server, "", 0)) {
            assertEquals(RedisException.Kind.TRANSPORT, assertThrows(RedisException.class, () -> client.callBytes(args)).kind());
            server.finished(); server.noMoreConnections();
        }
    }
    @Test void expiredHandshakeDeadlineClosesPeerWithoutSendingBusiness() throws Exception {
        AtomicLong clock = new AtomicLong();
        CountDownLatch started = new CountDownLatch(1);
        long budget = 100_000_000;
        RedisResourceLimits limits = RespBudgetTest.limits(8, 64, 32, 128, 20, 10, 4, 128, 256, 10, budget);
        try (Server server = new Server(socket -> {
            command(socket, "AUTH", "synthetic"); assertTrue(started.await(2, TimeUnit.SECONDS));
            clock.set(budget); reply(socket, "+OK\r\n"); eof(socket);
        }); RespClient client = new RespClient("127.0.0.1", server.port(), "", "synthetic", 0, limits, () -> { long value = clock.get(); started.countDown(); return value; })) {
            RedisException error = assertThrows(RedisException.class, () -> client.call("PING"));
            assertEquals(RedisException.Kind.DEADLINE, error.kind());
            assertEquals(RedisException.Delivery.NOT_SENT, error.delivery());
            server.finished(); server.noMoreConnections();
        }
    }
    @Test void selectSharesAuthDeadlineAndReadRetryDoesNotStartNewDeadline() throws Exception {
        long budget = 100_000_000;
        RedisResourceLimits limits = RespBudgetTest.limits(8, 64, 32, 128, 20, 10, 4, 128, 256, 10, budget);
        AtomicLong clock = new AtomicLong();
        try (Server server = new Server(socket -> {
            command(socket, "AUTH", "synthetic"); reply(socket, "+OK\r\n");
            command(socket, "SELECT", "2"); clock.set(budget); reply(socket, "+OK\r\n"); eof(socket);
        }); RespClient client = new RespClient("127.0.0.1", server.port(), "", "synthetic", 2, limits, clock::get)) {
            assertEquals(RedisException.Kind.DEADLINE, assertThrows(RedisException.class, () -> client.call("PING")).kind());
            server.finished(); server.noMoreConnections();
        }
        clock.set(0);
        CountDownLatch reading = new CountDownLatch(1);
        try (Server server = new Server(socket -> {
            command(socket, "GET", "k"); assertTrue(reading.await(2, TimeUnit.SECONDS)); clock.set(budget);
        }, socket -> { command(socket, "PING"); reply(socket, "+PONG\r\n"); });
             RespClient client = new RespClient("127.0.0.1", server.port(), "", "", 0, limits, () -> { long value = clock.get(); reading.countDown(); return value; })) {
            assertEquals(RedisException.Kind.DEADLINE, assertThrows(RedisException.class, () -> client.call("GET", "k")).kind());
            client.call("PING"); server.finished(); assertEquals(2, server.accepted);
        }
    }
    @Test void closeDetachesBlockedReadAndSettlesClientWorker() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        try (Server server = new Server(socket -> { command(socket, "PING"); received.countDown(); eof(socket); }); RespClient client = client(server, "", 0)) {
            Thread worker = Thread.ofVirtual().start(() -> { try { client.call("PING"); result.set(new AssertionError("call succeeded after close")); } catch (Throwable failure) { result.set(failure); } });
            try {
                assertTrue(received.await(2, TimeUnit.SECONDS)); client.close(); worker.join(2000);
                assertFalse(worker.isAlive()); assertEquals(RedisException.Kind.CLOSED, assertInstanceOf(RedisException.class, result.get()).kind());
                server.finished(); server.noMoreConnections();
            } finally { client.close(); worker.join(2000); assertFalse(worker.isAlive()); }
        }
    }
    @Test void unexpectedClockErrorIsRethrownUnchangedAndIncompleteLeaseIsClosed() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        AssertionError original = new AssertionError("synthetic clock error");
        AtomicReference<AssertionError> injected = new AtomicReference<>();
        try (Server server = new Server(socket -> {
            command(socket, "GET", "k"); assertTrue(reading.await(2, TimeUnit.SECONDS));
            injected.set(original); reply(socket, "+PARTIAL"); eof(socket);
        }, socket -> { command(socket, "PING"); reply(socket, "+PONG\r\n"); });
             RespClient client = new RespClient("127.0.0.1", server.port(), "", "", 0, SMALL, () -> {
                 AssertionError error = injected.getAndSet(null); reading.countDown(); if (error != null) throw error; return 0;
             })) {
            assertSame(original, assertThrows(AssertionError.class, () -> client.call("GET", "k")));
            assertArrayEquals("PONG".getBytes(UTF_8), (byte[]) client.call("PING"));
            server.finished(); assertEquals(2, server.accepted);
        }
    }
    @Test void managerCloseAllReachesFirstAcquirePingSocketAndPreventsLateInstall() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        try (Server server = new Server(socket -> { command(socket, "PING"); received.countDown(); eof(socket); }); RespClient client = client(server, "", 0)) {
            RedisSessionManager manager = new RedisSessionManager(new CredentialCipher(), (config, db, password) -> new RedisSession(client));
            manager.register(new ConnConfig("synthetic", "synthetic", DbType.REDIS, "127.0.0.1", server.port(), "0", "", "", Map.of()));
            Thread worker = Thread.ofVirtual().start(() -> { try { manager.acquire("synthetic"); result.set(new AssertionError("late session installed")); } catch (Throwable error) { result.set(error); } });
            try {
                assertTrue(received.await(2, TimeUnit.SECONDS)); manager.closeAll(); worker.join(2000);
                assertFalse(worker.isAlive()); assertInstanceOf(RedisException.class, result.get()); assertFalse(manager.isConnected("synthetic"));
                server.finished(); server.noMoreConnections();
            } finally { manager.closeAll(); worker.join(2000); assertFalse(worker.isAlive()); }
        }
    }
    private static void command(Socket socket, String... expected) throws IOException {
        List<?> list = assertInstanceOf(List.class, RespCodec.decode(socket.getInputStream()));
        assertEquals(List.of(expected), list.stream().map(item -> new String((byte[]) item, UTF_8)).toList());
    }
    private static void reply(Socket socket, String response) throws IOException { socket.getOutputStream().write(response.getBytes(UTF_8)); socket.getOutputStream().flush(); }
    private static void eof(Socket socket) throws IOException { assertEquals(-1, socket.getInputStream().read(), "peer did not close rejected connection"); }
    @FunctionalInterface private interface Step { void run(Socket socket) throws Exception; }
    private static final class Server implements AutoCloseable {
        final ServerSocket listener;
        final Thread worker;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile int accepted;
        volatile Socket active;
        Server(Step... steps) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); listener.setSoTimeout(3000);
            worker = Thread.ofVirtual().name("G10-script-server").start(() -> {
                try {
                    for (Step step : steps) {
                        try (Socket socket = listener.accept()) { active = socket; accepted++; socket.setSoTimeout(3000); step.run(socket); }
                        finally { active = null; }
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { done.countDown(); }
            });
        }
        int port() { return listener.getLocalPort(); }
        void finished() throws InterruptedException { assertTrue(done.await(4, TimeUnit.SECONDS), "script not settled"); worker.join(1000); assertFalse(worker.isAlive()); if (failure.get() != null) throw new AssertionError("script failed", failure.get()); }
        void noMoreConnections() throws IOException { listener.setSoTimeout(100); assertThrows(SocketTimeoutException.class, listener::accept); }
        @Override public void close() throws Exception {
            listener.close(); Socket socket = active; if (socket != null) socket.close(); worker.join(4000); assertFalse(worker.isAlive(), "script worker leaked");
            if (failure.get() != null) throw new AssertionError("script failed", failure.get());
        }
    }
}
