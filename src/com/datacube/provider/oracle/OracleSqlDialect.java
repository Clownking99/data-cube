package com.datacube.provider.oracle;

import com.datacube.spi.SqlDialect;

/**
 * Oracle 方言实现。
 *
 * <p>要点：双引号引用（同 PG）；分页用 12c+ 的 {@code OFFSET .. ROWS FETCH NEXT .. ROWS ONLY}；
 * 无 {@code search_path}，用 {@code ALTER SESSION SET CURRENT_SCHEMA}；user=schema 故 {@link #hasSchemaLevel()} 为 true。
 */
public final class OracleSqlDialect implements SqlDialect {

    @Override
    public String quoteIdentifier(String ident) {
        if (ident == null) return null;
        // 双引号引用，内部双引号转义为两个；Oracle 未加引号标识符默认大写
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String pageClause(long offset, int limit) {
        return "OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    @Override
    public String currentSchemaSql(String schema) {
        if (schema == null || schema.isEmpty()) return null;
        return "ALTER SESSION SET CURRENT_SCHEMA = " + quoteIdentifier(schema);
    }

    @Override
    public boolean hasSchemaLevel() {
        return true;
    }

    @Override
    public String sqlLiteral(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean b) return b ? "1" : "0";
        if (v instanceof byte[] bytes) return "HEXTORAW('" + hex(bytes) + "')";
        if (v instanceof java.sql.Timestamp ts) {
            return "TO_TIMESTAMP('" + ts + "', 'YYYY-MM-DD HH24:MI:SS.FF')";
        }
        if (v instanceof java.sql.Date d) {
            return "TO_DATE('" + d + "', 'YYYY-MM-DD')";
        }
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
