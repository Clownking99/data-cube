package com.datacube.sqleditor;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import com.datacube.spi.model.ScriptOutcome;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.datacube.sqleditor.SqlScriptExecutionReport.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlScriptExecutionReportTest {
    @Test void countsReturnedKindsAndExplainsUnknownAndTruncatedRowCounts() {
        var report = capture(List.of(
                new ScriptOutcome(4, "select n", QueryResult.queryWithMetadata(List.of(ResultColumn.unknown(0, "n")), List.of(List.of(1)), 2, true)),
                new ScriptOutcome(5, "update n", QueryResult.update(3, 7)),
                new ScriptOutcome(6, "ddl", QueryResult.update(4, -1)),
                new ScriptOutcome(7, "bad", QueryResult.error("syntax", 5)),
                new ScriptOutcome(8, "slow", QueryResult.timeout("timeout", 6)),
                new ScriptOutcome(9, "cancel", QueryResult.cancelled("cancelled", 7))), 30);
        assertEquals(6, report.returned()); assertEquals(3, report.normal()); assertEquals(1, report.failed());
        assertEquals(1, report.timedOut()); assertEquals(1, report.cancelled()); assertTrue(report.hasFailures());
        assertEquals("已返回 6 条结果：正常 3 · 失败 1 · 超时 1 · 取消 1 - 30ms", report.summary());
        assertEquals(List.of("QUERY", "UPDATE", "UPDATE", "失败", "超时", "取消"), report.entries().stream().map(Entry::status).toList());
        assertEquals("已加载 1 行（结果已截断）", report.entries().getFirst().resultDescription());
        assertEquals("影响 7 行", report.entries().get(1).resultDescription());
        assertEquals("影响行数未提供", report.entries().get(2).resultDescription());
        assertEquals(4, report.entries().getFirst().index()); assertEquals(2, report.entries().getFirst().elapsedMillis());
    }

    @Test void entryCapDoesNotHideFailuresFromTotalCounts() {
        var outcomes = new ArrayList<ScriptOutcome>();
        for (int i = 0; i < MAX_ENTRIES; i++) outcomes.add(new ScriptOutcome(i + 1, "s", QueryResult.update(1, 0)));
        outcomes.add(new ScriptOutcome(1001, "bad", QueryResult.error("later failure", 1)));
        var report = capture(outcomes, 1);
        assertEquals(1000, report.entries().size()); assertEquals(1001, report.returned());
        assertEquals(1, report.failed()); assertTrue(report.hasFailures());
        assertTrue(report.displayNotice().contains("仅显示前 1000 条"));
    }

    @Test void fieldsAndAggregateAreBoundedWithExplicitOmission() {
        String huge = "x".repeat(MAX_FIELD_UNITS + 10);
        var outcomes = new ArrayList<ScriptOutcome>();
        for (int i = 0; i < 40; i++) outcomes.add(new ScriptOutcome(i, huge, QueryResult.error(huge, 1)));
        var report = capture(outcomes, 1);
        assertEquals(MAX_TEXT_UNITS, report.entries().stream().mapToInt(e -> e.sql().value().length() + e.error().value().length()).sum());
        assertEquals(MAX_FIELD_UNITS, report.entries().getFirst().sql().value().length());
        assertTrue(report.entries().stream().allMatch(e -> e.sql().truncated() && e.error().truncated()));
        assertEquals("", report.entries().getLast().sql().value());
        assertEquals("错误详情因显示上限省略", report.entries().getLast().resultDescription());
    }

    @Test void exactLimitIsNotTruncationAndSurrogatePairIsNotSplit() {
        String exact = "a".repeat(MAX_FIELD_UNITS);
        String boundary = "a".repeat(MAX_FIELD_UNITS - 1) + "\uD83D\uDE00";
        var report = capture(List.of(new ScriptOutcome(1, exact, QueryResult.error(boundary, 1))), 1);
        assertFalse(report.entries().getFirst().sql().truncated());
        var error = report.entries().getFirst().error();
        assertEquals(MAX_FIELD_UNITS - 1, error.value().length()); assertTrue(error.truncated());
        assertEquals('a', error.value().charAt(error.value().length() - 1));
    }

    @Test void summaryIsShortButDetailsKeepLongMultilineErrorAsPlainText() {
        String error = "<script>do not execute</script>\n" + "message".repeat(25) + "\nSQLState=42000";
        var report = capture(List.of(new ScriptOutcome(1, "select '<b>raw</b>'", QueryResult.error(error, 1))), 1);
        Entry entry = report.entries().getFirst();
        assertEquals(error, entry.error().value()); assertFalse(entry.error().truncated());
        assertEquals(81, entry.summary().length()); assertFalse(entry.summary().contains("\n"));
        assertTrue(entry.summary().endsWith("…"));
    }

    @Test void nullTextAndEmptyResultsAreNotMisrepresented() {
        var report = capture(List.of(new ScriptOutcome(1, null, QueryResult.error(null, 1))), 1);
        assertEquals("", report.entries().getFirst().sql().value());
        assertEquals("未提供错误信息", report.entries().getFirst().resultDescription());
        assertFalse(capture(List.of(), 0).hasFailures());
        assertFalse(capture(List.of(new ScriptOutcome(1, "s", QueryResult.update(1, 0))), 1).hasFailures());
    }

    @Test void detachedSnapshotReadsOnlyRowCountAndNeverAccessesQueryCells() throws Exception {
        var result = QueryResult.query(List.of("cell"), List.of(List.of("not retained")), 1);
        var rows = QueryResult.class.getDeclaredField("rows"); rows.setAccessible(true);
        rows.set(result, new java.util.AbstractList<List<Object>>() {
            @Override public int size() { return 1; }
            @Override public List<Object> get(int index) { throw new AssertionError("must not access query rows"); }
        });
        var outcomes = new ArrayList<>(List.of(new ScriptOutcome(1, "select opaque", result)));
        var report = capture(outcomes, 1); outcomes.clear();
        assertEquals(1, report.entries().getFirst().rowCount()); assertEquals("已加载 1 行", report.entries().getFirst().summary());
        assertEquals("select opaque", report.entries().getFirst().sql().value());
        assertThrows(UnsupportedOperationException.class, () -> report.entries().clear());
        for (var component : Entry.class.getRecordComponents()) {
            Class<?> type = component.getType();
            assertTrue(type.isPrimitive() || type.isEnum() || type == Text.class, "entry may only retain scalars and bounded text: " + component);
        }
    }
}
