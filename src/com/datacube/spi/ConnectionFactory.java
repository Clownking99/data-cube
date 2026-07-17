package com.datacube.spi;

import com.datacube.spi.model.ConnConfig;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 连接工厂能力：驱动加载 + 建连 + 连接测试。
 *
 * <p>由具体 provider 实现（如 PostgreSQL 加载 {@code org.postgresql.Driver}）。
 */
public interface ConnectionFactory {

    /** 确保 JDBC 驱动已加载（幂等）。 */
    void ensureDriverLoaded();

    /** 按配置建立连接。调用方负责关闭。 */
    Connection open(ConnConfig cfg) throws SQLException;

    /**
     * 测试连接。
     *
     * @return {@code null} 表示成功；否则返回错误消息（不抛异常）。
     */
    String test(ConnConfig cfg);
}
