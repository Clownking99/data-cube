package com.datacube.provider.postgres;

import com.datacube.spi.DdlGenerator;
import com.datacube.spi.model.RoutineRef;
import com.datacube.spi.model.TableRef;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL DDL 生成器：读取对象的 CREATE 语句。
 *
 * <p>视图/函数直接用 PG 内置函数（{@code pg_get_viewdef}/{@code pg_get_functiondef}）获取；
 * 表 DDL 由 {@code information_schema} 列信息 + 主键约束拼装。
 */
public final class PgDdlGenerator implements DdlGenerator {

    private final Connection conn;

    public PgDdlGenerator(Connection conn) {
        this.conn = conn;
    }

    private static String quote(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static String qualified(String schema, String name) {
        return (schema == null || schema.isEmpty()) ? quote(name) : quote(schema) + "." + quote(name);
    }

    @Override
    public String tableDdl(TableRef t) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(qualified(t.schema(), t.name())).append(" (\n");

        List<String> lines = new ArrayList<>();
        String colSql = "SELECT column_name, data_type, character_maximum_length, "
                + "numeric_precision, numeric_scale, is_nullable, column_default "
                + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? "
                + "ORDER BY ordinal_position";
        try (PreparedStatement ps = conn.prepareStatement(colSql)) {
            ps.setString(1, t.schema());
            ps.setString(2, t.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StringBuilder col = new StringBuilder("    ");
                    col.append(quote(rs.getString("column_name"))).append(' ');
                    col.append(formatType(rs));
                    if ("NO".equalsIgnoreCase(rs.getString("is_nullable"))) {
                        col.append(" NOT NULL");
                    }
                    String def = rs.getString("column_default");
                    if (def != null && !def.isBlank()) {
                        col.append(" DEFAULT ").append(def);
                    }
                    lines.add(col.toString());
                }
            }
        }

        String pk = primaryKeyClause(t);
        if (pk != null) {
            lines.add("    " + pk);
        }

        sb.append(String.join(",\n", lines));
        sb.append("\n);");
        return sb.toString();
    }

    private static String formatType(ResultSet rs) throws SQLException {
        String type = rs.getString("data_type");
        long charLen = rs.getLong("character_maximum_length");
        boolean charLenNull = rs.wasNull();
        int precision = rs.getInt("numeric_precision");
        boolean precisionNull = rs.wasNull();
        int scale = rs.getInt("numeric_scale");
        boolean scaleNull = rs.wasNull();

        if (!charLenNull && charLen > 0
                && (type.contains("char") || type.contains("varying"))) {
            return type + "(" + charLen + ")";
        }
        if ("numeric".equalsIgnoreCase(type) && !precisionNull) {
            return scaleNull ? "numeric(" + precision + ")"
                    : "numeric(" + precision + "," + scale + ")";
        }
        return type;
    }

    private String primaryKeyClause(TableRef t) throws SQLException {
        List<String> cols = new ArrayList<>();
        String pkName = null;
        String sql = "SELECT tc.constraint_name, kcu.column_name "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema "
                + "WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = ? AND tc.table_name = ? "
                + "ORDER BY kcu.ordinal_position";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.schema());
            ps.setString(2, t.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pkName = rs.getString("constraint_name");
                    cols.add(quote(rs.getString("column_name")));
                }
            }
        }
        if (cols.isEmpty()) return null;
        return "CONSTRAINT " + quote(pkName) + " PRIMARY KEY (" + String.join(", ", cols) + ")";
    }

    @Override
    public String viewDdl(TableRef t) throws SQLException {
        String sql = "SELECT pg_get_viewdef(?::regclass, true)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, qualified(t.schema(), t.name()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return "CREATE OR REPLACE VIEW " + qualified(t.schema(), t.name())
                            + " AS\n" + rs.getString(1);
                }
            }
        }
        return "-- 视图定义不可用: " + t.qualified();
    }

    @Override
    public String routineDdl(RoutineRef r) throws SQLException {
        String sql = "SELECT pg_get_functiondef(p.oid) "
                + "FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                + "WHERE n.nspname = ? AND p.proname = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.schema());
            ps.setString(2, r.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        return "-- 函数定义不可用: " + r.qualified();
    }

    @Override
    public String sequenceDdl(String schema, String name) throws SQLException {
        String sql = "SELECT start_value, minimum_value, maximum_value, increment "
                + "FROM information_schema.sequences "
                + "WHERE sequence_schema = ? AND sequence_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return "CREATE SEQUENCE " + qualified(schema, name)
                            + "\n    START WITH " + rs.getString("start_value")
                            + "\n    INCREMENT BY " + rs.getString("increment")
                            + "\n    MINVALUE " + rs.getString("minimum_value")
                            + "\n    MAXVALUE " + rs.getString("maximum_value") + ";";
                }
            }
        }
        return "CREATE SEQUENCE " + qualified(schema, name) + ";";
    }

    @Override
    public String packageDdl(String schema, String name) throws SQLException {
        return "-- PostgreSQL 不支持程序包（package）";
    }

    @Override
    public String triggerDdl(String schema, String name) throws SQLException {
        return "-- PostgreSQL 触发器 DDL 暂未支持";
    }

    @Override
    public String typeDdl(String schema, String name) throws SQLException {
        return "-- PostgreSQL 自定义类型 DDL 暂未支持";
    }
}
