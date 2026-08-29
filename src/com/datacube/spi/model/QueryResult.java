package com.datacube.spi.model;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQL 执行结果包装：与 UI 完全解耦，CLI 与 GUI 共用。
 *
 * <p>三种构造方式：
 * <ul>
 *   <li>{@link #query(List, List, long)}：SELECT 结果</li>
 *   <li>{@link #update(long, int)}：DML/DDL 影响行数</li>
 *   <li>{@link #error(String, long)}：执行异常</li>
 * </ul>
 */
public final class QueryResult {

    /** 类型：查询 / 更新 / 错误 */
    public enum Kind { QUERY, UPDATE, ERROR }

    /** ERROR 的细分原因；保留 {@link Kind#ERROR} 以兼容现有调用方。 */
    public enum FailureKind { SQL_ERROR, CANCELLED, TIMEOUT }

    public final Kind kind;
    /** 列名（仅 QUERY 有） */
    public final List<String> columns;
    /** 列注释（仅 QUERY 有；与 {@link #columns} 平行，元素可为 null；由 provider best-effort 填充） */
    public final List<String> columnComments;
    /** 列元数据（仅 QUERY 有；与 {@link #columns} 平行） */
    public final List<ResultColumn> resultColumns;
    /** 数据行（仅 QUERY 有；每个 List<Object> 对应一行） */
    public final List<List<Object>> rows;
    /** 受影响行数（仅 UPDATE 有；-1 表示无信息） */
    public final int updateCount;
    /** 耗时（毫秒） */
    public final long elapsedMillis;
    /** 错误信息（仅 ERROR 有） */
    public final String errorMessage;
    /** 错误细分（仅 ERROR 有；QUERY/UPDATE 为 null） */
    public final FailureKind failureKind;
    /** 是否因达到最大行数限制而仍有未读取行 */
    public final boolean truncated;

    private QueryResult(Kind kind, List<String> columns, List<String> columnComments,
                        List<List<Object>> rows, int updateCount, long elapsedMillis, String errorMessage,
                        FailureKind failureKind, List<ResultColumn> resultColumns, boolean truncated) {
        this.kind = kind;
        this.columns = columns != null ? columns : Collections.emptyList();
        this.columnComments = columnComments != null ? columnComments : Collections.emptyList();
        this.rows = rows != null ? rows : Collections.emptyList();
        this.updateCount = updateCount;
        this.elapsedMillis = elapsedMillis;
        this.errorMessage = errorMessage;
        this.failureKind = failureKind;
        this.resultColumns = resultColumns != null ? List.copyOf(resultColumns) : Collections.emptyList();
        this.truncated = truncated;
    }

    public static QueryResult query(List<String> columns, List<List<Object>> rows, long elapsedMillis) {
        List<ResultColumn> metadata = new ArrayList<>();
        if (columns != null) {
            for (int i = 0; i < columns.size(); i++) metadata.add(ResultColumn.unknown(i, columns.get(i)));
        }
        return new QueryResult(Kind.QUERY, columns, null, rows, -1, elapsedMillis, null, null, metadata, false);
    }

    public static QueryResult queryWithMetadata(
            List<ResultColumn> columns, List<List<Object>> rows,
            long elapsedMillis, boolean truncated) {
        List<ResultColumn> metadata = List.copyOf(columns);
        List<String> labels = metadata.stream().map(ResultColumn::label).toList();
        return new QueryResult(Kind.QUERY, labels, null, rows, -1, elapsedMillis, null, null, metadata, truncated);
    }

    public static QueryResult update(long elapsedMillis, int updateCount) {
        return new QueryResult(Kind.UPDATE, null, null, null, updateCount, elapsedMillis, null, null, null, false);
    }

    public static QueryResult error(String errorMessage, long elapsedMillis) {
        return failure(FailureKind.SQL_ERROR, errorMessage, elapsedMillis);
    }

    public static QueryResult cancelled(String errorMessage, long elapsedMillis) {
        return failure(FailureKind.CANCELLED, errorMessage, elapsedMillis);
    }

    public static QueryResult timeout(String errorMessage, long elapsedMillis) {
        return failure(FailureKind.TIMEOUT, errorMessage, elapsedMillis);
    }

    private static QueryResult failure(FailureKind kind, String message, long elapsedMillis) {
        return new QueryResult(Kind.ERROR, null, null, null, -1, elapsedMillis, message, kind, null, false);
    }

    /**
     * 返回一个附加了列注释的副本（不改动行数据）。注释列表与 {@link #columns} 平行，
     * 元素可为 null（该列取不到注释）。仅对 QUERY 结果有意义。
     */
    public QueryResult withColumnComments(List<String> comments) {
        return new QueryResult(kind, columns, comments, rows, updateCount, elapsedMillis, errorMessage, failureKind,
                resultColumns, truncated);
    }

    /**
     * 从 ResultSet 读取全部行（受保护的最大行数限制避免 OOM）。
     */
    public static QueryResult fromResultSet(ResultSet rs, long elapsedMillis) throws SQLException {
        return fromResultSet(rs, elapsedMillis, 10_000);
    }

    /**
     * 从 ResultSet 读取最多 {@code maxRows} 行（限制保留内存）。
     * {@code maxRows <= 0} 视为不限制。
     */
    public static QueryResult fromResultSet(ResultSet rs, long elapsedMillis, int maxRows) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();
        List<ResultColumn> metadata = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            metadata.add(new ResultColumn(i - 1, md.getColumnLabel(i), md.getColumnType(i), md.getColumnTypeName(i)));
        }
        List<List<Object>> data = new ArrayList<>();
        int max = maxRows <= 0 ? Integer.MAX_VALUE : maxRows;
        int rowCount = 0;
        while (rowCount < max && rs.next()) {
            List<Object> row = new ArrayList<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                row.add(readCell(rs, i, md.getColumnType(i)));
            }
            data.add(row);
            rowCount++;
        }
        boolean truncated = maxRows > 0 && rowCount >= max && rs.next();
        return queryWithMetadata(metadata, data, elapsedMillis, truncated);
    }

    private static Object readCell(ResultSet rs, int idx, int sqlType) throws SQLException {
        Object v = rs.getObject(idx);
        if (v == null) return null;
        // 大字段截断
        if (v instanceof java.sql.Clob) {
            java.sql.Clob c = (java.sql.Clob) v;
            long len = c.length();
            String s = c.getSubString(1, (int) Math.min(500, len));
            c.free();
            return s + (len > 500 ? "..." : "");
        }
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            if (b.length > 64) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 64; i++) sb.append(String.format("%02x", b[i]));
                return sb.append("...(").append(b.length).append(" bytes)").toString();
            }
        }
        // Oracle BLOB 返回 java.sql.Blob（而非 byte[]）：取前 64 字节 hex 预览
        if (v instanceof java.sql.Blob) {
            java.sql.Blob blob = (java.sql.Blob) v;
            long len = blob.length();
            byte[] b = blob.getBytes(1, (int) Math.min(64, len));
            blob.free();
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            if (len > 64) sb.append("...(").append(len).append(" bytes)");
            return sb.toString();
        }
        return v;
    }

    @Override
    public String toString() {
        switch (kind) {
            case QUERY:   return "QUERY " + rows.size() + " rows in " + elapsedMillis + "ms";
            case UPDATE:  return "UPDATE " + updateCount + " rows in " + elapsedMillis + "ms";
            case ERROR:   return "ERROR in " + elapsedMillis + "ms: " + errorMessage;
            default:      return kind + " " + elapsedMillis + "ms";
        }
    }
}
