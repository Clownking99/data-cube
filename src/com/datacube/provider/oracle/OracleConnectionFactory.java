package com.datacube.provider.oracle;

import com.datacube.spi.ConnectionFactory;
import com.datacube.spi.model.ConnConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Oracle 连接工厂：加载 {@code oracle.jdbc.OracleDriver}，建连与测试。
 *
 * <p>驱动硬编码收敛在本实现层；密码经 service 层解密后由临时 props 传入。
 */
public final class OracleConnectionFactory implements ConnectionFactory {

    private volatile boolean driverLoaded = false;

    @Override
    public void ensureDriverLoaded() {
        if (driverLoaded) return;
        synchronized (this) {
            if (driverLoaded) return;
            try {
                Class.forName("oracle.jdbc.OracleDriver");
                driverLoaded = true;
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Oracle JDBC 驱动未找到: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public Connection open(ConnConfig cfg) throws SQLException {
        ensureDriverLoaded();
        return DriverManager.getConnection(cfg.jdbcUrl(), cfg.username(), passwordOf(cfg));
    }

    @Override
    public String test(ConnConfig cfg) {
        try {
            ensureDriverLoaded();
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        try (Connection c = DriverManager.getConnection(cfg.jdbcUrl(), cfg.username(), passwordOf(cfg));
             Statement s = c.createStatement()) {
            s.execute("SELECT 1 FROM DUAL");
            return null;
        } catch (SQLException e) {
            return e.getMessage();
        }
    }

    /**
     * 从配置取明文密码。此处 {@code encryptedPassword} 已由 service 层解密后
     * 通过临时 props 传入（见 ConnectionManager），保证本层不接触加密细节。
     */
    private static String passwordOf(ConnConfig cfg) {
        String pw = cfg.props().get("__plainPassword");
        return pw != null ? pw : cfg.encryptedPassword();
    }
}
