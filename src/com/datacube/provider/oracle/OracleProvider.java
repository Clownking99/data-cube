package com.datacube.provider.oracle;

import com.datacube.spi.ConnectionFactory;
import com.datacube.spi.DataAccessor;
import com.datacube.spi.DataEditor;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.DdlGenerator;
import com.datacube.spi.MetadataReader;
import com.datacube.spi.SqlDialect;
import com.datacube.spi.SqlRunner;
import com.datacube.spi.SequenceDdlBuilder;
import com.datacube.spi.TableDdlBuilder;
import com.datacube.spi.model.DbType;
import com.datacube.sqleditor.result.ResultFilterSqlRenderer;
import com.datacube.spi.schemadiff.SchemaDiffCapability;

import com.datacube.provider.jdbc.JdbcDataEditor;

import java.sql.Connection;
import java.util.Optional;

/**
 * Oracle 能力提供者：组装数据库能力实现，与 {@code provider.postgres} 对等。
 *
 * <p>无状态能力（工厂/方言/执行器）单例复用；绑定连接的能力
 * （元数据/DDL/数据访问）按 {@link Connection} 现建。
 */
public final class OracleProvider implements DatabaseProvider {

    private final OracleConnectionFactory connectionFactory = new OracleConnectionFactory();
    private final OracleSqlDialect dialect = new OracleSqlDialect();
    private final OracleSqlRunner sqlRunner = new OracleSqlRunner(dialect);
    private final OracleTableDdlBuilder tableDdlBuilder = new OracleTableDdlBuilder();
    private final OracleSequenceDdlBuilder sequenceDdlBuilder = new OracleSequenceDdlBuilder();
    private final SchemaDiffCapability schemaDiffCapability = new OracleSchemaDiffCapability();
    private final ResultFilterSqlRenderer resultFilterSqlRenderer = new OracleResultFilterSqlRenderer();

    @Override
    public DbType type() {
        return DbType.ORACLE;
    }

    @Override
    public boolean supports(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:oracle:");
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
    public TableDdlBuilder tableDdlBuilder() {
        return tableDdlBuilder;
    }

    @Override
    public SequenceDdlBuilder sequenceDdlBuilder() {
        return sequenceDdlBuilder;
    }

    @Override
    public MetadataReader metadataReader(Connection c) {
        return new OracleMetadataReader(c);
    }

    @Override
    public DdlGenerator ddlGenerator(Connection c) {
        return new OracleDdlGenerator(c);
    }

    @Override
    public DataAccessor dataAccessor(Connection c) {
        return new OracleDataAccessor(c, dialect);
    }

    @Override
    public DataEditor dataEditor(Connection c) {
        return new JdbcDataEditor(c, dialect);
    }

    @Override
    public Optional<SchemaDiffCapability> schemaDiffCapability() {
        return Optional.of(schemaDiffCapability);
    }

    @Override
    public Optional<ResultFilterSqlRenderer> resultFilterSqlRenderer() {
        return Optional.of(resultFilterSqlRenderer);
    }
}
