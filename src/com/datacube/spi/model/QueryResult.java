package com.datacube.spi.model;

import java.io.Reader;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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

    /** ERROR 的细分原因；保留 {@link Kind#ERROR} 以兼容现有调用方。 */
    public enum FailureKind { SQL_ERROR, CANCELLED, TIMEOUT }

    public final Kind kind;
    /** 列名（仅 QUERY 有） */
    public final List<String> columns;
    /** 列注释（仅 QUERY 有；与 {@link #columns} 平行，元素可为 null；由 provider best-effort 填充） */
    public final List<String> columnComments;
    /** 列元数据（仅 QUERY 有；与 {@link #columns} 平行） */
    public final List<ResultColumn> resultColumns;
    /** 数据行（仅 QUERY 有；每个 List<Object> 对应一行） */
    public final List<List<Object>> rows;
    /** 受影响行数（仅 UPDATE 有；-1 表示无信息） */
    public final int updateCount;
    /** Legacy provider elapsed value; not end-to-end execution/fetch/render time. */
    public final long elapsedMillis;
    /** 错误信息（仅 ERROR 有） */
    public final String errorMessage;
    /** 错误细分（仅 ERROR 有；QUERY/UPDATE 为 null） */
    public final FailureKind failureKind;
    /** 是否因达到最大行数限制而仍有未读取行 */
    public final boolean truncated;
    /** Explicit data/metadata omissions independent of the per-query row limit. */
    public final String retentionNotice;
    /** Confirmed original-statement position, one-based Unicode code points; zero means unavailable. */
    public final int errorPosition;
    /** Monotonic measurements, -1 means not measured. Execute excludes schema setup and fetch. */
    public final long executionMillis;
    /** ResultSet acquisition/materialization; excludes optional column-comment metadata. */
    public final long fetchMillis;

    private QueryResult(Kind kind, List<String> columns, List<String> columnComments,
                        List<List<Object>> rows, int updateCount, long elapsedMillis, String errorMessage,
                        FailureKind failureKind, List<ResultColumn> resultColumns, boolean truncated) {
        this(kind, columns, columnComments, rows, updateCount, elapsedMillis, errorMessage,
                failureKind, resultColumns, truncated, "");
    }

    private QueryResult(Kind kind, List<String> columns, List<String> columnComments,
                        List<List<Object>> rows, int updateCount, long elapsedMillis, String errorMessage,
                        FailureKind failureKind, List<ResultColumn> resultColumns, boolean truncated, String notice) {
        this(kind, columns, columnComments, rows, updateCount, elapsedMillis, errorMessage, failureKind,
                resultColumns, truncated, notice, 0, -1, -1);
    }

    private QueryResult(Kind kind, List<String> columns, List<String> columnComments,
                        List<List<Object>> rows, int updateCount, long elapsedMillis, String errorMessage,
                        FailureKind failureKind, List<ResultColumn> resultColumns, boolean truncated, String notice,
                        int errorPosition, long executionMillis, long fetchMillis) {
        this.errorPosition = errorPosition;
        this.executionMillis = executionMillis;
        this.fetchMillis = fetchMillis;
        this.kind = kind;
        this.columns = columns;
        this.columnComments = columnComments;
        this.rows = rows;
        this.updateCount = updateCount;
        this.elapsedMillis = elapsedMillis;
        this.errorMessage = errorMessage;
        this.failureKind = failureKind;
        this.resultColumns = resultColumns;
        this.truncated = truncated;
        this.retentionNotice = notice;
    }

    public static QueryResult query(List<String> columns, List<List<Object>> rows, long elapsedMillis) {
        List<ResultColumn> metadata = new ArrayList<>();
        if (columns != null) {
            for (int i = 0; i < columns.size(); i++) metadata.add(ResultColumn.unknown(i, columns.get(i)));
        }
        return queryWithMetadata(metadata, rows, elapsedMillis, false);
    }

    public static QueryResult queryWithMetadata(
            List<ResultColumn> columns, List<List<Object>> rows,
            long elapsedMillis, boolean truncated) {
        List<ResultColumn> metadata = List.copyOf(Objects.requireNonNull(columns, "columns"));
        return frozenQuery(metadata, freezeRows(metadata, rows), elapsedMillis, truncated);
    }

    public static QueryResult update(long elapsedMillis, int updateCount) {
        return new QueryResult(Kind.UPDATE, List.of(), List.of(), List.of(), updateCount,
                elapsedMillis, null, null, List.of(), false);
    }

    public static QueryResult error(String errorMessage, long elapsedMillis) {
        return failure(FailureKind.SQL_ERROR, errorMessage, elapsedMillis);
    }

    public static QueryResult cancelled(String errorMessage, long elapsedMillis) {
        return failure(FailureKind.CANCELLED, errorMessage, elapsedMillis);
    }

    public static QueryResult timeout(String errorMessage, long elapsedMillis) {
        return failure(FailureKind.TIMEOUT, errorMessage, elapsedMillis);
    }

    private static QueryResult failure(FailureKind kind, String message, long elapsedMillis) {
        return new QueryResult(Kind.ERROR, List.of(), List.of(), List.of(), -1,
                elapsedMillis, message, kind, List.of(), false);
    }

    /**
     * 返回一个附加了列注释的副本（不改动行数据）。注释列表与 {@link #columns} 平行，
     * 元素可为 null（该列取不到注释）。仅对 QUERY 结果有意义。
     */
    public QueryResult withColumnComments(List<String> comments) {
        return new QueryResult(kind, columns, immutableNullableCopy(comments), rows,
                updateCount, elapsedMillis, errorMessage, failureKind, resultColumns, truncated, retentionNotice, errorPosition, executionMillis, fetchMillis);
    }

    public QueryResult withRetentionNotice(String notice) {
        return new QueryResult(kind, columns, columnComments, rows, updateCount, elapsedMillis,
                errorMessage, failureKind, resultColumns, truncated, notice, errorPosition, executionMillis, fetchMillis);
    }

    public QueryResult withExecutionDetails(int position, long executionMillis, long fetchMillis) {
        return new QueryResult(kind, columns, columnComments, rows, updateCount, elapsedMillis,
                errorMessage, failureKind, resultColumns, truncated, retentionNotice,
                kind == Kind.ERROR ? Math.max(0, position) : 0, executionMillis, fetchMillis);
    }

    /** Bounded editor read path. Driver buffering is outside this retained-data allowance. */
    public static QueryResult fromResultSet(ResultSet rs, long elapsedMillis, int maxRows,
            com.datacube.spi.SqlResultBudget budget, com.datacube.spi.SqlExecutionControl control) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int originalColumns = md.getColumnCount();
        int count = Math.min(originalColumns, budget.limits().columns());
        boolean omitted = count < originalColumns;
        var columns = new ArrayList<ResultColumn>(count);
        for (int i = 1; i <= count; i++) {
            String label = md.getColumnLabel(i), type = md.getColumnTypeName(i);
            String keptLabel = budget.text(label, 512), keptType = budget.text(type, 128);
            omitted |= !Objects.equals(label, keptLabel) || !Objects.equals(type, keptType);
            columns.add(new ResultColumn(i - 1, keptLabel, md.getColumnType(i), keptType));
        }
        var rows = new ArrayList<List<Object>>();
        int max = maxRows <= 0 ? Integer.MAX_VALUE : maxRows;
        while (rows.size() < max && budget.canReadRow(count)) {
            checkCancelled(control);
            if (!rs.next()) return frozenQuery(List.copyOf(columns), Collections.unmodifiableList(rows), elapsedMillis, false)
                    .withRetentionNotice(omitted ? "部分列或单元格仅预览/已省略（结果预算）" : "");
            budget.takeRow(count);
            var row = new ArrayList<Object>(count);
            for (int i = 1; i <= count; i++) {
                checkCancelled(control);
                Object value = readBudgetedCell(rs, i, columns.get(i - 1), budget, control);
                omitted |= value instanceof ResultValuePreview;
                row.add(value);
            }
            rows.add(Collections.unmodifiableList(row));
        }
        checkCancelled(control);
        boolean more = rs.next();
        omitted |= more && !budget.canReadRow(count);
        return frozenQuery(List.copyOf(columns), Collections.unmodifiableList(rows), elapsedMillis, more)
                .withRetentionNotice(omitted ? "部分行、列或单元格仅预览/已省略（结果预算）" : "");
    }

    private static Object readBudgetedCell(ResultSet rs, int index, ResultColumn column,
            com.datacube.spi.SqlResultBudget budget, com.datacube.spi.SqlExecutionControl control) throws SQLException {
        int type = column.jdbcType();
        if (type == Types.CHAR || type == Types.VARCHAR || type == Types.LONGVARCHAR
                || type == Types.NCHAR || type == Types.NVARCHAR || type == Types.LONGNVARCHAR
                || type == Types.CLOB || type == Types.NCLOB || type == Types.SQLXML
                || "json".equalsIgnoreCase(column.jdbcTypeName()) || "jsonb".equalsIgnoreCase(column.jdbcTypeName())) {
            try (Reader reader = rs.getCharacterStream(index)) {
                if (reader == null) return null;
                int cap = Math.min(budget.remainingText(), budget.limits().cellUnits());
                char[] buffer = new char[cap + 1]; int used = 0;
                while (used < buffer.length) {
                    checkCancelled(control);
                    int n = reader.read(buffer, used, buffer.length - used);
                    if (n < 0) break;
                    if (n == 0) { int c = reader.read(); if (c < 0) break; buffer[used++] = (char) c; }
                    else used += n;
                }
                String source = new String(buffer, 0, used);
                String kept = budget.text(source, cap);
                return kept.length() < used ? new ResultValuePreview(kept) : kept;
            } catch (java.io.IOException failure) { throw new SQLException("无法读取结果文本", failure); }
        }
        if (type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB) {
            try (var stream = rs.getBinaryStream(index)) {
                if (stream == null) return null;
                byte[] bytes = stream.readNBytes(Math.min(64, budget.remainingText() / 2));
                return new ResultValuePreview(budget.text("0x" + java.util.HexFormat.of().formatHex(bytes), 130));
            } catch (java.io.IOException failure) { throw new SQLException("无法读取结果二进制预览", failure); }
        }
        if (type == Types.ARRAY || type == Types.STRUCT || type == Types.REF || type == Types.JAVA_OBJECT
                || (type == Types.OTHER && !"uuid".equalsIgnoreCase(column.jdbcTypeName())))
            return new ResultValuePreview("此复合类型未读取");
        // Avoid recursively freezing unexpected driver aggregates on the bounded path.
        Object raw = isTimestampWithLocalTimeZone(column.jdbcTypeName())
                ? rs.getObject(index, OffsetDateTime.class) : rs.getObject(index);
        int scalarLimit = Math.min(budget.limits().cellUnits(), budget.remainingText());
        if (raw instanceof java.math.BigDecimal number && number.precision() > scalarLimit
                || raw instanceof java.math.BigInteger integer && integer.bitLength() > scalarLimit * 3L)
            return new ResultValuePreview("数值超过单值预算");
        if (raw != null && !(raw instanceof Number || raw instanceof String || raw instanceof Boolean
                || raw instanceof java.util.UUID || raw instanceof java.util.Date
                || "java.time".equals(raw.getClass().getPackageName())))
            return new ResultValuePreview("此驱动值类型未保留");
        Object value = ImmutableResultValue.freezeJdbc(raw,
                isTimestampWithLocalTimeZone(column.jdbcTypeName()) ? Types.TIMESTAMP_WITH_TIMEZONE : type);
        if (value instanceof java.math.BigDecimal || value instanceof java.math.BigInteger) {
            String number = value.toString();
            if (number.length() > scalarLimit) return new ResultValuePreview("数值超过单值预算");
            budget.text(number, scalarLimit);
        }
        if (value instanceof String string) {
            String kept = budget.text(string, budget.limits().cellUnits());
            return kept.length() < string.length() ? new ResultValuePreview(kept) : kept;
        }
        return value;
    }

    private static void checkCancelled(com.datacube.spi.SqlExecutionControl control) throws SQLException {
        if (control.cancellationRequested() || Thread.currentThread().isInterrupted())
            throw new SQLException("SQL execution cancelled");
    }

    /**
     * 从 ResultSet 读取全部行（受保护的最大行数限制避免 OOM）。
     */
    public static QueryResult fromResultSet(ResultSet rs, long elapsedMillis) throws SQLException {
        return fromResultSet(rs, elapsedMillis, 10_000);
    }

    /**
     * 从 ResultSet 读取最多 {@code maxRows} 行（限制保留内存）。
     * {@code maxRows <= 0} 视为不限制。
     */
    public static QueryResult fromResultSet(ResultSet rs, long elapsedMillis, int maxRows) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();
        List<ResultColumn> metadata = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            metadata.add(new ResultColumn(i - 1, md.getColumnLabel(i), md.getColumnType(i), md.getColumnTypeName(i)));
        }
        List<List<Object>> data = new ArrayList<>();
        int max = maxRows <= 0 ? Integer.MAX_VALUE : maxRows;
        int rowCount = 0;
        while (rowCount < max && rs.next()) {
            List<Object> row = new ArrayList<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                ResultColumn column = metadata.get(i - 1);
                row.add(readCell(rs, i, column.jdbcType(), column.jdbcTypeName()));
            }
            data.add(Collections.unmodifiableList(row));
            rowCount++;
        }
        boolean truncated = maxRows > 0 && rowCount >= max && rs.next();
        return frozenQuery(List.copyOf(metadata), Collections.unmodifiableList(data), elapsedMillis, truncated);
    }

    private static Object readCell(ResultSet rs, int idx, int sqlType, String typeName)
            throws SQLException {
        if (sqlType == Types.OTHER
                && ("json".equalsIgnoreCase(typeName) || "jsonb".equalsIgnoreCase(typeName))) {
            Reader source = rs.getCharacterStream(idx);
            return ImmutableResultValue.readBoundedText(source);
        }
        if (isTimestampWithLocalTimeZone(typeName)) {
            Object temporal = rs.getObject(idx, OffsetDateTime.class);
            return ImmutableResultValue.freezeJdbc(temporal, Types.TIMESTAMP_WITH_TIMEZONE);
        }
        return ImmutableResultValue.freezeJdbc(rs.getObject(idx), sqlType);
    }

    private static boolean isTimestampWithLocalTimeZone(String typeName) {
        return typeName != null
                && typeName.toUpperCase(java.util.Locale.ROOT).contains("WITH LOCAL TIME ZONE");
    }

    private static QueryResult frozenQuery(
            List<ResultColumn> metadata, List<List<Object>> rows,
            long elapsedMillis, boolean truncated) {
        List<String> labels = metadata.stream().map(ResultColumn::label).toList();
        return new QueryResult(Kind.QUERY, labels, List.of(), rows, -1,
                elapsedMillis, null, null, metadata, truncated);
    }

    private static List<List<Object>> freezeRows(
            List<ResultColumn> metadata, List<List<Object>> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<List<Object>> frozenRows = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            List<Object> source = Objects.requireNonNull(row, "result row");
            List<Object> frozenRow = new ArrayList<>(source.size());
            for (int index = 0; index < source.size(); index++) {
                Object value = source.get(index);
                try {
                    frozenRow.add(index < metadata.size()
                            ? ImmutableResultValue.freezeJdbc(value, metadata.get(index).jdbcType())
                            : ImmutableResultValue.freezeJdbc(value));
                } catch (SQLException failure) {
                    throw new IllegalArgumentException("无法读取 JDBC 结果值", failure);
                }
            }
            frozenRows.add(Collections.unmodifiableList(frozenRow));
        }
        return Collections.unmodifiableList(frozenRows);
    }

    private static <T> List<T> immutableNullableCopy(List<? extends T> values) {
        if (values == null || values.isEmpty()) return List.of();
        return Collections.unmodifiableList(new ArrayList<>(values));
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
