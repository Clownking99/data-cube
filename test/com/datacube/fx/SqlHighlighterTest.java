package com.datacube.fx;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlHighlighterTest {
    @Test void oracleDdlKeywordsAreCaseInsensitiveAndAvailableForCompletion() {
        for (String word : List.of("CASCADE", "CONSTRAINTS", "PURGE", "TRUNCATE", "COMMENT", "GRANT", "REVOKE", "SYNONYM", "TABLESPACE", "MERGE", "USING", "MATCHED")) {
            String sql = word.toLowerCase(java.util.Locale.ROOT);
            assertTrue(style(sql, 0).contains("sql-keyword"), word);
            assertTrue(completionKeywords().contains(word), word);
        }
    }

    @Test void keywordsDoNotLeakIntoCommentsStringsQuotedOrExtendedIdentifiers() {
        for (String text : List.of("cascade_name", "mycascade", "cascade$log", "cascade#log", "中cascade"))
            for (int i = 0; i < text.length(); i++) assertFalse(style(text, i).contains("sql-keyword"), text);
        assertTrue(style("-- cascade constraints", 3).contains("sql-comment"));
        assertTrue(style("/* cascade */", 4).contains("sql-comment"));
        assertTrue(style("'cascade'", 2).contains("sql-string"));
        assertTrue(style("\"CASCADE\"", 2).contains("sql-string"));
        String sql = "drop table demo cascade constraints;";
        assertTrue(style(sql, sql.indexOf("cascade")).contains("sql-keyword"));
        assertTrue(style(sql, sql.indexOf("constraints")).contains("sql-keyword"));
        assertEquals(sql.length(), SqlHighlighter.compute(sql).length());
    }

    @SuppressWarnings("unchecked") private static List<String> completionKeywords() {
        try { var field = SqlEditorPane.class.getDeclaredField("SQL_KEYWORDS"); field.setAccessible(true); return (List<String>) field.get(null); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Collection<String> style(String text, int at) {
        int offset = 0;
        for (var span : SqlHighlighter.compute(text)) { if (at < offset + span.getLength()) return span.getStyle(); offset += span.getLength(); }
        throw new AssertionError("Missing span at " + at);
    }
}
