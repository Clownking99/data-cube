package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import java.util.*;

public final class ResultExportSnapshot {
    public record Column(int index, String label) {
        public Column {
            if (index < 0) throw new IllegalArgumentException("Invalid export column");
            label = Objects.requireNonNullElse(label, "");
        }
    }
    private final QueryResult active;
    private final String originalSql;
    private final List<Integer> visibleRows;
    private final List<Column> projection;

    private ResultExportSnapshot(QueryResult active, String sql,
            List<Integer> visibleRows, List<Column> projection) {
        this.active = Objects.requireNonNull(active);
        if (active.kind != QueryResult.Kind.QUERY)
            throw new IllegalArgumentException("Export requires a query result");
        this.originalSql = Objects.requireNonNullElse(sql, "");
        this.visibleRows = List.copyOf(visibleRows);
        this.projection = List.copyOf(projection);
        if (this.visibleRows.stream().anyMatch(i -> i < 0 || i >= active.rows.size())
                || this.projection.stream().anyMatch(c -> c.index() >= active.columns.size()))
            throw new IllegalArgumentException("Invalid export position");
    }

    public static ResultExportSnapshot capture(QueryResult active, String sql,
            List<Integer> visibleRows, List<Column> projection) {
        return new ResultExportSnapshot(active, sql, visibleRows, projection);
    }
    public String originalSql() { return originalSql; }
    public boolean truncated() { return active.truncated; }
    public List<String> columns() { return projection.stream().map(Column::label).toList(); }

    public List<List<Object>> rows(ResultExportScope scope) {
        Objects.requireNonNull(scope);
        return Collections.unmodifiableList(new AbstractList<>() {
            @Override public int size() {
                return scope == ResultExportScope.CURRENT_FILTERED
                        ? visibleRows.size() : active.rows.size();
            }
            @Override public List<Object> get(int index) {
                Objects.checkIndex(index, size());
                List<Object> source = active.rows.get(scope == ResultExportScope.CURRENT_FILTERED
                        ? visibleRows.get(index) : index);
                return Collections.unmodifiableList(new AbstractList<>() {
                    @Override public int size() { return projection.size(); }
                    @Override public Object get(int column) {
                        int position = projection.get(column).index();
                        return position < source.size() ? source.get(position) : null;
                    }
                });
            }
        });
    }
    @Override public String toString() {
        return "ResultExportSnapshot[loaded=" + active.rows.size()
                + ", visible=" + visibleRows.size() + ", columns=" + projection.size() + "]";
    }
}
