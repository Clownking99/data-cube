package com.datacube.redis;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Loopback protocol script only: demonstrates the existing confirmed-SELECT reset. */
public final class RedisSelectRecoveryProbe {
    public static void main(String[] args) throws Exception {
        List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            listener.setSoTimeout(4000);
            Thread server = Thread.ofPlatform().start(() -> {
                try {
                    try (Socket first = listener.accept()) {
                        first.setSoTimeout(4000);
                        exchange(first, commands, "+OK\r\n"); // configured SELECT 2
                        exchange(first, commands, "+OK\r\n"); // user SELECT 7, confirmed
                        exchange(first, commands, null);       // GET then transport EOF
                    }
                    try (Socket second = listener.accept()) {
                        second.setSoTimeout(4000);
                        exchange(second, commands, "+OK\r\n");
                        exchange(second, commands, "+synthetic-db2\r\n");
                    }
                } catch (Throwable problem) { failure.set(problem); }
            });
            try (RespClient client = new RespClient("127.0.0.1", listener.getLocalPort(), "", "", 2)) {
                client.call("SELECT", "7");
                byte[] response = (byte[]) client.call("GET", "synthetic-key");
                if (!"synthetic-db2".equals(new String(response, StandardCharsets.UTF_8))) throw new AssertionError();
            }
            server.join(5000);
            if (server.isAlive() || failure.get() != null) throw new AssertionError("Script did not settle", failure.get());
            List<List<String>> expected = List.of(List.of("SELECT", "2"), List.of("SELECT", "7"),
                    List.of("GET", "synthetic-key"), List.of("SELECT", "2"), List.of("GET", "synthetic-key"));
            if (!commands.equals(expected)) throw new AssertionError("Baseline behavior changed: " + commands);
            System.out.println("{\"case\":\"confirmed-select-reconnect\",\"confirmedDb\":7,\"restoredDb\":2,"
                    + "\"connections\":2,\"loopbackOnly\":true,\"realRedis\":false,\"serverSettled\":true}");
        }
    }
    private static void exchange(Socket socket, List<List<String>> commands, String response) throws Exception {
        List<?> frame = (List<?>) RespCodec.decode(socket.getInputStream());
        commands.add(frame.stream().map(v -> new String((byte[]) v, StandardCharsets.UTF_8)).toList());
        if (response != null) {
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        }
    }
}
