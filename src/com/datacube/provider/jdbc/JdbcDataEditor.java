package com.datacube.provider.jdbc;

import com.datacube.spi.DataEditor;
import com.datacube.spi.SqlDialect;
import com.datacube.spi.model.EditableColumn;
import com.datacube.spi.model.RowKey;
import com.datacube.spi.model.TableRef;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static java.sql.Types.BIGINT;
import static java.sql.Types.BIT;
import static java.sql.Types.BLOB;
import static java.sql.Types.BINARY;
import static java.sql.Types.BOOLEAN;
import static java.sql.Types.CHAR;
import static java.sql.Types.CLOB;
import static java.sql.Types.DATE;
import static java.sql.Types.DECIMAL;
import static java.sql.Types.DOUBLE;
import static java.sql.Types.FLOAT;
import static java.sql.Types.INTEGER;
import static java.sql.Types.LONGNVARCHAR;
import static java.sql.Types.LONGVARBINARY;
import static java.sql.Types.LONGVARCHAR;
import static java.sql.Types.NCHAR;
import static java.sql.Types.NCLOB;
import static java.sql.Types.NUMERIC;
import static java.sql.Types.NVARCHAR;
import static java.sql.Types.REAL;
import static java.sql.Types.ROWID;
import static java.sql.Types.SMALLINT;
import static java.sql.Types.TIME;
import static java.sql.Types.TIMESTAMP;
import static java.sql.Types.TIMESTAMP_WITH_TIMEZONE;
import static java.sql.Types.TINYINT;
import static java.sql.Types.VARBINARY;
import static java.sql.Types.VARCHAR;

/**
 * 通用 JDBC 数据编辑器：单表行级 INSERT/UPDATE/DELETE（参数化）。
 *
 * <p>DML 为标准 SQL，唯一库差异（标识符引用）经 {@link SqlDialect#quoteIdentifier}
 * 收敛，故 Oracle 与 PostgreSQL 共用本实现。UPDATE/DELETE 走事务护栏：
 * 影响行数不为 1 立即回滚（见 {@link RowGuardException}）。
 */
public final class JdbcDataEditor implements DataEditor {

    private final Connection conn;
    private final SqlDialect dialect;
    private final Map<TableRef, List<EditableColumn>> columnCache = new HashMap<>();

    public JdbcDataEditor(Connection conn, SqlDialect dialect) {
        this.conn = conn;
        this.dialect = dialect;
    }

