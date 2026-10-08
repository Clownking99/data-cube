package com.datacube.sqleditor.result;

import com.datacube.spi.SqlResultBudget;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultValuePreview;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Tab-local, memory-only retention. Never owns a batch, connection, request, or JDBC resource. */
public final class PinnedResultStore implements AutoCloseable {
    public static final int MAX_RESULTS = 3;
    public static final int MAX_SQL_UNITS = 16_384;
    public record Source(String target, String schema, String sql, boolean databaseFiltered) {
        public Source {
            target = Objects.requireNonNullElse(target, "未绑定连接（本地结果）");
            schema = Objects.requireNonNullElse(schema, "默认 Schema");
            sql = Objects.requireNonNullElse(sql, "");
        }
        @Override public String toString() { return "Source[values=<redacted>]"; }
    }
    public record Usage(int results, long rows, long cells, long textUnits) {}
    /** Object identity distinguishes repeated SQL and equal data. */
    public static final class Entry {
        private final long id;
        private final QueryResult result;
        private final Source source;
        private final Instant pinnedAt;
        private final Usage usage;
        private Entry(long id, QueryResult result, Source source, Instant pinnedAt, Usage usage) {
            this.id = id; this.result = result; this.source = source; this.pinnedAt = pinnedAt; this.usage = usage;
        }
        public long id() { return id; }
        public QueryResult result() { return result; }
        public Source source() { return source; }
        public Instant pinnedAt() { return pinnedAt; }
        @Override public String toString() { return "PinnedResult[id=" + id + ", values=<redacted>]"; }
    }

    private final SqlResultBudget.Limits limits;
    private final Clock clock;
    private final List<Entry> entries = new ArrayList<>();
    private Usage usage = new Usage(0, 0, 0, 0);
    private long nextId;
    private boolean closed;

    public PinnedResultStore() { this(SqlResultBudget.DEFAULT, Clock.systemUTC()); }
    public PinnedResultStore(SqlResultBudget.Limits limits, Clock clock) {
        this.limits = Objects.requireNonNull(limits); this.clock = Objects.requireNonNull(clock);
    }
    public List<Entry> entries() { return List.copyOf(entries); }
    public Usage usage() { return usage; }
    public boolean contains(Entry entry) { return entries.contains(entry); }

    public Entry pin(QueryResult result, Source source) {
        if (closed) throw new IllegalStateException("固定结果已关闭");
        if (result == null || result.kind != QueryResult.Kind.QUERY)
            throw new IllegalArgumentException("只能固定已加载的查询结果");
        Objects.requireNonNull(source);
        if (entries.size() >= Math.min(MAX_RESULTS, limits.results()))
            throw new IllegalStateException("固定结果数量已达上限，请先移除一份");
        int columns = result.resultColumns.size();
        long cells = (long) result.rows.size() * columns;
        if (columns > limits.columns() || result.rows.size() > limits.rows() - usage.rows()
                || cells > limits.cells() - usage.cells()) throw capacity();
        var counter = new TextCounter(limits.textUnits() - usage.textUnits());
        counter.add(source.target(), 512); counter.add(source.schema(), 512);
        counter.add(source.sql(), MAX_SQL_UNITS); counter.add(result.retentionNotice, 4096);
        for (var column : result.resultColumns) {
            counter.add(column.label(), 512); counter.add(column.jdbcTypeName(), 128);
        }
        if (result.columnComments.size() > columns) throw capacity();
        for (String comment : result.columnComments) counter.add(comment, 512);
        for (var row : result.rows) {
            if (row.size() != columns) throw new IllegalArgumentException("结果列结构不一致，无法固定");
            for (Object value : row) {
                if (value instanceof ResultValuePreview preview) counter.add(preview.text(), limits.cellUnits());
                else if (value instanceof String text) counter.add(text, limits.cellUnits());
                else if (value instanceof BigDecimal number) {
                    if (number.precision() > limits.cellUnits()) throw capacity();
                    counter.add(number.toString(), limits.cellUnits());
                } else if (value instanceof BigInteger number) {
                    if (number.bitLength() > limits.cellUnits() * 4L) throw capacity();
                    counter.add(number.toString(), limits.cellUnits());
                } else if (value != null && !(value instanceof Byte || value instanceof Short || value instanceof Integer
                        || value instanceof Long || value instanceof Float || value instanceof Double || value instanceof Boolean
                        || value instanceof UUID || "java.time".equals(value.getClass().getPackageName()))) {
                    // Do not retain an aggregate's hidden object graph or stringify it recursively.
                    throw new IllegalArgumentException("此结果包含无法确认保留大小的复合值，无法固定");
                }
            }
        }
        Usage cost = new Usage(1, result.rows.size(), cells, counter.used);
        Entry entry = new Entry(++nextId, result, source, clock.instant(), cost);
        entries.add(entry);
        usage = new Usage(usage.results() + 1, usage.rows() + cost.rows(), usage.cells() + cost.cells(), usage.textUnits() + cost.textUnits());
        return entry;
    }

    public boolean remove(Entry entry) {
        if (!entries.remove(entry)) return false;
        Usage cost = entry.usage;
        usage = new Usage(usage.results() - 1, usage.rows() - cost.rows(), usage.cells() - cost.cells(), usage.textUnits() - cost.textUnits());
        return true;
    }
    public void clear() { entries.clear(); usage = new Usage(0, 0, 0, 0); }
    @Override public void close() { closed = true; clear(); }

    private static IllegalStateException capacity() {
        return new IllegalStateException("固定结果预算不足或单份结果超过上限；请先移除固定结果或减少查询返回量。当前固定结果未改变。");
    }
    private static final class TextCounter {
        private final long available;
        private long used;
        private TextCounter(long available) { this.available = available; }
        private void add(String text, int fieldLimit) {
            if (text == null) return;
            if (text.length() > fieldLimit || text.length() > available - used) throw capacity();
            used += text.length();
        }
    }
}
