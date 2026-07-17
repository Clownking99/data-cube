package com.datacube.spi.model;

import java.util.Collections;
import java.util.Map;

/**
 * 连接配置：持久化到 {@code ~/.datacube/connections.json}。
 *
 * <p>元信息明文存储；{@code encryptedPassword} 为 AES 加密后的密文
 * （由 service 层 {@code CredentialCipher} 加解密）。不可变。
 */
public record ConnConfig(
        String id,
        String name,
        DbType type,
        String host,
        int port,
        String database,
        String username,
        String encryptedPassword,
        Map<String, String> props) {

    public ConnConfig {
        props = props == null ? Collections.emptyMap() : Map.copyOf(props);
    }

    /** 拼装 JDBC URL（按数据库类型）。一期仅 PG。 */
    public String jdbcUrl() {
        return switch (type) {
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
        };
    }

    /** 返回替换了加密密码字段的副本。 */
    public ConnConfig withEncryptedPassword(String enc) {
        return new ConnConfig(id, name, type, host, port, database, username, enc, props);
    }
}
