package com.datacube.provider.oracle;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Oracle 元数据读取器：基于 {@code ALL_*} 数据字典视图，按 {@code OWNER} 过滤。
 *
 * <p>Oracle user=schema，schema 列表取全部 OWNER（不排除系统 schema，由用户在树里自行折叠）。
 */
public final class OracleMetadataReader implements MetadataReader {

    private final Connection conn;

    public OracleMetadataReader(Connection conn) {
        this.conn = conn;
    }

    @Override
    public List<CatalogInfo> catalogs() throws SQLException {
        // Oracle 无供对象树使用的 catalog 层级
        return List.of();
    }

    @Override
    public List<SchemaInfo> schemas(String catalog) throws SQLException {
        List<SchemaInfo> out = new ArrayList<>();
        String sql = "SELECT DISTINCT OWNER FROM ALL_OBJECTS ORDER BY OWNER";
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new SchemaInfo(catalog, rs.getString(1)));
        }
        return out;
    }

    @Override
    public List<TableInfo> tables(String schema) throws SQLException {
        List<TableInfo> out = new ArrayList<>();
        String sql = "SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER = ? ORDER BY TABLE_NAME";
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
        String sql = "SELECT VIEW_NAME, TEXT FROM ALL_VIEWS WHERE OWNER = ? ORDER BY VIEW_NAME";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // TEXT 为 LONG，需在读取其它列后读取
                    String name = rs.getString("VIEW_NAME");
                    out.add(new ViewInfo(schema, name, rs.getString("TEXT")));
                }
            }
        }
        return out;
    }

    @Override
    public List<ColumnInfo> columns(TableRef t) throws SQLException {
        Set<String> pkCols = primaryKeyColumns(t);
        List<ColumnInfo> out = new ArrayList<>();
        // DATA_DEFAULT 为 LONG，置于末列并最后读取
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, "
                + "NULLABLE, COLUMN_ID, DATA_DEFAULT "
                + "FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.schema());
            ps.setString(2, t.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    String typeName = formatType(rs);
                    boolean nullable = "Y".equalsIgnoreCase(rs.getString("NULLABLE"));
                    int ordinal = rs.getInt("COLUMN_ID");
                    String def = rs.getString("DATA_DEFAULT");
                    out.add(new ColumnInfo(
                            name,
                            typeName,
                            nullable,
                            def == null ? null : def.trim(),
                            ordinal,
                            pkCols.contains(name),
                            null));
                }
            }
        }
        return out;
    }

    private static String formatType(ResultSet rs) throws SQLException {
        String type = rs.getString("DATA_TYPE");
        int len = rs.getInt("DATA_LENGTH");
        int precision = rs.getInt("DATA_PRECISION");
        boolean precisionNull = rs.wasNull();
        int scale = rs.getInt("DATA_SCALE");
        boolean scaleNull = rs.wasNull();

        if (type != null && (type.contains("CHAR"))) {
            return type + "(" + len + ")";
        }
        if ("NUMBER".equalsIgnoreCase(type) && !precisionNull) {
            return (scaleNull || scale == 0) ? "NUMBER(" + precision + ")"
                    : "NUMBER(" + precision + "," + scale + ")";
        }
        return type;
    }

    private Set<String> primaryKeyColumns(TableRef t) throws SQLException {
        Set<String> pk = new HashSet<>();
        String sql = "SELECT col.COLUMN_NAME FROM ALL_CONSTRAINTS c "
                + "JOIN ALL_CONS_COLUMNS col ON col.OWNER = c.OWNER AND col.CONSTRAINT_NAME = c.CONSTRAINT_NAME "
                + "WHERE c.CONSTRAINT_TYPE = 'P' AND c.OWNER = ? AND c.TABLE_NAME = ?";
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
        String sql = "SELECT i.INDEX_NAME, i.UNIQUENESS, c.COLUMN_NAME "
                + "FROM ALL_INDEXES i "
                + "JOIN ALL_IND_COLUMNS c ON c.INDEX_OWNER = i.OWNER AND c.INDEX_NAME = i.INDEX_NAME "
                + "WHERE i.TABLE_OWNER = ? AND i.TABLE_NAME = ? "
                + "ORDER BY i.INDEX_NAME, c.COLUMN_POSITION";
        java.util.LinkedHashMap<String, List<String>> byIndex = new java.util.LinkedHashMap<>();
        java.util.Map<String, Boolean> uniqueMap = new java.util.HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.schema());
            ps.setString(2, t.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String idx = rs.getString("INDEX_NAME");
                    byIndex.computeIfAbsent(idx, k -> new ArrayList<>()).add(rs.getString("COLUMN_NAME"));
                    uniqueMap.put(idx, "UNIQUE".equalsIgnoreCase(rs.getString("UNIQUENESS")));
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
        String sql = "SELECT c.CONSTRAINT_NAME, c.CONSTRAINT_TYPE, col.COLUMN_NAME "
                + "FROM ALL_CONSTRAINTS c "
                + "LEFT JOIN ALL_CONS_COLUMNS col ON col.OWNER = c.OWNER AND col.CONSTRAINT_NAME = c.CONSTRAINT_NAME "
                + "WHERE c.OWNER = ? AND c.TABLE_NAME = ? "
                + "ORDER BY c.CONSTRAINT_NAME, col.POSITION";
        java.util.LinkedHashMap<String, List<String>> cols = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> types = new java.util.HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.schema());
            ps.setString(2, t.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("CONSTRAINT_NAME");
                    types.put(name, rs.getString("CONSTRAINT_TYPE"));
                    String col = rs.getString("COLUMN_NAME");
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

    private static ConstraintInfo.Type mapType(String oraType) {
        if (oraType == null) return ConstraintInfo.Type.CHECK;
        return switch (oraType) {
            case "P" -> ConstraintInfo.Type.PRIMARY_KEY;
            case "U" -> ConstraintInfo.Type.UNIQUE;
            case "R" -> ConstraintInfo.Type.FOREIGN_KEY;
            default -> ConstraintInfo.Type.CHECK;
        };
    }

    @Override
    public List<RoutineInfo> routines(String schema) throws SQLException {
        List<RoutineInfo> out = new ArrayList<>();
        String sql = "SELECT OBJECT_NAME, OBJECT_TYPE FROM ALL_OBJECTS "
                + "WHERE OWNER = ? AND OBJECT_TYPE IN ('PROCEDURE','FUNCTION') ORDER BY OBJECT_NAME";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString("OBJECT_TYPE");
                    RoutineInfo.Kind kind = "PROCEDURE".equalsIgnoreCase(type)
                            ? RoutineInfo.Kind.PROCEDURE : RoutineInfo.Kind.FUNCTION;
                    out.add(new RoutineInfo(schema, rs.getString("OBJECT_NAME"), kind, null));
                }
            }
        }
        return out;
    }

    @Override
    public List<SequenceInfo> sequences(String schema) throws SQLException {
        List<SequenceInfo> out = new ArrayList<>();
        String sql = "SELECT SEQUENCE_NAME FROM ALL_SEQUENCES WHERE SEQUENCE_OWNER = ? ORDER BY SEQUENCE_NAME";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new SequenceInfo(schema, rs.getString(1)));
            }
        }
        return out;
    }
}
