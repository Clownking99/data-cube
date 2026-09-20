package com.datacube.sqleditor;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.sqleditor.SqlScriptExecutionReport.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlScriptPreviewTest {
    private static Entry entry(String sql) {
        return capture(List.of(new ScriptOutcome(1, sql, QueryResult.update(1, 0))), 1).entries().getFirst();
    }

    @Test void previewFlattensLinesButLeavesCommentsLiteralsAndCapturedSqlIntact() {
        String sql = " \t-- comment\r\nSELECT\t'<b>A  B</b>', 'x\ny'\rFROM sample;\u0085-- next\u2028line\u2029end  ";
        var entry = entry(sql);
        assertEquals("-- comment SELECT '<b>A  B</b>', 'x y' FROM sample; -- next line end", entry.sqlPreview());
        assertEquals(sql, entry.sql().value()); assertFalse(entry.sql().truncated());
        assertEquals("影响 0 行", entry.summary());
    }

    @ParameterizedTest @ValueSource(ints = {119, 120, 121})
    void previewHasExactBoundWithExplicitTruncation(int length) {
        String sql = "x".repeat(length); var entry = entry(sql);
        assertEquals("x".repeat(Math.min(length, 120)) + (length > 120 ? "…" : ""), entry.sqlPreview());
        assertEquals(sql, entry.sql().value()); assertFalse(entry.sql().truncated());
    }

    @ParameterizedTest @ValueSource(ints = {118, 119})
    void previewDoesNotSplitSurrogatePairAtBoundary(int prefix) {
        String sql = "x".repeat(prefix) + "\uD83D\uDE00"; var entry = entry(sql);
        assertEquals(prefix == 118 ? sql : "x".repeat(119) + "…", entry.sqlPreview());
        assertEquals(sql, entry.sql().value());
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" \r\n\t ", "\u2028\u2029"})
    void missingOrWhitespaceSqlIsNotConfusedWithBudgetOmission(String sql) {
        var entry = entry(sql); assertEquals("未提供 SQL", entry.sqlPreview()); assertFalse(entry.sql().truncated());
    }

    @Test void inheritedTruncationRemainsVisibleEvenWhenFlattenedPreviewIsShort() {
        var entry = entry("select 1" + " ".repeat(MAX_FIELD_UNITS) + "tail must not be read");
        assertEquals("select 1…", entry.sqlPreview()); assertTrue(entry.sql().truncated());
        assertFalse(entry.sql().value().contains("tail")); assertEquals(MAX_FIELD_UNITS, entry.sql().value().length());
    }

    @Test void aggregateBudgetOmissionDoesNotLookLikeMissingSql() {
        var outcomes = new ArrayList<ScriptOutcome>();
        for (int i = 0; i < 64; i++) outcomes.add(new ScriptOutcome(i, "x".repeat(MAX_FIELD_UNITS), QueryResult.update(1, 1)));
        outcomes.add(new ScriptOutcome(65, "select hidden_tail", QueryResult.update(1, 1)));
        var report = capture(outcomes, 1); var last = report.entries().getLast();
        assertEquals("SQL 因显示上限省略", last.sqlPreview()); assertEquals("", last.sql().value()); assertTrue(last.sql().truncated());
        assertEquals(65, report.normal()); assertEquals("x".repeat(120) + "…", report.entries().getFirst().sqlPreview());
    }

    @Test void truncatedWhitespacePrefixDoesNotClaimThatOriginalSqlWasEmpty() {
        var entry = entry(" ".repeat(MAX_FIELD_UNITS) + "select 1");
        assertEquals("SQL 因显示上限省略", entry.sqlPreview()); assertTrue(entry.sql().truncated());
    }
}
