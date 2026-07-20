package com.datacube.spi.model;

/**
 * 数据库类型枚举。
 *
 * <p>接入新库在此追加枚举值，并新增对应的 {@code provider.<type>} 实现即可，
 * 业务层零改动。
 */
public enum DbType {
    POSTGRESQL("PostgreSQL", "jdbc:postgresql:", 5432),
    ORACLE("Oracle", "jdbc:oracle:thin:@", 1521);

    private final String displayName;
    private final String urlPrefix;
    private final int defaultPort;

    DbType(String displayName, String urlPrefix, int defaultPort) {
        this.displayName = displayName;
        this.urlPrefix = urlPrefix;
        this.defaultPort = defaultPort;
    }

    public String displayName() {
        return displayName;
    }

    public String urlPrefix() {
        return urlPrefix;
    }

    public int defaultPort() {
        return defaultPort;
    }
}
