package com.datacube.service;

import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.model.*;
import java.sql.*;
import java.util.*;

/** One explicit catalog search in one schema, on an owned connection; never executes supplied SQL. */
public final class SchemaMetadataSearch {
    public static final int MAX_HITS = 200, TIMEOUT_SECONDS = 10;
    public enum Mode {
        COLUMN_NAME("字段名"), OBJECT_COMMENT("对象注释"), COLUMN_COMMENT("字段注释");
        private final String label;
        Mode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public record Request(ConnConfig connection, String schema, Mode mode, String term) {
        public Request {
            Objects.requireNonNull(connection); Objects.requireNonNull(mode);
            if ((connection.type() != DbType.POSTGRESQL && connection.type() != DbType.ORACLE)
                    || schema == null || schema.isEmpty() || schema.length() > 1024
                    || term == null || term.isBlank() || term.length() > 256)
                throw new IllegalArgumentException("Explicit schema and bounded search term required");
        }
        @Override public String toString() { return "Schema metadata search request"; }
    }
    public record Hit(TableInfo object, Mode mode, String column, String excerpt) {}
    public record Result(List<Hit> hits, boolean truncated) {
        public Result { hits = List.copyOf(hits); }
        public String notice() {
            return "已读取 " + hits.size() + " 条匹配" + (truncated ? "，仅保留前 200 条，请缩小搜索词" : "")
                    + "；仅当前账户可见元数据，未匹配不代表对象不存在。注释仅预览前 512 字符。";
        }
    }
    @FunctionalInterface public interface Opener { Connection open(ConnConfig target) throws SQLException; }
    private SchemaMetadataSearch() {}

    public static Result search(Request request, Opener opener, SqlExecutionControl control) throws SQLException {
        check(control);
        String sql = sql(request.connection().type(), request.mode());
        try (Connection connection = opener.open(request.connection())) {
            check(control);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                var activation = control.activate(statement, TIMEOUT_SECONDS);
                try {
                    statement.setMaxRows(MAX_HITS + 1);
                    statement.setString(1, request.schema()); statement.setString(2, request.term());
                    control.ensureNotCancelled(activation);
                    var hits = new ArrayList<Hit>();
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            check(control);
                            if (hits.size() == MAX_HITS) return new Result(hits, true);
                            String name = identity(rs.getString(1)), kind = rs.getString(2);
                            String column = request.mode() == Mode.OBJECT_COMMENT ? null : identity(rs.getString(3));
                            if (kind == null || !Set.of("TABLE", "BASE TABLE", "VIEW").contains(kind)) throw new SQLException("Invalid catalog kind");
                            String excerpt = rs.getString(4);
                            if (excerpt == null) excerpt = "";
                            if (excerpt.length() > 1024) throw new SQLException("Invalid catalog preview");
                            hits.add(new Hit(new TableInfo(request.schema(), name,
                                    kind.equals("VIEW") ? TableInfo.Kind.VIEW : TableInfo.Kind.TABLE, null), request.mode(), column, excerpt));
                        }
                    }
                    check(control);
                    return new Result(hits, false);
                } finally { control.release(activation); }
            }
        }
    }
    private static String identity(String value) throws SQLException {
        if (value == null || value.isEmpty() || value.length() > 1024) throw new SQLException("Invalid catalog identity");
        return value;
    }
    private static void check(SqlExecutionControl control) throws SQLException {
        if (control.cancellationRequested() || Thread.currentThread().isInterrupted()) throw new SQLException("Catalog search cancelled");
    }
    private static String sql(DbType type, Mode mode) {
        if (type == DbType.ORACLE) {
            String from = " FROM ALL_TAB_COMMENTS t";
            String value = "t.COMMENTS", column = "NULL";
            if (mode != Mode.OBJECT_COMMENT) {
                from += " JOIN ALL_COL_COMMENTS c ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME";
                column = "c.COLUMN_NAME"; value = mode == Mode.COLUMN_NAME ? column : "c.COMMENTS";
            }
            return "SELECT t.TABLE_NAME, t.TABLE_TYPE, " + column + ", SUBSTR(" + value + ",1,512)" + from
                    + " WHERE t.OWNER = ? AND t.TABLE_TYPE IN ('TABLE','VIEW') AND INSTR(LOWER(" + value + "), LOWER(?)) > 0"
                    + " ORDER BY t.TABLE_NAME, t.TABLE_TYPE" + (mode == Mode.OBJECT_COMMENT ? "" : ", c.COLUMN_NAME");
        }
        String from = " FROM information_schema.tables t JOIN pg_catalog.pg_namespace n ON n.nspname = t.table_schema"
                + " JOIN pg_catalog.pg_class c ON c.relnamespace = n.oid AND c.relname = t.table_name";
        String value = "pg_catalog.obj_description(c.oid, 'pg_class')", column = "NULL";
        if (mode != Mode.OBJECT_COMMENT) {
            from += " JOIN information_schema.columns f ON f.table_schema = t.table_schema AND f.table_name = t.table_name"
                    + " JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attname = f.column_name AND a.attnum > 0 AND NOT a.attisdropped";
            column = "f.column_name"; value = mode == Mode.COLUMN_NAME ? column : "pg_catalog.col_description(c.oid, a.attnum)";
        }
        return "SELECT t.table_name, t.table_type, " + column + ", left(" + value + ",512)" + from
                + " WHERE t.table_schema = ? AND t.table_type IN ('BASE TABLE','VIEW') AND strpos(lower(" + value + "), lower(?)) > 0"
                + " ORDER BY t.table_name, t.table_type" + (mode == Mode.OBJECT_COMMENT ? "" : ", f.column_name");
    }
}
