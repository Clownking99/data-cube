package com.datacube.provider.postgres;

import com.datacube.spi.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

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

    @Override
    public Map<String, String> columnComments(Connection conn, String schema, String table) throws SQLException {
        Map<String, String> out = new HashMap<>();
        if (schema == null || table == null) return out;
        String sql = "SELECT a.attname AS c, col_description(a.attrelid, a.attnum) AS d "
                + "FROM pg_attribute a "
                + "JOIN pg_class cl ON cl.oid = a.attrelid "
                + "JOIN pg_namespace n ON n.oid = cl.relnamespace "
                + "WHERE n.nspname = ? AND cl.relname = ? AND a.attnum > 0 AND NOT a.attisdropped";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String d = rs.getString("d");
                    if (d != null && !d.isEmpty()) out.put(rs.getString("c"), d);
                }
            }
        }
        return out;
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
