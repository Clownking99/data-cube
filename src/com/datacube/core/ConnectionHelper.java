package com.datacube.core;

import com.datacube.cli.ConsoleLogger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 连接与 Schema 工具：CLI 与 GUI 共用，消除重复逻辑。
 */
public final class ConnectionHelper {

    private ConnectionHelper() {}

    /**
     * 测试连接并返回 Connection。失败时记录错误日志并抛出。
     */
    public static Connection openAndTest(String url, String user, String pass, String label,
                                         MigrationLogger logger) throws SQLException {
        try {
            Connection conn = DriverManager.getConnection(url, user, pass);
            logger.logOk(label + " 连接成功 (" + url + ")");
            return conn;
        } catch (SQLException e) {
            logger.logErr(label + " 连接失败: " + e.getMessage());
            logger.logToFile(ConsoleLogger.stackTrace(e));
            throw e;
        }
    }

    /**
     * 确保 schema 存在，不存在则创建。
     */
    public static void ensureSchema(Connection conn, String schema, MigrationLogger logger) throws SQLException {
        boolean exists = false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                exists = rs.next();
            }
        }
        if (exists) {
            logger.logOk("Schema \"" + schema + "\" 已存在");
            return;
        }
        logger.logInfo("Schema \"" + schema + "\" 不存在，正在创建...");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA " + schema);
            logger.logOk("Schema \"" + schema + "\" 创建成功");
        } catch (SQLException e) {
            logger.logErr("创建 Schema 失败: " + e.getMessage());
            logger.logToFile(ConsoleLogger.stackTrace(e));
            throw e;
        }
    }

    /**
     * 加载 JDBC 驱动（Oracle / PostgreSQL）。
     */
    public static void loadDrivers(MigrationLogger logger) {
        try {
            Class.forName("oracle.jdbc.driver.OracleDriver");
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            logger.logErr("JDBC 驱动加载失败: " + e.getMessage());
        }
    }
}