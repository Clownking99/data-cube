package com.datacube.provider.postgres;

import com.datacube.spi.SqlDialect;

/**
 * PostgreSQL 方言实现。
 */
public final class PgSqlDialect implements SqlDialect {

    @Override
    public String quoteIdentifier(String ident) {
        if (ident == null) return null;
        // 双引号引用，内部双引号转义为两个
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String pageClause(long offset, int limit) {
        return "LIMIT " + limit + " OFFSET " + offset;
    }

    @Override
    public String currentSchemaSql(String schema) {
        if (schema == null || schema.isEmpty()) return null;
        return "SET search_path TO " + quoteIdentifier(schema);
    }

    @Override
    public boolean hasSchemaLevel() {
        return true;
    }
}
