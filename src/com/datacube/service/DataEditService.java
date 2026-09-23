package com.datacube.service;

import com.datacube.spi.DataEditor;
import com.datacube.spi.model.EditableColumn;
import com.datacube.spi.model.RowKey;
import com.datacube.spi.model.TableRef;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 表数据编辑服务：编排 {@link DataEditor} 提供行级 INSERT/UPDATE/DELETE。
 *
 * <p>与只读的 {@link DataBrowseService} 对称。所有方法显式接收 {@code connId}，
 * 零 JavaFX 依赖。
 */
public final class DataEditService {

    private final ConnectionManager connections;

    public DataEditService(ConnectionManager connections) {
        this.connections = connections;
    }

    private DataEditor editor(String connId) throws SQLException {
        Connection c = connections.acquire(connId);
        return connections.provider(connId).dataEditor(c);
    }

    public List<EditableColumn> columns(String connId, TableRef t) throws SQLException {
        return editor(connId).columns(t);
    }

    public int insert(String connId, TableRef t, LinkedHashMap<String, String> values) throws SQLException {
        return prepareInsert(target(connId), t, values).execute(null);
    }

    public int update(String connId, TableRef t, LinkedHashMap<String, String> newValues, RowKey key) throws SQLException {
        return prepareUpdate(target(connId), t, newValues, key).execute(null);
    }

    public int delete(String connId, TableRef t, RowKey key) throws SQLException {
        return prepareDelete(target(connId), t, key).execute(null);
    }

    public WriteTarget target(String connId) { return connections.writeTarget(connId); }

    public WriteOperation<Integer> prepareInsert(WriteTarget target, TableRef table,
                                                LinkedHashMap<String, String> values) {
        var copy = new LinkedHashMap<>(values);
        return connections.prepareWrite(target, "新增行", table.qualified() + "，1 行",
                (c, p) -> p.dataEditor(c).insert(table, new LinkedHashMap<>(copy)));
    }

    public WriteOperation<Integer> prepareUpdate(WriteTarget target, TableRef table,
                                                LinkedHashMap<String, String> values, RowKey key) {
        var copy = new LinkedHashMap<>(values);
        RowKey frozen = freezeKey(key);
        return connections.prepareWrite(target, "修改行", table.qualified() + "，1 行",
                (c, p) -> p.dataEditor(c).update(table, new LinkedHashMap<>(copy), frozen));
    }

    public WriteOperation<Integer> prepareDelete(WriteTarget target, TableRef table, RowKey key) {
        RowKey frozen = freezeKey(key);
        return connections.prepareWrite(target, "删除行", table.qualified() + "，1 行",
                (c, p) -> p.dataEditor(c).delete(table, frozen));
    }

    private static RowKey freezeKey(RowKey key) {
        return new RowKey(key.columns(), key.values().stream().map(DataEditService::freezeValue).toList());
    }

    static Object freezeValue(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            java.sql.Timestamp copy = new java.sql.Timestamp(timestamp.getTime());
            copy.setNanos(timestamp.getNanos());
            return copy;
        }
        if (value instanceof java.sql.Date date) return new java.sql.Date(date.getTime());
        if (value instanceof java.sql.Time time) return new java.sql.Time(time.getTime());
        if (value instanceof byte[] bytes) return bytes.clone();
        if (value == null || value instanceof String || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof Float
                || value instanceof Double || value instanceof java.math.BigDecimal
                || value instanceof java.math.BigInteger
                || value instanceof Boolean || value instanceof java.time.Instant
                || value instanceof java.time.LocalDate || value instanceof java.time.LocalTime
                || value instanceof java.time.LocalDateTime || value instanceof java.time.OffsetTime
                || value instanceof java.time.OffsetDateTime || value instanceof java.time.ZonedDateTime
                || value instanceof java.util.UUID) return value;
        throw new IllegalArgumentException("不支持的可变写入参数类型");
    }
}
