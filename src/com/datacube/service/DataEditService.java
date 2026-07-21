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
        return editor(connId).insert(t, values);
    }

    public int update(String connId, TableRef t, LinkedHashMap<String, String> newValues, RowKey key) throws SQLException {
        return editor(connId).update(t, newValues, key);
    }

    public int delete(String connId, TableRef t, RowKey key) throws SQLException {
        return editor(connId).delete(t, key);
    }
}
