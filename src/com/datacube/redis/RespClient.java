package com.datacube.redis;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 单连接、单飞行命令的 RESP2 TCP 客户端。 */
public final class RespClient implements Closeable, RedisCommandExecutor {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final Set<String> RETRY_SAFE = Set.of(
            "PING", "GET", "STRLEN", "GETRANGE", "TYPE", "TTL", "EXISTS", "DBSIZE", "INFO",
            "SCAN", "HSCAN", "SSCAN", "ZSCAN", "LLEN", "LRANGE", "ZCARD");

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final int database;

    private Socket socket;
    private boolean closed;

    public RespClient(String host, int port, String username, String password, int database) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.database = database;
    }

    /** 发送 UTF-8 文本命令。 */
    public Object call(String... args) {
        byte[][] encodedArgs = Arrays.stream(args)
                .map(value -> Objects.requireNonNull(value, "Redis command argument")
                        .getBytes(StandardCharsets.UTF_8))
                .toArray(byte[][]::new);
        return callBytes(encodedArgs);
    }

    /** 发送二进制安全命令；包内会话门面使用。 */
    @Override
    public synchronized Object callBytes(byte[]... args) {
        if (closed) throw new RedisException("Redis client is closed");
        IOException last = null;
        boolean retrySafe = retrySafe(args);
        int attempts = retrySafe ? 2 : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                ensureConnected();
                return exchange(args);
            } catch (RedisException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                closeSocket();
            }
        }
        if (!retrySafe) {
            throw new RedisException("Redis connection lost; command result is uncertain and was not replayed", last);
        }
        throw new RedisException("Redis I/O error: " + last.getMessage(), last);
    }

    private static boolean retrySafe(byte[][] args) {
        if (args.length == 0 || args[0] == null) return false;
        String command = new String(args[0], StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
        return RETRY_SAFE.contains(command);
    }

    private void ensureConnected() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) return;
        Socket created = new Socket();
        try {
            created.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            created.setSoTimeout(READ_TIMEOUT_MS);
            socket = created;
            if (!password.isEmpty()) {
                if (username.isBlank()) {
                    expectOk(exchange(strings("AUTH", password)), "AUTH");
                } else {
                    expectOk(exchange(strings("AUTH", username, password)), "AUTH");
                }
            }
            if (database != 0) expectOk(exchange(strings("SELECT", Integer.toString(database))), "SELECT");
        } catch (IOException | RuntimeException e) {
            closeSocket();
            throw e;
        }
    }

    private Object exchange(byte[][] args) throws IOException {
        socket.getOutputStream().write(RespCodec.encode(args));
        socket.getOutputStream().flush();
        return RespCodec.decode(socket.getInputStream());
    }

    private static void expectOk(Object response, String command) {
        if (!(response instanceof byte[] bytes)
                || !"OK".equalsIgnoreCase(new String(bytes, StandardCharsets.UTF_8))) {
            throw new RedisException(command + " returned an unexpected response");
        }
    }

    private static byte[][] strings(String... args) {
        return Arrays.stream(args).map(value -> value.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeSocket();
    }

    private void closeSocket() {
        Socket current = socket;
        socket = null;
        if (current == null) return;
        try {
            current.close();
        } catch (IOException ignored) {
            // Closing is best effort; the connection is already discarded.
        }
    }
}

@FunctionalInterface
interface RedisCommandExecutor {
    Object callBytes(byte[]... args);
}
