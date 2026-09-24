package com.datacube.sqleditor;

import java.util.List;

/** One bounded immutable execution source. Positions never refer to live, later-edited text. */
public final class SqlExecutionSource {
    public static final int MAX_SOURCE_UNITS = 8 * 1024 * 1024;
    private final String source;
    private final long revision;
    private final int rangeStart;
    private final List<SqlScriptSplitter.Fragment> fragments;

    private SqlExecutionSource(String source, long revision, int rangeStart, List<SqlScriptSplitter.Fragment> fragments) {
        this.source = source; this.revision = revision; this.rangeStart = rangeStart; this.fragments = fragments;
    }
    public static SqlExecutionSource capture(String source, long revision, SqlExecutionRange range, boolean oracle) {
        if (source.length() > MAX_SOURCE_UNITS) return new SqlExecutionSource(null, revision, 0, List.of());
        return new SqlExecutionSource(source, revision, range.start(),
                SqlScriptSplitter.fragments(range.extract(source), oracle).stream().limit(1000).toList());
    }
    public record Location(int offset, String message) { public boolean available() { return offset >= 0; } }
    /** Driver position: one-based Unicode code points into the original submitted statement. */
    public Location locate(int index, int position, long currentRevision) {
        if (currentRevision != revision) return new Location(-1, "执行后正文已变化，错误位置已过期；请查看执行快照");
        if (source == null) return new Location(-1, "原文超过定位快照预算，未保留错误位置映射");
        if (position <= 0) return new Location(-1, "驱动未提供可确认的原始 SQL 位置，无法定位");
        if (index < 1 || index > fragments.size()) return new Location(-1, "该结果没有原始 SQL 偏移映射");
        var fragment = fragments.get(index - 1);
        int start = rangeStart + fragment.start(), end = rangeStart + fragment.end();
        int count = source.codePointCount(start, end);
        if (position > count + 1) return new Location(-1, "驱动位置超出原始语句范围，无法定位");
        return new Location(source.offsetByCodePoints(start, position - 1), "已定位到本次执行的原始 SQL");
    }
}
