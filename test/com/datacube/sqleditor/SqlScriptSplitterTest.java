package com.datacube.sqleditor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlScriptSplitter} 分句单测：PL/SQL 模式下块内分号不切分、{@code /} 行终止、
 * spec+body 双单元；普通脚本仍按 {@code ;} 切；PG 模式（plsql=false）与历史行为一致。
 */
class SqlScriptSplitterTest {

    @Test
    void plainMultiStatementSplitBySemicolon() {
        List<String> stmts = SqlScriptSplitter.split("SELECT 1; SELECT 2; SELECT 3");
        assertEquals(3, stmts.size(), "普通脚本应按分号切成 3 条");
        assertEquals("SELECT 1", stmts.get(0));
        assertEquals("SELECT 2", stmts.get(1));
        assertEquals("SELECT 3", stmts.get(2));
    }

    @Test
    void semicolonInStringNotSplit() {
        List<String> stmts = SqlScriptSplitter.split("INSERT INTO t VALUES ('a;b'); SELECT 1");
        assertEquals(2, stmts.size(), "字符串内分号不切分");
        assertTrue(stmts.get(0).contains("'a;b'"));
    }

    @Test
    void plsqlBlockInnerSemicolonsNotSplit() {
        String proc = "CREATE OR REPLACE PROCEDURE p AS\n"
                + "BEGIN\n"
                + "  x := 1;\n"
                + "  y := 2;\n"
                + "END;\n"
                + "/\n";
        List<String> stmts = SqlScriptSplitter.split(proc, true);
        assertEquals(1, stmts.size(), "PL/SQL 块内部分号不应切分：" + stmts);
        assertTrue(stmts.get(0).contains("END;"), "块应保留 END; ：" + stmts.get(0));
        assertTrue(!stmts.get(0).contains("/"), "斜杠终止符不计入语句文本：" + stmts.get(0));
    }

    @Test
    void plsqlSlashTerminatesBlock() {
        String script = "BEGIN\n  NULL;\nEND;\n/\nBEGIN\n  NULL;\nEND;\n/\n";
        List<String> stmts = SqlScriptSplitter.split(script, true);
        assertEquals(2, stmts.size(), "两个以 / 终止的匿名块应切成 2 个单元：" + stmts);
    }

    @Test
    void plsqlPackageSpecAndBody() {
        String script = "CREATE OR REPLACE PACKAGE pkg AS\n"
                + "  PROCEDURE p;\n"
                + "END pkg;\n"
                + "/\n"
                + "CREATE OR REPLACE PACKAGE BODY pkg AS\n"
                + "  PROCEDURE p IS BEGIN NULL; END;\n"
                + "END pkg;\n"
                + "/\n";
        List<String> stmts = SqlScriptSplitter.split(script, true);
        assertEquals(2, stmts.size(), "spec + body 应为 2 个单元：" + stmts);
        assertTrue(stmts.get(0).toUpperCase().startsWith("CREATE OR REPLACE PACKAGE"));
        assertTrue(stmts.get(1).toUpperCase().contains("PACKAGE BODY"));
    }

    @Test
    void plsqlModeStillSplitsPlainStatements() {
        List<String> stmts = SqlScriptSplitter.split("SELECT 1; SELECT 2", true);
        assertEquals(2, stmts.size(), "PL/SQL 模式下非块普通语句仍按分号切：" + stmts);
    }

    @Test
    void pgModeMatchesLegacyDefault() {
        String script = "SELECT 1; SELECT 2; SELECT 3";
        List<String> legacy = SqlScriptSplitter.split(script);
        List<String> pg = SqlScriptSplitter.split(script, false);
        assertEquals(legacy, pg, "plsql=false 应与默认（历史）行为一致");
    }

    @Test
    void pgDollarQuoteFunctionNotSplitByInnerSemicolon() {
        String fn = "CREATE FUNCTION f() RETURNS int AS $$ BEGIN RETURN 1; END; $$ LANGUAGE plpgsql; SELECT 1";
        List<String> stmts = SqlScriptSplitter.split(fn, false);
        assertEquals(2, stmts.size(), "dollar-quote 内分号不切分：" + stmts);
        assertTrue(stmts.get(0).contains("$$"));
    }
}
