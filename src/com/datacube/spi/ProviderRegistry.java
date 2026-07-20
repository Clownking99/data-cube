package com.datacube.spi;

import com.datacube.provider.oracle.OracleProvider;
import com.datacube.provider.postgres.PostgresProvider;
import com.datacube.spi.model.DbType;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider 注册表：运行时按类型 / JDBC URL 分派到具体 {@link DatabaseProvider}。
 *
 * <p>一期显式注册 {@link PostgresProvider}。未来接入新库只需在 {@code static}
 * 块追加一行注册，业务/UI 层零改动。后续可无缝升级为 {@link java.util.ServiceLoader}
 * 自动发现。
 */
public final class ProviderRegistry {

    private static final List<DatabaseProvider> PROVIDERS = new ArrayList<>();

    static {
        register(new PostgresProvider());
        register(new OracleProvider());
    }

    private ProviderRegistry() {}

    public static synchronized void register(DatabaseProvider provider) {
        PROVIDERS.add(provider);
    }

    /** 按数据库类型获取 provider。 */
    public static DatabaseProvider forType(DbType type) {
        for (DatabaseProvider p : PROVIDERS) {
            if (p.type() == type) return p;
        }
        throw new IllegalArgumentException("未找到数据库类型的 provider: " + type);
    }

    /** 按 JDBC URL 识别 provider（遍历 {@link DatabaseProvider#supports}）。 */
    public static DatabaseProvider forUrl(String jdbcUrl) {
        for (DatabaseProvider p : PROVIDERS) {
            if (p.supports(jdbcUrl)) return p;
        }
        throw new IllegalArgumentException("未找到匹配 URL 的 provider: " + jdbcUrl);
    }

    /** 所有已注册的 provider（用于 UI 展示可选数据库类型）。 */
    public static List<DatabaseProvider> all() {
        return List.copyOf(PROVIDERS);
    }
}
