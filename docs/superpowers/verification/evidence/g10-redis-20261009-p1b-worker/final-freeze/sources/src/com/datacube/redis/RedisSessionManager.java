package com.datacube.redis;

import com.datacube.config.CredentialCipher;
import com.datacube.spi.model.ConnConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Redis-only ownership. No I/O while holding the registry monitor. */
public final class RedisSessionManager {
    private final CredentialCipher cipher;
    private final RedisSessionFactory factory;
    private final Map<String, ConnConfig> configs = new LinkedHashMap<>();
    private final Map<String, RedisSession> live = new LinkedHashMap<>();
    private final Set<RedisSession> independent = new LinkedHashSet<>();
    private final Set<RedisSession> pending = new LinkedHashSet<>();
    private final Object acquireGate = new Object();
    private long generation;

    public RedisSessionManager(CredentialCipher cipher) {
        this(cipher, (config, database, plainPassword) -> new RedisSession(
                new RespClient(config.host(), config.port(), config.username(), plainPassword, database)));
    }
    RedisSessionManager(CredentialCipher cipher, RedisSessionFactory factory) {
        this.cipher = Objects.requireNonNull(cipher, "cipher"); this.factory = Objects.requireNonNull(factory, "factory");
    }
    public void register(ConnConfig config) {
        RedisSession removed = null;
        synchronized (this) {
            ConnConfig previous = configs.put(config.id(), config);
            if (previous == null || !previous.equals(config)) {
                generation++;
                removed = live.remove(config.id());
            }
        }
        closeOne(removed);
    }
    public void unregister(String connId) {
        RedisSession removed;
        synchronized (this) { generation++; removed = live.remove(connId); configs.remove(connId); }
        closeOne(removed);
    }
    public RedisSession acquire(String connId) {
        synchronized (acquireGate) {
            Snapshot snapshot = snapshot(connId);
            RedisSession existing;
            synchronized (this) { existing = live.get(connId); }
            if (existing != null) {
                try {
                    if (!existing.ping()) throw RedisException.rejected(RedisException.Kind.PROTOCOL, RedisException.Delivery.REPLIED);
                    synchronized (this) { if (valid(connId, snapshot) && live.get(connId) == existing) return existing; }
                    throw stale();
                } catch (RuntimeException | Error failure) {
                    synchronized (this) { if (live.get(connId) == existing) live.remove(connId); }
                    closeAfterFailure(existing, failure);
                    if (!(failure instanceof RedisException redis) || redis.kind() != RedisException.Kind.TRANSPORT) throw failure;
                    synchronized (this) { if (!valid(connId, snapshot)) throw stale(); }
                }
            }
            RedisSession created = create(snapshot.config, database(snapshot.config));
            try {
                synchronized (this) {
                    if (!valid(connId, snapshot)) throw stale();
                    pending.add(created); // closeAll owns even the first blocking PING.
                }
                if (!created.ping()) throw RedisException.rejected(RedisException.Kind.PROTOCOL, RedisException.Delivery.REPLIED);
                synchronized (this) {
                    if (!valid(connId, snapshot)) throw stale();
                    pending.remove(created);
                    live.put(connId, created);
                }
                return created;
            } catch (RuntimeException | Error failure) {
                synchronized (this) { pending.remove(created); }
                closeAfterFailure(created, failure); throw failure;
            }
        }
    }
    public RedisSession openSession(String connId, int database) {
        Snapshot snapshot = snapshot(connId);
        RedisSession created = create(snapshot.config, database);
        try {
            synchronized (this) {
                if (!valid(connId, snapshot)) throw stale();
                independent.add(created);
            }
            return created;
        } catch (RuntimeException | Error failure) { closeAfterFailure(created, failure); throw failure; }
    }
    public void closeIndependent(RedisSession session) {
        synchronized (this) { independent.remove(session); }
        closeOne(session);
    }
    public String test(ConnConfig config) {
        RedisSession session = null;
        try { session = create(config, database(config)); return session.ping() ? null : "PING 返回异常"; }
        catch (Exception error) { String message = error.getMessage(); return message == null ? error.getClass().getSimpleName() : message; }
        finally { closeOne(session); }
    }
    public synchronized boolean isConnected(String connId) { return live.containsKey(connId); }
    public void release(String connId) {
        RedisSession removed;
        synchronized (this) { generation++; removed = live.remove(connId); }
        closeOne(removed);
    }
    public void closeAll() {
        List<RedisSession> snapshot;
        synchronized (this) {
            generation++;
            Set<RedisSession> unique = new LinkedHashSet<>(live.values()); unique.addAll(independent); unique.addAll(pending);
            snapshot = new ArrayList<>(unique);
            live.clear(); independent.clear(); pending.clear();
        }
        Throwable first = null;
        for (RedisSession session : snapshot) {
            try { session.close(); }
            catch (RuntimeException | Error failure) {
                if (first == null) first = failure;
                else if (failure instanceof Error && !(first instanceof Error)) {
                    failure.addSuppressed(first); first = failure;
                } else if (first != failure) first.addSuppressed(failure);
            }
        }
        if (first instanceof Error error) throw error;
        if (first instanceof RuntimeException error) throw error;
    }
    private static void closeOne(RedisSession session) { if (session != null) session.close(); }
    private static void closeAfterFailure(RedisSession session, Throwable original) {
        try { session.close(); }
        catch (Error closing) { if (closing != original) closing.addSuppressed(original); throw closing; }
        catch (RuntimeException closing) {
            if (closing != original) original.addSuppressed(closing);
            if (original instanceof Error error) throw error;
            throw (RuntimeException) original; // Closing failure prevents transport replacement.
        }
    }
    private synchronized Snapshot snapshot(String connId) {
        ConnConfig config = configs.get(connId);
        if (config == null) throw new IllegalStateException("Redis connection is not registered");
        return new Snapshot(config, generation);
    }
    private boolean valid(String connId, Snapshot snapshot) {
        return generation == snapshot.generation && snapshot.config.equals(configs.get(connId));
    }
    private record Snapshot(ConnConfig config, long generation) {}
    private static RedisException stale() { return RedisException.rejected(RedisException.Kind.CONTEXT, RedisException.Delivery.NOT_SENT); }
    private RedisSession create(ConnConfig config, int database) { return factory.open(config, database, cipher.decrypt(config.encryptedPassword())); }
    private static int database(ConnConfig config) {
        String value = config.database(); return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
    }
}

@FunctionalInterface
interface RedisSessionFactory { RedisSession open(ConnConfig config, int database, String plainPassword); }
