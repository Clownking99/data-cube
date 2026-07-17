package com.datacube.provider.postgres;

import com.datacube.spi.DataAccessor;
import com.datacube.spi.SqlDialect;
import com.datacube.spi.model.PagedResult;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.SortKey;
import com.datacube.spi.model.TableRef;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * PostgreSQL 数据访问器：表数据分页读取（只读），SQL 拼接委托 {@link SqlDialect}。
 */
public final class PgDataAccessor implements DataAccessor {

    private final Connection conn;
    private final SqlDialect dialect;

    public PgDataAccessor(Connection conn, SqlDialect dialect) {
        this.conn = conn;
        this.dialect = dialect;
    }

    private String qualified(TableRef t) {
        String schema = t.schema();
        String name = dialect.quoteIdentifier(t.name());
        return (schema == null || schema.isEmpty()) ? name : dialect.quoteIdentifier(schema) + "." + name;
    }

    @Override
    public PagedResult page(TableRef t, long offset, int limit, List<SortKey> sorts, String filter) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(qualified(t));
        if (filter != null && !filter.isBlank()) {
            sql.append(" WHERE ").append(filter);
        }
        if (sorts != null && !sorts.isEmpty()) {
            sql.append(" ORDER BY ");
            for (int i = 0; i < sorts.size(); i++) {
                if (i > 0) sql.append(", ");
                SortKey k = sorts.get(i);
                sql.append(dialect.quoteIdentifier(k.column())).append(k.ascending() ? " ASC" : " DESC");
            }
        }
        // 多取一行判断是否还有更多
        sql.append(' ').append(dialect.pageClause(offset, limit + 1));

        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql.toString())) {
            QueryResult qr = QueryResult.fromResultSet(rs, 0);
            List<List<Object>> rows = qr.rows;
            boolean hasMore = rows.size() > limit;
            List<List<Object>> pageRows = hasMore ? rows.subList(0, limit) : rows;
            return new PagedResult(qr.columns, List.copyOf(pageRows), hasMore);
        }
    }

    @Override
    public long count(TableRef t, String filter) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(qualified(t));
        if (filter != null && !filter.isBlank()) {
            sql.append(" WHERE ").append(filter);
        }
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql.toString())) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}
