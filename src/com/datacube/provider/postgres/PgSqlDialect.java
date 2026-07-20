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
        return "SET search_path TO " + quoteIdentifier(foldUnquotedIdentifier(schema));
    }

    @Override
    public String foldUnquotedIdentifier(String ident) {
        // PG 未加引号标识符默认小写
        return ident == null ? null : ident.toLowerCase();
    }

    @Override
    public boolean hasSchemaLevel() {
        return true;
    }

    @Override
    public String sqlLiteral(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean b) return b ? "TRUE" : "FALSE";
        if (v instanceof byte[] bytes) return "'\\x" + hex(bytes) + "'";
        String s = v.toString();
        return "'" + s.replace("'", "''") + "'";
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