    @Override
    public List<EditableColumn> columns(TableRef t) throws SQLException {
        List<EditableColumn> cached = columnCache.get(t);
        if (cached != null) return cached;

        Set<String> pk = primaryKeys(t);
        Map<String, String> comments;
        try {
            comments = dialect.columnComments(conn, t.schema(), t.name());
        } catch (SQLException e) {
            comments = java.util.Collections.emptyMap();
        }
        String sql = "SELECT * FROM " + qualified(t) + " WHERE 1=0";
        List<EditableColumn> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String name = md.getColumnLabel(i);
                int type = md.getColumnType(i);
                String typeName = md.getColumnTypeName(i);
                boolean nullable = md.isNullable(i) != ResultSetMetaData.columnNoNulls;
                boolean auto = md.isAutoIncrement(i);
                boolean readOnly = md.isReadOnly(i);
                boolean isPk = pk.contains(name);
                boolean editable = !readOnly && !isBinaryOrLob(type);
                list.add(new EditableColumn(name, type, typeName, nullable, isPk, auto, editable, comments.get(name)));
            }
        }
        List<EditableColumn> result = List.copyOf(list);
        columnCache.put(t, result);
        return result;
    }

    @Override
    public int insert(TableRef t, LinkedHashMap<String, String> values) throws SQLException {
        if (values.isEmpty()) {
            throw new SQLException("插入失败：没有任何列值");
        }
        Map<String, Integer> types = typeMap(t);
        List<String> cols = new ArrayList<>(values.keySet());
        String sql = buildInsertSql(qualified(t), cols, dialect::quoteIdentifier);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (String c : cols) {
                bindValue(ps, i++, values.get(c), jdbcTypeOf(types, c), c);
            }
            return ps.executeUpdate();
        }
    }

    @Override
    public int update(TableRef t, LinkedHashMap<String, String> newValues, RowKey key) throws SQLException {
        if (newValues.isEmpty()) {
            throw new SQLException("更新失败：没有任何被修改的列");
        }
        requireKey(key);
        Map<String, Integer> types = typeMap(t);
        List<String> setCols = new ArrayList<>(newValues.keySet());
        String sql = buildUpdateSql(qualified(t), setCols, key, dialect::quoteIdentifier);
        return runGuarded(sql, ps -> {
            int i = 1;
            for (String c : setCols) {
                bindValue(ps, i++, newValues.get(c), jdbcTypeOf(types, c), c);
            }
            bindKeyValues(ps, i, key);
        });
    }

    @Override
    public int delete(TableRef t, RowKey key) throws SQLException {
        requireKey(key);
        String sql = buildDeleteSql(qualified(t), key, dialect::quoteIdentifier);
        return runGuarded(sql, ps -> bindKeyValues(ps, 1, key));
    }

    // ---------- 事务护栏 ----------

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    /** 单语句事务：影响 1 行提交，否则回滚并抛 {@link RowGuardException}。 */
    private int runGuarded(String sql, Binder binder) throws SQLException {
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            int n;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                binder.bind(ps);
                n = ps.executeUpdate();
            }
            if (n == 1) {
                conn.commit();
                return n;
            }
            conn.rollback();
            throw new RowGuardException(n);
        } catch (SQLException e) {
            if (!(e instanceof RowGuardException)) {
                safeRollback();
            }
            throw e;
        } finally {
            try {
                conn.setAutoCommit(prevAutoCommit);
            } catch (SQLException ignore) {
                // 恢复 autoCommit 失败不掩盖原始异常
            }
        }
    }

    private void safeRollback() {
        try {
            conn.rollback();
        } catch (SQLException ignore) {
            // 回滚失败不掩盖原始异常
        }
    }

    // ---------- 绑定 ----------

    /** 绑定 WHERE 中非 NULL 的旧值（NULL 列在 SQL 里已是 IS NULL，不占位）。 */
    private void bindKeyValues(PreparedStatement ps, int startIdx, RowKey key) throws SQLException {
        int idx = startIdx;
        List<Object> vals = key.values();
        for (Object v : vals) {
            if (v != null) {
                ps.setObject(idx++, v);
            }
        }
    }

    /** 文本→目标类型强转后绑定；转换失败抛携带列名与类型的可读异常。 */
    private void bindValue(PreparedStatement ps, int idx, String text, int jdbcType, String col) throws SQLException {
        Object v;
        try {
            v = coerce(text, jdbcType);
        } catch (RuntimeException ex) {
            throw new SQLException("列 [" + col + "] 的值 \"" + text + "\" 无法转换为 " + typeLabel(jdbcType), ex);
        }
        if (v == null) {
            ps.setNull(idx, jdbcType);
        } else {
            ps.setObject(idx, v, jdbcType);
        }
    }

    // ---------- 元数据 ----------

    private String qualified(TableRef t) {
        return qualify(t, dialect::quoteIdentifier);
    }

    private Map<String, Integer> typeMap(TableRef t) throws SQLException {
        Map<String, Integer> m = new HashMap<>();
        for (EditableColumn c : columns(t)) {
            m.put(c.name(), c.jdbcType());
        }
        return m;
    }

    private static int jdbcTypeOf(Map<String, Integer> types, String col) {
        Integer t = types.get(col);
        return t == null ? VARCHAR : t;
    }

    /** 主键列集合；取不到时返回空集（退化为全列匹配 + 护栏）。 */
    private Set<String> primaryKeys(TableRef t) {
        Set<String> pk = new LinkedHashSet<>();
        String schema = (t.schema() == null || t.schema().isEmpty()) ? null : t.schema();
        try {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getPrimaryKeys(null, schema, t.name())) {
                while (rs.next()) {
                    pk.add(rs.getString("COLUMN_NAME"));
                }
            }
        } catch (SQLException ignore) {
            // 取不到主键时退化为全列匹配，安全由行护栏兜底
        }
        return pk;
    }

    private static void requireKey(RowKey key) throws SQLException {
        if (key == null || key.columns().isEmpty()) {
            throw new SQLException("无法定位行：缺少主键或可匹配列");
        }
    }

    // ---------- 纯函数（可单测，不依赖连接） ----------

    static String qualify(TableRef t, UnaryOperator<String> quoteId) {
        String name = quoteId.apply(t.name());
        return (t.schema() == null || t.schema().isEmpty()) ? name : quoteId.apply(t.schema()) + "." + name;
    }

    static String buildInsertSql(String qualified, List<String> cols, UnaryOperator<String> quoteId) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(qualified).append(" (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quoteId.apply(cols.get(i)));
        }
        sb.append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('?');
        }
        return sb.append(')').toString();
    }

    static String buildUpdateSql(String qualified, List<String> setCols, RowKey key, UnaryOperator<String> quoteId) {
        StringBuilder sb = new StringBuilder("UPDATE ").append(qualified).append(" SET ");
        for (int i = 0; i < setCols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quoteId.apply(setCols.get(i))).append(" = ?");
        }
        return sb.append(whereClause(key, quoteId)).toString();
    }

    static String buildDeleteSql(String qualified, RowKey key, UnaryOperator<String> quoteId) {
        return "DELETE FROM " + qualified + whereClause(key, quoteId);
    }

    /** WHERE 拼装：旧值非 NULL → {@code col = ?}；NULL → {@code col IS NULL}（不占位）。 */
    static String whereClause(RowKey key, UnaryOperator<String> quoteId) {
        List<String> cols = key.columns();
        List<Object> vals = key.values();
        StringBuilder sb = new StringBuilder(" WHERE ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(" AND ");
            sb.append(quoteId.apply(cols.get(i)));
            if (vals.get(i) == null) {
                sb.append(" IS NULL");
            } else {
                sb.append(" = ?");
            }
        }
        return sb.toString();
    }

    /**
     * 文本→目标类型强转。{@code null} 输入 → {@code null}（SQL NULL）；
     * 非字符类型的空白文本 → {@code null}；字符类型的空串保持空串。
     * 转换失败抛 {@link RuntimeException}（由调用方补列名后包装成 SQLException）。
     */
    static Object coerce(String text, int jdbcType) {
        if (text == null) return null;
        switch (jdbcType) {
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
            case DECIMAL:
            case NUMERIC:
            case REAL:
            case FLOAT:
            case DOUBLE:
                return text.isBlank() ? null : new BigDecimal(text.trim());
            case BOOLEAN:
            case BIT:
                return text.isBlank() ? null : parseBool(text.trim());
            case DATE:
                return text.isBlank() ? null : java.sql.Date.valueOf(text.trim());
            case TIME:
                return text.isBlank() ? null : java.sql.Time.valueOf(text.trim());
            case TIMESTAMP:
            case TIMESTAMP_WITH_TIMEZONE:
                return text.isBlank() ? null : java.sql.Timestamp.valueOf(normalizeTimestamp(text.trim()));
            case CHAR:
            case VARCHAR:
            case LONGVARCHAR:
            case NCHAR:
            case NVARCHAR:
            case LONGNVARCHAR:
            case CLOB:
            case NCLOB:
                return text;
            default:
                return text; // 交由驱动按目标类型强转（如 PG uuid/json 等）
        }
    }

    private static Boolean parseBool(String s) {
        switch (s.toLowerCase()) {
            case "true": case "t": case "1": case "yes": case "y":
                return Boolean.TRUE;
            case "false": case "f": case "0": case "no": case "n":
                return Boolean.FALSE;
            default:
                throw new IllegalArgumentException("非布尔值: " + s);
        }
    }

    /** 仅有日期部分时补 00:00:00，以满足 {@link java.sql.Timestamp#valueOf}。 */
    private static String normalizeTimestamp(String s) {
        return s.indexOf(' ') < 0 ? s + " 00:00:00" : s;
    }

    private static boolean isBinaryOrLob(int jdbcType) {
        switch (jdbcType) {
            case BLOB:
            case CLOB:
            case NCLOB:
            case BINARY:
            case VARBINARY:
            case LONGVARBINARY:
            case ROWID:
                return true;
            default:
                return false;
        }
    }

    private static String typeLabel(int jdbcType) {
        switch (jdbcType) {
            case TINYINT: case SMALLINT: case INTEGER: case BIGINT: return "整数";
            case DECIMAL: case NUMERIC: case REAL: case FLOAT: case DOUBLE: return "数值";
            case BOOLEAN: case BIT: return "布尔";
            case DATE: return "日期";
            case TIME: return "时间";
            case TIMESTAMP: case TIMESTAMP_WITH_TIMEZONE: return "时间戳";
            default: return "该类型";
        }
    }
}
