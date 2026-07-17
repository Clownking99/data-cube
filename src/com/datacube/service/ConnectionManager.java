package com.datacube.service;

import com.datacube.config.CredentialCipher;
import com.datacube.spi.ConnectionFactory;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.ProviderRegistry;
import com.datacube.spi.model.ConnConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 连接管理：按 connId 惰性建连并缓存，提供释放/关闭/测试。
 *
 * <p>经 {@link ProviderRegistry} 取对应 {@link DatabaseProvider} 的
 * {@link ConnectionFactory}；解密后的明文密码经临时 props 传给工厂，
 * 保证 provider 层不接触加密细节。
 */
public final class ConnectionManager {

    private static final Logger LOG = Logger.getLogger(ConnectionManager.class.getName());

    private final CredentialCipher cipher;
    private final Map<String, ConnConfig> configs = new LinkedHashMap<>();
    private final Map<String, Connection> live = new HashMap<>();

    public ConnectionManager(CredentialCipher cipher) {
        this.cipher = cipher;
    }

    /** 凭据加解密器（供 UI 编辑连接时加密密码复用）。 */
    public CredentialCipher cipher() {
        return cipher;
    }

    /** 注册/更新连接配置（供 acquire 惰性建连使用）。 */
    public synchronized void register(ConnConfig cfg) {
        configs.put(cfg.id(), cfg);
    }

    /** 移除配置并关闭其活动连接。 */
    public synchronized void unregister(String connId) {
        release(connId);
        configs.remove(connId);
    }

    public synchronized ConnConfig config(String connId) {
        return configs.get(connId);
    }

    /** 该 connId 当前是否持有活动连接（供 UI 判断是否可断开）。 */
    public synchronized boolean isConnected(String connId) {
        return live.containsKey(connId);
    }

    /** 该 connId 对应的 provider。 */
    public DatabaseProvider provider(String connId) {
        ConnConfig cfg = requireConfig(connId);
        return ProviderRegistry.forType(cfg.type());
    }

    /** 惰性建连并按 connId 缓存；已有有效连接直接复用。 */
    public synchronized Connection acquire(String connId) throws SQLException {
        Connection existing = live.get(connId);
        if (existing != null && isValid(existing)) {
            return existing;
        }
        if (existing != null) {
            closeQuietly(existing);
            live.remove(connId);
        }
        ConnConfig cfg = requireConfig(connId);
        DatabaseProvider provider = ProviderRegistry.forType(cfg.type());
        Connection conn = provider.connectionFactory().open(withPlainPassword(cfg));
        live.put(connId, conn);
        return conn;
    }

    /** 释放指定连接。 */
    public synchronized void release(String connId) {
        Connection conn = live.remove(connId);
        if (conn != null) closeQuietly(conn);
    }

    /** 关闭全部活动连接（应用退出时调用）。 */
    public synchronized void closeAll() {
        for (Connection c : live.values()) closeQuietly(c);
        live.clear();
    }

    /**
     * 测试连接（不缓存）。
     *
     * @return {@code null} 表示成功；否则返回错误消息。
     */
    public String test(ConnConfig cfg) {
        DatabaseProvider provider = ProviderRegistry.forType(cfg.type());
        return provider.connectionFactory().test(withPlainPassword(cfg));
    }

    // ---------- 内部 ----------

    private ConnConfig requireConfig(String connId) {
        ConnConfig cfg = configs.get(connId);
        if (cfg == null) throw new IllegalStateException("未注册的连接: " + connId);
        return cfg;
    }

    /** 将解密后的明文密码放入临时 props（key {@code __plainPassword}）。 */
    private ConnConfig withPlainPassword(ConnConfig cfg) {
        String plain = cipher.decrypt(cfg.encryptedPassword());
        Map<String, String> props = new HashMap<>(cfg.props());
        props.put("__plainPassword", plain);
        return new ConnConfig(cfg.id(), cfg.name(), cfg.type(), cfg.host(), cfg.port(),
                cfg.database(), cfg.username(), cfg.encryptedPassword(), props);
    }

    private static boolean isValid(Connection c) {
        try {
            return !c.isClosed() && c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException e) {
            LOG.fine("关闭连接异常: " + e.getMessage());
        }
    }
}
