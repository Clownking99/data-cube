package com.datacube.provider.postgres;

import com.datacube.spi.MetadataReader;
import com.datacube.spi.model.CatalogInfo;
import com.datacube.spi.model.ColumnInfo;
import com.datacube.spi.model.ConstraintInfo;
import com.datacube.spi.model.IndexInfo;
import com.datacube.spi.model.RoutineInfo;
import com.datacube.spi.model.SchemaInfo;
import com.datacube.spi.model.SequenceInfo;
import com.datacube.spi.model.TableInfo;
import com.datacube.spi.model.TableRef;
import com.datacube.spi.model.ViewInfo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL 元数据读取器：基于 {@code information_schema} / {@code pg_catalog}。
 */
public final class PgMetadataReader implements MetadataReader {

    private final Connection conn;

    public PgMetadataReader(Connection conn) {
        this.conn = conn;
    }

    @Override
    public List<CatalogInfo> catalogs() throws SQLException {
        List<CatalogInfo> out = new ArrayList<>();
        String sql = "SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new CatalogInfo(rs.getString(1)));
        }
        return out;
    }

    @Override
    public List<SchemaInfo> schemas(String catalog) throws SQLException {
        List<SchemaInfo> out = new ArrayList<>();
        String sql = "SELECT schema_name FROM information_schema.schemata "
                + "WHERE schema_name NOT IN ('pg_catalog','information_schema') "
                + "AND schema_name NOT LIKE 'pg_%' ORDER BY schema_name";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new SchemaInfo(catalog, rs.getString(1)));
        }
        return out;
    }

    @Override
    public List<TableInfo> tables(String schema) throws SQLException {
        List<TableInfo> out = new ArrayList<>();
        String sql = "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TableInfo(schema, rs.getString(1), TableInfo.Kind.TABLE, null));
                }
            }
        }
        return out;
    }

    @Override
    public List<ViewInfo> views(String schema) throws SQLException {
        List<ViewInfo> out = new ArrayList<>();
        String sql = "SELECT table_name, view_definition FROM information_schema.views "
                + "WHERE table_schema = ? ORDER BY table_name";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ViewInfo(schema, rs.getString(1), rs.getString(2)));
                }
            }
        }
        return out;
    }

    @Override
    public List<ColumnInfo> columns(TableRef t) throws SQLException {
        Set<String> pkCols = primaryKeyColumns(t);
        Map<String, String> comments = columnComments(t);
        List<ColumnInfo> out = new ArrayList<>();
        String sql = "SELECT column_name, data_type, is_nullable, column_default, ordinal_position "
                + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? "
                + "ORDER BY ordinal_position";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.schema());
            ps.setString(2, t.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("column_name");
                    out.add(new ColumnInfo(
                            name,
                            rs.getString("data_type"),
                            "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                            rs.getString("column_default"),
                            rs.getInt("ordinal_position"),
                            pkCols.contains(name),
                            comments.get(name)));
                }
            }
        }
        return out;
    }

    /** 批量取回列注释（{@code col_description}）：列名 → 注释（空注释不入图）。 */
    private Map<String, String> columnComments(TableRef t) throws SQLException {
        Map<String, String> out = new HashMap<>();
        String sql = "SELECT a.attname AS column_name, col_description(a.attrelid, a.attnum) AS comment "
                + "FROM pg_attribute a "
                + "JOIN pg_class c ON c.oid = a.attrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.schema());
            ps.setString(2, t.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String comment = rs.getString("comment");
                    if (comment != null && !comment.isEmpty()) {
                        out.put(rs.getString("column_name"), comment);
                    }
                }
            }
        }
        return out;
    }

    private Set<String> primaryKeyColumns(TableRef t) throws SQLException {
        Set<String> pk = new HashSet<>();
        String sql = "SELECT kcu.column_name FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema "
                + "WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = ? AND tc.table_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.schema());
            ps.setString(2, t.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) pk.add(rs.getString(1));
            }
        }
        return pk;
    }

    @Override
    public List<IndexInfo> indexes(TableRef t) throws SQLException {
        List<IndexInfo> out = new ArrayList<>();
        String sql = "SELECT i.relname AS index_name, ix.indisunique AS is_unique, "
                + "a.attname AS column_name, array_position(ix.indkey, a.attnum) AS ord "
                + "FROM pg_class ti "
                + "JOIN pg_namespace ns ON ns.oid = ti.relnamespace "
                + "JOIN pg_index ix ON ix.indrelid = ti.oid "
                + "JOIN pg_class i ON i.oid = ix.indexrelid "
                + "JOIN pg_attribute a ON a.attrelid = ti.oid AND a.attnum = ANY(ix.indkey) "
                + "WHERE ns.nspname = ? AND ti.relname = ? "
                + "ORDER BY i.relname, ord";
        // 按索引名聚合列
        java.util.LinkedHashMap<String, List<String>> byIndex = new java.util.LinkedHashMap<>();
        java.util.Map<String, Boolean> uniqueMap = new java.util.HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.schema());
            ps.setString(2, t.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String idx = rs.getString("index_name");
                    byIndex.computeIfAbsent(idx, k -> new ArrayList<>()).add(rs.getString("column_name"));
                    uniqueMap.put(idx, rs.getBoolean("is_unique"));
                }
            }
        }
        for (var e : byIndex.entrySet()) {
            out.add(new IndexInfo(e.getKey(), uniqueMap.getOrDefault(e.getKey(), false), e.getValue()));
        }
        return out;
    }

    @Override
    public List<ConstraintInfo> constraints(TableRef t) throws SQLException {
        List<ConstraintInfo> out = new ArrayList<>();
        String sql = "SELECT tc.constraint_name, tc.constraint_type, kcu.column_name "
                + "FROM information_schema.table_constraints tc "
                + "LEFT JOIN information_schema.key_column_usage kcu "
                + "ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema "
                + "WHERE tc.table_schema = ? AND tc.table_name = ? "
                + "ORDER BY tc.constraint_name, kcu.ordinal_position";
        java.util.LinkedHashMap<String, List<String>> cols = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> types = new java.util.HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.schema());
            ps.setString(2, t.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("constraint_name");
                    types.put(name, rs.getString("constraint_type"));
                    String col = rs.getString("column_name");
                    List<String> list = cols.computeIfAbsent(name, k -> new ArrayList<>());
                    if (col != null) list.add(col);
                }
            }
        }
        for (var e : cols.entrySet()) {
            out.add(new ConstraintInfo(e.getKey(), mapType(types.get(e.getKey())), e.getValue(), null));
        }
        return out;
    }

    private static ConstraintInfo.Type mapType(String pgType) {
        if (pgType == null) return ConstraintInfo.Type.CHECK;
        return switch (pgType) {
            case "PRIMARY KEY" -> ConstraintInfo.Type.PRIMARY_KEY;
            case "UNIQUE" -> ConstraintInfo.Type.UNIQUE;
            case "FOREIGN KEY" -> ConstraintInfo.Type.FOREIGN_KEY;
            default -> ConstraintInfo.Type.CHECK;
        };
    }

    @Override
    public List<RoutineInfo> routines(String schema) throws SQLException {
        List<RoutineInfo> out = new ArrayList<>();
        String sql = "SELECT routine_name, routine_type, data_type FROM information_schema.routines "
                + "WHERE specific_schema = ? ORDER BY routine_name";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString("routine_type");
                    RoutineInfo.Kind kind = "PROCEDURE".equalsIgnoreCase(type)
                            ? RoutineInfo.Kind.PROCEDURE : RoutineInfo.Kind.FUNCTION;
                    out.add(new RoutineInfo(schema, rs.getString("routine_name"), kind, rs.getString("data_type")));
                }
            }
        }
        return out;
    }

    @Override
    public List<SequenceInfo> sequences(String schema) throws SQLException {
        List<SequenceInfo> out = new ArrayList<>();
        String sql = "SELECT sequence_name FROM information_schema.sequences "
                + "WHERE sequence_schema = ? ORDER BY sequence_name";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new SequenceInfo(schema, rs.getString(1)));
            }
        }
        return out;
    }
}
