package com.datacube.service;

import com.datacube.spi.DataAccessor;
import com.datacube.spi.model.PagedResult;
import com.datacube.spi.model.SortKey;
import com.datacube.spi.model.TableRef;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 表数据浏览服务：编排 {@link DataAccessor} 提供分页/计数（一期只读）。
 *
 * <p>所有方法显式接收 {@code connId}，零 JavaFX 依赖。
 */
public final class DataBrowseService {

    private final ConnectionManager connections;

    public DataBrowseService(ConnectionManager connections) {
        this.connections = connections;
    }

    private DataAccessor accessor(String connId) throws SQLException {
        Connection c = connections.acquire(connId);
        return connections.provider(connId).dataAccessor(c);
    }

    public PagedResult page(String connId, TableRef t, long offset, int limit,
                            List<SortKey> sorts, String filter) throws SQLException {
        return accessor(connId).page(t, offset, limit, sorts, filter);
    }

    public long count(String connId, TableRef t, String filter) throws SQLException {
        return accessor(connId).count(t, filter);
    }
}
