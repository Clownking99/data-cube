package com.datacube.redis;

import com.datacube.config.CredentialCipher;
import com.datacube.spi.model.ConnConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

/** 按连接 ID 管理 Redis 配置与缓存会话，并可为 UI 标签创建隔离会话。 */
public final class RedisSessionManager {

    private final CredentialCipher cipher;
    private final RedisSessionFactory factory;
    private final Map<String, ConnConfig> configs = new LinkedHashMap<>();
    private final Map<String, RedisSession> live = new LinkedHashMap<>();
    private final Set<RedisSession> independent = new LinkedHashSet<>();

    public RedisSessionManager(CredentialCipher cipher) {
        this(cipher, (config, database, plainPassword) -> new RedisSession(
                new RespClient(config.host(), config.port(), config.username(), plainPassword, database)));
    }

    RedisSessionManager(CredentialCipher cipher, RedisSessionFactory factory) {
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public synchronized void register(ConnConfig config) {
        ConnConfig previous = configs.put(config.id(), config);
        if (previous != null && !previous.equals(config)) release(config.id());
    }

    public synchronized void unregister(String connId) {
        release(connId);
        configs.remove(connId);
    }

    public synchronized RedisSession acquire(String connId) {
        RedisSession existing = live.get(connId);
        if (existing != null) {
            try {
                if (existing.ping()) return existing;
            } catch (RuntimeException ignored) {
                // Discard below and reconnect once.
            }
            existing.close();
            live.remove(connId);
        }
        RedisSession created = create(requireConfig(connId), database(requireConfig(connId)));
        try {
            if (!created.ping()) throw new RedisException("PING returned an unexpected response");
            live.put(connId, created);
            return created;
        } catch (RuntimeException e) {
            created.close();
            throw e;
        }
    }

    /** 为一个 UI 标签创建不进入共享缓存的独立会话。 */
    public synchronized RedisSession openSession(String connId, int database) {
        RedisSession session = create(requireConfig(connId), database);
        independent.add(session);
        return session;
    }

    public synchronized void closeIndependent(RedisSession session) {
        independent.remove(session);
        if (session != null) session.close();
    }

    public String test(ConnConfig config) {
        RedisSession session = null;
        try {
            session = create(config, database(config));
            return session.ping() ? null : "PING 返回异常";
        } catch (Exception e) {
            return message(e);
        } finally {
            if (session != null) session.close();
        }
    }

    public synchronized boolean isConnected(String connId) {
        return live.containsKey(connId);
    }

    public synchronized void release(String connId) {
        RedisSession session = live.remove(connId);
        if (session != null) session.close();
    }

    public synchronized void closeAll() {
        for (RedisSession session : live.values()) session.close();
        live.clear();
        for (RedisSession session : independent) session.close();
        independent.clear();
    }

    private RedisSession create(ConnConfig config, int database) {
        return factory.open(config, database, cipher.decrypt(config.encryptedPassword()));
    }

    private ConnConfig requireConfig(String connId) {
        ConnConfig config = configs.get(connId);
        if (config == null) throw new IllegalStateException("未注册的 Redis 连接: " + connId);
        return config;
    }

    private static int database(ConnConfig config) {
        String value = config.database();
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
    }

    private static String message(Exception error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }
}

@FunctionalInterface
interface RedisSessionFactory {
    RedisSession open(ConnConfig config, int database, String plainPassword);
}
