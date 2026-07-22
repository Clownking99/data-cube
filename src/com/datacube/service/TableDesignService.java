package com.datacube.service;

import com.datacube.spi.ScriptErrorPolicy;
import com.datacube.spi.MetadataReader;
import com.datacube.spi.model.ColumnDraft;
import com.datacube.spi.model.ColumnInfo;
import com.datacube.spi.model.ConstraintInfo;
import com.datacube.spi.model.IndexDraft;
import com.datacube.spi.model.IndexInfo;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.spi.model.TableDraft;
import com.datacube.spi.model.TableRef;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 表设计服务：编排 {@link MetadataReader} 载入表结构、{@code TableDdlBuilder} 生成
 * CREATE/ALTER 预览、{@code SqlRunner} 执行 DDL。
 *
 * <p>所有方法显式接收 {@code connId}，零 JavaFX 依赖。DDL 方言差异全部封闭在
 * provider 层，本服务只做编排。
 */
public final class TableDesignService {

    /** 执行 DDL 无需保留查询行（DDL 无结果集）；沿用非查询语句默认。 */
    private static final int MAX_ROWS = 0;

    private final ConnectionManager connections;

    public TableDesignService(ConnectionManager connections) {
        this.connections = connections;
    }

    /**
     * 载入现有表结构为草稿：列（含类型/可空/默认/注释）、索引、主键。
     *
     * <p>主键列序与约束名优先由 {@link ConstraintInfo.Type#PRIMARY_KEY} 推导（保序）；
     * 约束缺失时退化用 {@link ColumnInfo#primaryKey} 标记（无约束名）。
     */
    public TableDraft load(String connId, TableRef t) throws SQLException {
        Connection c = connections.acquire(connId);
        MetadataReader reader = connections.provider(connId).metadataReader(c);

        List<ColumnInfo> cols = reader.columns(t);
        List<IndexInfo> idx = reader.indexes(t);
        List<ConstraintInfo> constraints = reader.constraints(t);

        String tableComment = null;
        List<ColumnDraft> columns = new ArrayList<>(cols.size());
        for (ColumnInfo ci : cols) {
            columns.add(new ColumnDraft(ci.name(), ci.typeName(), ci.nullable(),
                    ci.defaultValue(), ci.comment()));
        }

        List<String> primaryKey = new ArrayList<>();
        String primaryKeyName = null;
        for (ConstraintInfo cons : constraints) {
            if (cons.type() == ConstraintInfo.Type.PRIMARY_KEY) {
                primaryKey = new ArrayList<>(cons.columns());
                primaryKeyName = cons.name();
                break;
            }
        }
        if (primaryKey.isEmpty()) {
            for (ColumnInfo ci : cols) {
                if (ci.primaryKey()) primaryKey.add(ci.name());
            }
        }

        List<IndexDraft> indexes = new ArrayList<>(idx.size());
        for (IndexInfo ii : idx) {
            indexes.add(new IndexDraft(ii.name(), ii.unique(), ii.columns()));
        }

        return new TableDraft(t.schema(), t.name(), tableComment, columns,
                primaryKey, primaryKeyName, indexes);
    }

    /** 生成建表脚本预览。 */
    public String previewCreate(String connId, TableDraft d) {
        return connections.provider(connId).tableDdlBuilder().createTable(d);
    }

    /** 生成变更脚本预览（无差异返回空串）。 */
    public String previewAlter(String connId, TableDraft original, TableDraft edited) {
        return connections.provider(connId).tableDdlBuilder().alterScript(original, edited);
    }

    /**
     * 执行 DDL 脚本（多语句，分号分隔）；遇错处置交由 {@code policy}。
     *
     * @return 每条语句（含失败）的结果
     */
    public List<ScriptOutcome> execute(String connId, String ddl, ScriptErrorPolicy policy)
            throws SQLException {
        Connection conn = connections.acquire(connId);
        return connections.provider(connId).sqlRunner()
                .executeScript(conn, ddl, null, MAX_ROWS, policy);
    }
}
