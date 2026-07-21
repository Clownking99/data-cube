package com.datacube.provider.postgres;

import com.datacube.spi.ConnectionFactory;
import com.datacube.spi.DataAccessor;
import com.datacube.spi.DataEditor;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.DdlGenerator;
import com.datacube.spi.MetadataReader;
import com.datacube.spi.SqlDialect;
import com.datacube.spi.SqlRunner;
import com.datacube.spi.model.DbType;

import com.datacube.provider.jdbc.JdbcDataEditor;

import java.sql.Connection;

/**
 * PostgreSQL 能力提供者：组装 6 个能力实现。
 *
 * <p>无状态能力（工厂/方言/执行器）单例复用；绑定连接的能力
 * （元数据/DDL/数据访问）按 {@link Connection} 现建。
 */
public final class PostgresProvider implements DatabaseProvider {

    private final PgConnectionFactory connectionFactory = new PgConnectionFactory();
    private final PgSqlDialect dialect = new PgSqlDialect();
    private final PgSqlRunner sqlRunner = new PgSqlRunner(dialect);

    @Override
    public DbType type() {
        return DbType.POSTGRESQL;
    }

    @Override
    public boolean supports(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql:");
    }

    @Override
    public ConnectionFactory connectionFactory() {
        return connectionFactory;
    }

    @Override
    public SqlDialect dialect() {
        return dialect;
    }

    @Override
    public SqlRunner sqlRunner() {
        return sqlRunner;
    }

    @Override
    public MetadataReader metadataReader(Connection c) {
        return new PgMetadataReader(c);
    }

    @Override
    public DdlGenerator ddlGenerator(Connection c) {
        return new PgDdlGenerator(c);
    }

    @Override
    public DataAccessor dataAccessor(Connection c) {
        return new PgDataAccessor(c, dialect);
    }

    @Override
    public DataEditor dataEditor(Connection c) {
        return new JdbcDataEditor(c, dialect);
    }
}
