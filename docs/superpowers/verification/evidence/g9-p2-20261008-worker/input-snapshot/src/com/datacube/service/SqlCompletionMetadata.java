package com.datacube.service;

import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.sqleditor.SqlCompletionContext;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** A caller-owned connection and a single exact catalog query. Never uses the object tree connection. */
public final class SqlCompletionMetadata {
    public static final int TIMEOUT_SECONDS = 5;
    public static final int MAX_COLUMNS = 256, MAX_TABLES = 500;
    public record Target(ConnConfig connection, String schema, String table) {
        public Target {
            if (connection == null || connection.type() == DbType.REDIS || schema == null || schema.isBlank())
                throw new IllegalArgumentException("Completion requires an explicit relational target");
        }
        @Override public String toString() { return "SQL completion metadata target"; }
    }
    public record Names(List<String> values, String notice) { public Names { values = List.copyOf(values); } }
    @FunctionalInterface public interface Opener { Connection open(ConnConfig config) throws SQLException; }
    private SqlCompletionMetadata() {}

    public static Names load(Target target, Opener opener, SqlExecutionControl control) throws SQLException {
        check(control);
        boolean oracle = target.connection().type() == DbType.ORACLE, columns = target.table() != null;
        int limit = columns ? MAX_COLUMNS : MAX_TABLES;
        String sql = oracle
                ? columns ? "SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID"
                          : "SELECT OBJECT_NAME FROM ALL_OBJECTS WHERE OWNER = ? AND OBJECT_TYPE IN ('TABLE', 'VIEW') ORDER BY OBJECT_NAME"
                : columns ? "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position"
                          : "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name";
        try (Connection connection = opener.open(target.connection())) {
            check(control);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                var activation = control.activate(statement, TIMEOUT_SECONDS);
                try {
                    statement.setMaxRows(limit + 1);
                    statement.setString(1, target.schema());
                    if (columns) statement.setString(2, target.table());
                    control.ensureNotCancelled(activation);
                    var values = new ArrayList<String>();
                    int read = 0;
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            check(control);
                            if (read++ == limit) return new Names(values, "仅显示前 " + limit + " 个元数据名称");
                            // SQL identifiers have bounded server limits; reject abnormal driver values.
                            String name = rs.getString(1);
                            if (name != null && !name.isEmpty() && name.length() <= 512)
                                values.add(SqlCompletionContext.identifier(name, oracle));
                        }
                    }
                    return new Names(values, values.isEmpty() ? "目标未返回可见名称（可能不存在或无权限）" : "");
                } finally { control.release(activation); }
            }
        }
    }
    private static void check(SqlExecutionControl control) throws SQLException {
        if (control.cancellationRequested() || Thread.currentThread().isInterrupted()) throw new SQLException("Completion cancelled");
    }
}
