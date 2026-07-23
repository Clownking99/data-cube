package com.datacube.service;

import com.datacube.spi.DdlGenerator;
import com.datacube.spi.model.RoutineRef;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.spi.model.SequenceDraft;
import com.datacube.spi.model.TableRef;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * DDL 查看服务：编排 {@link DdlGenerator} 生成对象的 CREATE 语句，并支持执行 DDL。
 *
 * <p>所有方法显式接收 {@code connId}，零 JavaFX 依赖。
 */
public final class DdlService {

    /** 执行 DDL 无需保留查询行（DDL 无结果集）。 */
    private static final int MAX_ROWS = 0;

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

    public String packageDdl(String connId, String schema, String name) throws SQLException {
        return generator(connId).packageDdl(schema, name);
    }

    public String triggerDdl(String connId, String schema, String name) throws SQLException {
        return generator(connId).triggerDdl(schema, name);
    }

    public String typeDdl(String connId, String schema, String name) throws SQLException {
        return generator(connId).typeDdl(schema, name);
    }

    public String sequenceDdl(String connId, String schema, String name) throws SQLException {
        return generator(connId).sequenceDdl(schema, name);
    }

    /** 载入序列完整属性为可编辑草稿（供序列设计器）。 */
    public SequenceDraft loadSequence(String connId, String schema, String name) throws SQLException {
        Connection c = connections.acquire(connId);
        return connections.provider(connId).metadataReader(c).sequence(schema, name);
    }

    /** diff 序列原始态与编辑态，生成 {@code ALTER SEQUENCE} 预览（无变更返回空串）。 */
    public String previewAlterSequence(String connId, SequenceDraft original, SequenceDraft edited) {
        return connections.provider(connId).sequenceDdlBuilder().alterScript(original, edited);
    }

    /**
     * 执行对象 DDL（可含 PL/SQL 多单元 spec+body）；遇错中止（policy=null）。
     *
     * @return 每个执行单元（含失败）的结果
     */
    public List<ScriptOutcome> executeDdl(String connId, String ddl) throws SQLException {
        Connection conn = connections.acquire(connId);
        return connections.provider(connId).sqlRunner()
                .executeScript(conn, ddl, null, MAX_ROWS, null);
    }
}
