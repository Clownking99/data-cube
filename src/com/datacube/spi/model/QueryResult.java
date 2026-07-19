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

    public final Kind kind;
    /** 列名（仅 QUERY 有） */
    public final List<String> columns;
    /** 列注释（仅 QUERY 有；与 {@link #columns} 平行，元素可为 null；由 provider best-effort 填充） */
    public final List<String> columnComments;
    /** 数据行（仅 QUERY 有；每个 List<Object> 对应一行） */
    public final List<List<Object>> rows;
    /** 受影响行数（仅 UPDATE 有；-1 表示无信息） */
    public final int updateCount;
    /** 耗时（毫秒） */
    public final long elapsedMillis;
    /** 错误信息（仅 ERROR 有） */
    public final String errorMessage;

    private QueryResult(Kind kind, List<String> columns, List<String> columnComments,
                        List<List<Object>> rows, int updateCount, long elapsedMillis, String errorMessage) {
        this.kind = kind;
        this.columns = columns != null ? columns : Collections.emptyList();
        this.columnComments = columnComments != null ? columnComments : Collections.emptyList();
        this.rows = rows != null ? rows : Collections.emptyList();
        this.updateCount = updateCount;
        this.elapsedMillis = elapsedMillis;
        this.errorMessage = errorMessage;
    }

    public static QueryResult query(List<String> columns, List<List<Object>> rows, long elapsedMillis) {
        return new QueryResult(Kind.QUERY, columns, null, rows, -1, elapsedMillis, null);
    }

    public static QueryResult update(long elapsedMillis, int updateCount) {
        return new QueryResult(Kind.UPDATE, null, null, null, updateCount, elapsedMillis, null);
    }

    public static QueryResult error(String errorMessage, long elapsedMillis) {
        return new QueryResult(Kind.ERROR, null, null, null, -1, elapsedMillis, errorMessage);
    }

    /**
     * 返回一个附加了列注释的副本（不改动行数据）。注释列表与 {@link #columns} 平行，
     * 元素可为 null（该列取不到注释）。仅对 QUERY 结果有意义。
     */
    public QueryResult withColumnComments(List<String> comments) {
        return new QueryResult(kind, columns, comments, rows, updateCount, elapsedMillis, errorMessage);
    }

    /**
     * 从 ResultSet 读取全部行（受保护的最大行数限制避免 OOM）。
     */
    public static QueryResult fromResultSet(ResultSet rs, long elapsedMillis) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();
        List<String> cols = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            cols.add(md.getColumnLabel(i));
        }
        List<List<Object>> data = new ArrayList<>();
        int max = 10_000;
        int rowCount = 0;
        while (rs.next() && rowCount < max) {
            List<Object> row = new ArrayList<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                row.add(readCell(rs, i, md.getColumnType(i)));
            }
            data.add(row);
            rowCount++;
        }
        return query(cols, data, elapsedMillis);
    }

    private static Object readCell(ResultSet rs, int idx, int sqlType) throws SQLException {
        Object v = rs.getObject(idx);
        if (v == null) return null;
        // 时间类型转为字符串，避免 TableView 默认渲染为长串数字
        if (v instanceof Timestamp) {
            return v.toString().substring(0, Math.min(19, v.toString().length()));
        }
        // 大字段截断
        if (v instanceof java.sql.Clob) {
            java.sql.Clob c = (java.sql.Clob) v;
            String s = c.getSubString(1, (int) Math.min(500, c.length()));
            c.free();
            return s + (c.length() > 500 ? "..." : "");
        }
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            if (b.length > 64) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 64; i++) sb.append(String.format("%02x", b[i]));
                return sb.append("...(").append(b.length).append(" bytes)").toString();
            }
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
