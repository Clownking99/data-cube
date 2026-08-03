package com.datacube.redis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RespClientTest {

    @Test
    void performsAclAuthAndSelectBeforeFirstCommand() throws Exception {
        try (ScriptedServer server = new ScriptedServer("+OK\r\n", "+OK\r\n", "+PONG\r\n");
             RespClient client = new RespClient("127.0.0.1", server.port(), "alice", "secret", 3)) {

            assertArrayEquals("PONG".getBytes(UTF_8), (byte[]) client.call("PING"));

            assertEquals(List.of(
                    List.of("AUTH", "alice", "secret"),
                    List.of("SELECT", "3"),
                    List.of("PING")), server.awaitCommands(3));
        }
    }

    @Test
    void performsPasswordOnlyAuthAndSkipsSelectForDbZero() throws Exception {
        try (ScriptedServer server = new ScriptedServer("+OK\r\n", "+PONG\r\n");
             RespClient client = new RespClient("127.0.0.1", server.port(), "", "secret", 0)) {

            client.call("PING");

            assertEquals(List.of(List.of("AUTH", "secret"), List.of("PING")), server.awaitCommands(2));
        }
    }

    @Test
    void skipsAuthenticationWhenPasswordIsEmpty() throws Exception {
        try (ScriptedServer server = new ScriptedServer("+PONG\r\n");
             RespClient client = new RespClient("127.0.0.1", server.port(), "ignored", "", 0)) {

            client.call("PING");

            assertEquals(List.of(List.of("PING")), server.awaitCommands(1));
        }
    }

    @Test
    void preservesServerErrors() throws Exception {
        try (ScriptedServer server = new ScriptedServer("-NOAUTH Authentication required\r\n");
             RespClient client = new RespClient("127.0.0.1", server.port(), "", "", 0)) {

            RedisException error = assertThrows(RedisException.class, () -> client.call("GET", "x"));

            assertEquals("NOAUTH Authentication required", error.getMessage());
        }
    }

    @Test
    void rejectsCallsAfterClose() throws Exception {
        try (ScriptedServer server = new ScriptedServer()) {
            RespClient client = new RespClient("127.0.0.1", server.port(), "", "", 0);
            client.close();

            RedisException error = assertThrows(RedisException.class, () -> client.call("PING"));

            assertTrue(error.getMessage().contains("closed"));
        }
    }

    @Test
    void reconnectsAndRetriesOnceAfterTransportDisconnect() throws Exception {
        try (RetryServer server = new RetryServer();
             RespClient client = new RespClient("127.0.0.1", server.port(), "", "", 0)) {

            assertArrayEquals("PONG".getBytes(UTF_8), (byte[]) client.call("PING"));

            assertTrue(server.done.await(2, TimeUnit.SECONDS));
            assertEquals(2, server.connections);
        }
    }

    @Test
    void doesNotReplayNonIdempotentCommandAfterUncertainDisconnect() throws Exception {
        try (RetryServer server = new RetryServer();
             RespClient client = new RespClient("127.0.0.1", server.port(), "", "", 0)) {

            RedisException error = assertThrows(RedisException.class, () -> client.call("INCR", "counter"));

            assertTrue(error.getMessage().contains("result is uncertain"));
            assertTrue(server.done.await(2, TimeUnit.SECONDS));
            assertEquals(1, server.connections);
        }
    }

    private static final class ScriptedServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final List<String> responses;
        private final List<List<String>> commands = new ArrayList<>();
        private final CountDownLatch done = new CountDownLatch(1);

        ScriptedServer(String... responses) throws IOException {
            this.server = new ServerSocket(0);
            this.responses = List.of(responses);
            this.thread = new Thread(this::serve, "RespClientTest-Server");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        List<List<String>> awaitCommands(int count) throws InterruptedException {
            assertTrue(done.await(2, TimeUnit.SECONDS), "fake server did not finish");
            assertEquals(count, commands.size());
            return List.copyOf(commands);
        }

        private void serve() {
            try (Socket socket = server.accept()) {
                for (String response : responses) {
                    List<?> raw = assertInstanceOf(List.class, RespCodec.decode(socket.getInputStream()));
                    List<String> command = raw.stream()
                            .map(v -> new String((byte[]) v, StandardCharsets.UTF_8))
                            .toList();
                    synchronized (commands) {
                        commands.add(command);
                    }
                    socket.getOutputStream().write(response.getBytes(UTF_8));
                    socket.getOutputStream().flush();
                }
            } catch (Throwable error) {
                // The awaiting assertion exposes missing commands; avoid hiding the client failure here.
            } finally {
                done.countDown();
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
            thread.join(2_000);
        }
    }

    private static final class RetryServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile int connections;

        RetryServer() throws IOException {
            server = new ServerSocket(0);
            server.setSoTimeout(700);
            thread = new Thread(this::serve, "RespClientTest-RetryServer");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            try {
                try (Socket first = server.accept()) {
                    connections++;
                    RespCodec.decode(first.getInputStream());
                    // Close without a response to force a client-side EOF.
                }
                try (Socket second = server.accept()) {
                    connections++;
                    RespCodec.decode(second.getInputStream());
                    second.getOutputStream().write("+PONG\r\n".getBytes(UTF_8));
                    second.getOutputStream().flush();
                }
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
            thread.join(2_000);
        }
    }
}
