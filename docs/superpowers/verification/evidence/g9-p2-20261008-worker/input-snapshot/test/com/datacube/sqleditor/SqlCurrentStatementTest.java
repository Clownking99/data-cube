package com.datacube.sqleditor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlCurrentStatementTest {
    @Test void exactOffsetsKeepRepeatedStatementsCommentsAndUnicode() {
        String source = "select '😀';\r\n -- keep\r\n select '😀';\n select 3";
        var spans = SqlScriptSplitter.fragments(source, false);
        assertEquals(SqlScriptSplitter.split(source), spans.stream().map(s -> s.text(source)).toList());
        assertEquals(3, spans.size());
        var current = SqlCurrentStatement.resolve(source, source.lastIndexOf("😀"), false);
        assertTrue(current.available());
        assertEquals("-- keep\r\n select '😀'", current.range().extract(source));
        assertEquals(source.indexOf("-- keep"), current.range().start());
    }
    @Test void dollarBodyNeverSplitsAtInternalSemicolon() {
        String sql = "select 0; DO $body$ BEGIN RAISE NOTICE ';'; END $body$; select 2";
        var result = SqlCurrentStatement.resolve(sql, sql.indexOf("RAISE"), false);
        assertEquals("DO $body$ BEGIN RAISE NOTICE ';'; END $body$", result.range().extract(sql));
    }
    @Test void leadingCommentsPackagesAndLoneCarriageReturnSlashKeepOracleBlock() {
        String sql = "-- package\rCREATE OR REPLACE PACKAGE BODY p AS\rPROCEDURE q IS BEGIN NULL; END;\rEND;\r/\rselect 2;";
        assertEquals(2, SqlScriptSplitter.split(sql, true).size());
        var result = SqlCurrentStatement.resolve(sql, sql.indexOf("NULL"), true);
        assertTrue(result.available());
        assertEquals(sql.substring(0, sql.indexOf("\r/")), result.range().extract(sql));
        assertEquals("select 2", SqlScriptSplitter.fragments(sql, true).getLast().text(sql));
    }
    @Test void refusesTriviaAndIncompleteInputWithoutExpanding() {
        for (String sql : new String[]{"-- comment", "  ", "select 'open", "select (1", "select * from", "select 1 +",
                "select $body$open;", "/* open", "select 1 /* a /* nested */ */"}) {
            assertFalse(SqlCurrentStatement.resolve(sql, sql.length(), sql.contains("nested")).available(), sql);
        }
        String sql = "select 1;\n\n-- note\nselect 2";
        assertFalse(SqlCurrentStatement.resolve(sql, sql.indexOf("\n") + 1, false).available());
        assertFalse(SqlCurrentStatement.resolve(sql, sql.indexOf("note"), false).available());
        assertFalse(SqlCurrentStatement.resolve("BEGIN NULL; END;", 7, true).available());
        assertTrue(SqlCurrentStatement.resolve("BEGIN NULL; END;\n/", 7, true).available());
        assertFalse(SqlCurrentStatement.resolve("BEGIN NULL;\n/", 7, true).available());
        assertFalse(SqlCurrentStatement.resolve("WITH q AS (SELECT 1)", 5, false).available());
        assertFalse(SqlCurrentStatement.resolve("unsupported delimiter text", 5, false).available());
    }
    @Test void emptySelectionStillUsesExistingAllRule() {
        assertEquals("select 1; select 2", SqlExecutionRange.resolve("select 1; select 2", 3, 3).extract("select 1; select 2"));
        assertEquals("select 1", SqlCurrentStatement.resolve("select 1; select 2", 3, false).range().extract("select 1; select 2"));
    }
    @Test void terminatorCaretIsExplicitWhileFollowingBlankLineDoesNotExpand() {
        String sql = "select 1  ;\n\nselect 2;";
        assertEquals("select 1", SqlCurrentStatement.resolve(sql, sql.indexOf(';'), false).range().extract(sql));
        assertEquals("select 1", SqlCurrentStatement.resolve(sql, sql.indexOf(';') + 1, false).range().extract(sql));
        assertFalse(SqlCurrentStatement.resolve(sql, sql.indexOf(';') + 2, false).available());
        assertEquals("select 2", SqlCurrentStatement.resolve(sql, sql.length(), false).range().extract(sql));
        String adjacent = "select 1;select 2";
        assertEquals("select 2", SqlCurrentStatement.resolve(adjacent, adjacent.indexOf("select 2"), false).range().extract(adjacent));
    }
    @Test void headerCommentsCannotExposeOracleBodyAndUnsupportedAtomicBodyDoesNotExecuteAFragment() {
        String oracle = "CREATE /* header */ OR -- replace\n REPLACE PROCEDURE p AS BEGIN DELETE FROM t; NULL; END;\n/";
        var current = SqlCurrentStatement.resolve(oracle, oracle.indexOf("DELETE"), true);
        assertTrue(current.available()); assertTrue(current.range().extract(oracle).startsWith("CREATE"));
        assertEquals(1, SqlScriptSplitter.split(oracle, true).size());
        String pg = "CREATE FUNCTION f() RETURNS int LANGUAGE SQL BEGIN ATOMIC SELECT 1; SELECT 2; END;";
        assertFalse(SqlCurrentStatement.resolve(pg, pg.indexOf("SELECT 2"), false).available());
        assertTrue(SqlCurrentStatement.resolve("SELECT 'BEGIN ATOMIC'", 4, false).available());
    }
}
