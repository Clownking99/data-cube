package com.datacube.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Redis 命令的类型化、二进制安全门面。 */
public final class RedisSession implements AutoCloseable {

    public record ScanPage(long cursor, List<byte[]> values) {}
    public record HashEntry(byte[] field, byte[] value) {}
    public record HashScanPage(long cursor, List<HashEntry> entries) {}
    public record ScoredValue(byte[] member, double score) {}
    public record ZScanPage(long cursor, List<ScoredValue> entries) {}

    private final RedisCommandExecutor executor;
    private final Runnable closeAction;
    private boolean closed;

    RedisSession(RedisCommandExecutor executor, Runnable closeAction) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
    }

    RedisSession(RespClient client) {
        this(client, client::close);
    }

    public boolean ping() {
        return "PONG".equalsIgnoreCase(text(call("PING")));
    }

    public ScanPage scan(long cursor, String pattern, int count) {
        return scanPage(call("SCAN", cursor, "MATCH", pattern == null || pattern.isBlank() ? "*" : pattern,
                "COUNT", count));
    }

    public String type(String key) {
        return text(call("TYPE", key));
    }

    public long ttl(String key) {
        return integer(call("TTL", key));
    }

    public boolean expire(String key, long seconds) {
        return integer(call("EXPIRE", key, seconds)) != 0;
    }

    public boolean persist(String key) {
        return integer(call("PERSIST", key)) != 0;
    }

    public boolean del(String key) {
        return integer(call("DEL", key)) != 0;
    }

    public void rename(String key, String newKey) {
        expectOk(call("RENAME", key, newKey), "RENAME");
    }

    public boolean exists(String key) {
        return integer(call("EXISTS", key)) != 0;
    }

    public long dbsize() {
        return integer(call("DBSIZE"));
    }

    public void select(int database) {
        expectOk(call("SELECT", database), "SELECT");
    }

    public String info(String section) {
        return text(section == null || section.isBlank() ? call("INFO") : call("INFO", section));
    }

    public byte[] get(String key) {
        return bytesOrNull(call("GET", key));
    }

    public long strlen(String key) {
        return integer(call("STRLEN", key));
    }

    public byte[] getrange(String key, long start, long end) {
        return bytesOrNull(call("GETRANGE", key, start, end));
    }

    public void set(String key, byte[] value) {
        expectOk(call("SET", key, value), "SET");
    }

    public HashScanPage hscan(String key, long cursor, int count) {
        ScanPage page = scanPage(call("HSCAN", key, cursor, "COUNT", count));
        requirePairs(page.values(), "HSCAN");
        List<HashEntry> entries = new ArrayList<>(page.values().size() / 2);
        for (int i = 0; i + 1 < page.values().size(); i += 2) {
            entries.add(new HashEntry(page.values().get(i), page.values().get(i + 1)));
        }
        return new HashScanPage(page.cursor(), List.copyOf(entries));
    }

    public boolean hset(String key, byte[] field, byte[] value) {
        return integer(call("HSET", key, field, value)) != 0;
    }

    public boolean hdel(String key, byte[] field) {
        return integer(call("HDEL", key, field)) != 0;
    }

    public long llen(String key) {
        return integer(call("LLEN", key));
    }

    public List<byte[]> lrange(String key, long start, long stop) {
        return byteList(call("LRANGE", key, start, stop));
    }

    public long lpush(String key, byte[] value) {
        return integer(call("LPUSH", key, value));
    }

    public long rpush(String key, byte[] value) {
        return integer(call("RPUSH", key, value));
    }

    public void lset(String key, long index, byte[] value) {
        expectOk(call("LSET", key, index, value), "LSET");
    }

    public long lrem(String key, long count, byte[] value) {
        return integer(call("LREM", key, count, value));
    }

    public ScanPage sscan(String key, long cursor, int count) {
        return scanPage(call("SSCAN", key, cursor, "COUNT", count));
    }

    public boolean sadd(String key, byte[] member) {
        return integer(call("SADD", key, member)) != 0;
    }

    public boolean srem(String key, byte[] member) {
        return integer(call("SREM", key, member)) != 0;
    }

    public ZScanPage zscan(String key, long cursor, int count) {
        ScanPage page = scanPage(call("ZSCAN", key, cursor, "COUNT", count));
        requirePairs(page.values(), "ZSCAN");
        List<ScoredValue> entries = new ArrayList<>(page.values().size() / 2);
        for (int i = 0; i + 1 < page.values().size(); i += 2) {
            entries.add(new ScoredValue(page.values().get(i), Double.parseDouble(text(page.values().get(i + 1)))));
        }
        return new ZScanPage(page.cursor(), List.copyOf(entries));
    }

    public boolean zadd(String key, double score, byte[] member) {
        return integer(call("ZADD", key, Double.toString(score), member)) != 0;
    }

    public boolean zrem(String key, byte[] member) {
        return integer(call("ZREM", key, member)) != 0;
    }

    public long zcard(String key) {
        return integer(call("ZCARD", key));
    }

    public Object raw(String... args) {
        return call((Object[]) args);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        closeAction.run();
    }

    private Object call(Object... args) {
        if (closed) throw new RedisException("Redis session is closed");
        byte[][] bytes = new byte[args.length][];
        for (int i = 0; i < args.length; i++) {
            Object arg = Objects.requireNonNull(args[i], "Redis command argument");
            bytes[i] = arg instanceof byte[] raw ? raw : utf8(String.valueOf(arg));
        }
        return executor.callBytes(bytes);
    }

    private static ScanPage scanPage(Object response) {
        List<?> parts = list(response, "scan response");
        if (parts.size() != 2) throw new RedisException("Invalid Redis scan response");
        long cursor = Long.parseLong(text(parts.get(0)));
        return new ScanPage(cursor, byteList(parts.get(1)));
    }

    private static List<byte[]> byteList(Object response) {
        List<?> values = list(response, "array response");
        List<byte[]> result = new ArrayList<>(values.size());
        for (Object value : values) result.add(bytesOrNull(value));
        return List.copyOf(result);
    }

    private static void requirePairs(List<byte[]> values, String command) {
        if ((values.size() & 1) != 0) throw new RedisException("Invalid " + command + " pair response");
    }

    private static List<?> list(Object response, String label) {
        if (!(response instanceof List<?> values)) throw new RedisException("Invalid Redis " + label);
        return values;
    }

    private static long integer(Object response) {
        if (response instanceof Long value) return value;
        throw new RedisException("Expected Redis integer response");
    }

    private static byte[] bytesOrNull(Object response) {
        if (response == null || response instanceof byte[]) return (byte[]) response;
        throw new RedisException("Expected Redis bulk string response");
    }

    private static String text(Object response) {
        byte[] bytes = bytesOrNull(response);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static void expectOk(Object response, String command) {
        if (!"OK".equalsIgnoreCase(text(response))) {
            throw new RedisException(command + " returned an unexpected response");
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
