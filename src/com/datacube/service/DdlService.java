package com.datacube.service;

import com.datacube.spi.DdlGenerator;
import com.datacube.spi.model.RoutineRef;
import com.datacube.spi.model.TableRef;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * DDL 查看服务：编排 {@link DdlGenerator} 生成对象的 CREATE 语句。
 *
 * <p>所有方法显式接收 {@code connId}，零 JavaFX 依赖。
 */
public final class DdlService {

    private final ConnectionManager connections;

    public DdlService(ConnectionManager connections) {
        this.connections = connections;
    }

    private DdlGenerator generator(String connId) throws SQLException {
        Connection c = connections.acquire(connId);
        return connections.provider(connId).ddlGenerator(c);
    }

    public String tableDdl(String connId, TableRef t) throws SQLException {
        return generator(connId).tableDdl(t);
    }

    public String viewDdl(String connId, TableRef t) throws SQLException {
        return generator(connId).viewDdl(t);
    }

    public String routineDdl(String connId, RoutineRef r) throws SQLException {
        return generator(connId).routineDdl(r);
    }

    public String sequenceDdl(String connId, String schema, String name) throws SQLException {
        return generator(connId).sequenceDdl(schema, name);
    }
}
