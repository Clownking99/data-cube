package com.datacube.spi;

import com.datacube.spi.model.DbType;

import java.sql.Connection;

/**
 * 数据库能力提供者：SPI 顶层入口。
 *
 * <p>业务层只依赖本接口与其返回的能力接口，运行时通过
 * {@link ProviderRegistry} 按 {@link DbType} / JDBC URL 分派到具体实现，
 * 完全不感知底层数据库。
 *
 * <p>新增数据库支持 = 新建一个 {@code DatabaseProvider} 实现 + 在
 * {@link ProviderRegistry} 注册，业务/UI 层零改动（开闭原则）。
 */
public interface DatabaseProvider {

    /** 数据库类型标识。 */
    DbType type();

    /** 是否支持该 JDBC URL（按前缀识别）。 */
    boolean supports(String jdbcUrl);

    /** 连接工厂（无状态，可复用）。 */
    ConnectionFactory connectionFactory();

    /** SQL 方言（无状态，可复用）。 */
    SqlDialect dialect();

    /** SQL 执行器（无状态，可复用）。 */
    SqlRunner sqlRunner();

    /** 绑定连接的元数据读取器。 */
    MetadataReader metadataReader(Connection c);

    /** 绑定连接的 DDL 生成器。 */
    DdlGenerator ddlGenerator(Connection c);

    /** 绑定连接的数据访问器。 */
    DataAccessor dataAccessor(Connection c);

    /** 绑定连接的数据编辑器（行级 INSERT/UPDATE/DELETE）。 */
    DataEditor dataEditor(Connection c);
}
