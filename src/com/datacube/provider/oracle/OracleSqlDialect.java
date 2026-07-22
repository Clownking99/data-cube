package com.datacube.provider.oracle;

import com.datacube.spi.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

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
        return "ALTER SESSION SET CURRENT_SCHEMA = " + quoteIdentifier(foldUnquotedIdentifier(schema));
    }

    @Override
    public String foldUnquotedIdentifier(String ident) {
        // Oracle 未加引号标识符默认大写
        return ident == null ? null : ident.toUpperCase();
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

    @Override
    public Map<String, String> columnComments(Connection conn, String schema, String table) throws SQLException {
        Map<String, String> out = new HashMap<>();
        if (schema == null || table == null) return out;
        String sql = "SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS "
                + "WHERE OWNER = ? AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String d = rs.getString("COMMENTS");
                    if (d != null && !d.isEmpty()) out.put(rs.getString("COLUMN_NAME"), d);
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
