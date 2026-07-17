package com.datacube.service;

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
import java.sql.SQLException;
import java.util.List;

/**
 * 对象树服务：编排 {@link MetadataReader} 为连接树提供懒加载数据。
 *
 * <p>所有方法显式接收 {@code connId}，零 JavaFX 依赖。
 */
public final class ObjectTreeService {

    private final ConnectionManager connections;

    public ObjectTreeService(ConnectionManager connections) {
        this.connections = connections;
    }

    private MetadataReader reader(String connId) throws SQLException {
        Connection c = connections.acquire(connId);
        return connections.provider(connId).metadataReader(c);
    }

    public boolean hasSchemaLevel(String connId) {
        return connections.provider(connId).dialect().hasSchemaLevel();
    }

    public List<CatalogInfo> catalogs(String connId) throws SQLException {
        return reader(connId).catalogs();
    }

    public List<SchemaInfo> schemas(String connId, String catalog) throws SQLException {
        return reader(connId).schemas(catalog);
    }

    public List<TableInfo> tables(String connId, String schema) throws SQLException {
        return reader(connId).tables(schema);
    }

    public List<ViewInfo> views(String connId, String schema) throws SQLException {
        return reader(connId).views(schema);
    }

    public List<RoutineInfo> routines(String connId, String schema) throws SQLException {
        return reader(connId).routines(schema);
    }

    public List<SequenceInfo> sequences(String connId, String schema) throws SQLException {
        return reader(connId).sequences(schema);
    }

    public List<ColumnInfo> columns(String connId, TableRef t) throws SQLException {
        return reader(connId).columns(t);
    }

    public List<IndexInfo> indexes(String connId, TableRef t) throws SQLException {
        return reader(connId).indexes(t);
    }

    public List<ConstraintInfo> constraints(String connId, TableRef t) throws SQLException {
        return reader(connId).constraints(t);
    }
}
