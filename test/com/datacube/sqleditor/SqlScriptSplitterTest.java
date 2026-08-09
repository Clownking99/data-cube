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

    @Test
    void identifierDollarSequencesAndOracleModeCannotOpenDollarQuotes() {
        String[] pgScripts = {
                "select 1 as foo$bar$; delete from account",
                "select 1 as foo$$; delete from account"
        };
        for (String script : pgScripts) {
            List<String> stmts = SqlScriptSplitter.split(script, false);
            assertEquals(2, stmts.size(), "标识符中的 $ 不能吞掉后续语句：" + stmts);
            assertTrue(stmts.get(1).toLowerCase().startsWith("delete from account"));
        }

        List<String> oracle = SqlScriptSplitter.split(
                "select $$ marker; delete from account", true);
        assertEquals(2, oracle.size(), "Oracle 模式不能启用 PostgreSQL dollar quote：" + oracle);
        assertTrue(oracle.get(1).toLowerCase().startsWith("delete from account"));
    }

    @Test
    void nonAsciiIdentifierCodeUnitsCannotOpenDollarQuotes() {
        String[] scripts = {
                "select 1 as e\u0301$bar$; delete from account",
                "select 1 as name\u200C$tag$; delete from account"
        };
        for (String script : scripts) {
            List<String> stmts = SqlScriptSplitter.split(script, false);
            assertEquals(2, stmts.size(), "非 ASCII 标识符 code unit 不能吞掉后续语句：" + stmts);
            assertTrue(stmts.get(1).toLowerCase().startsWith("delete from account"));
        }
    }

    @Test
    void pgEscapeStringDoesNotHideFollowingStatement() {
        List<String> stmts = SqlScriptSplitter.split(
                "select E'it\\'s'; delete from account", false);

        assertEquals(2, stmts.size(), "E-string 的反斜杠转义不能吞掉后续语句：" + stmts);
        assertTrue(stmts.get(1).toLowerCase().startsWith("delete from account"));
    }

    @Test
    void oracleNqQuoteCanContainApostrophesAndSemicolons() {
        List<String> stmts = SqlScriptSplitter.split(
                "select NQ'[It's; delete from hidden]' from dual; delete from target where id=1",
                true);

        assertEquals(2, stmts.size(), "NQ quote 内的撇号和分号不能参与分句：" + stmts);
        assertTrue(stmts.get(1).toLowerCase().startsWith("delete from target"));
    }

    @Test
    void blockCommentedStatementsNotExecuted() {
        String script = "/*\nselect * from t1 where id in('1','2');\n\nselect * from t2;\n*/";
        assertTrue(SqlScriptSplitter.split(script).isEmpty(), "整段块注释不应产生语句");
        assertTrue(SqlScriptSplitter.split(script, true).isEmpty(), "PL/SQL 模式下同样不产生语句");
    }

    @Test
    void postgresNestedBlockCommentKeepsInnerTailSemicolonInsideOneStatement() {
        List<String> stmts = SqlScriptSplitter.split(
                "select 1 /* outer /* inner */ tail; */; select 2", false);

        assertEquals(2, stmts.size(), "PostgreSQL nested block comment must retain outer depth");
        assertTrue(stmts.getFirst().contains("tail; */"));
        assertEquals("select 2", stmts.get(1));
    }

    @Test
    void invalidLexicalUnitsRemainAvailableForConservativeHandling() {
        String[] invalidScripts = {
                "/* outer /* inner */ tail */ DELETE FROM account;",
                "*/ DELETE FROM account;",
                "/* unclosed DELETE FROM account;",
                "/* unclosed COMMIT;"
        };

        for (String script : invalidScripts) {
            List<String> stmts = SqlScriptSplitter.split(script, true);
            assertEquals(1, stmts.size(), "invalid SQL unit must not be silently dropped: " + script);
            assertTrue(stmts.getFirst().contains(
                    script.contains("DELETE") ? "DELETE" : "COMMIT"), script);
        }
    }

    @Test
    void lineCommentOnlyUnitDropped() {
        List<String> stmts = SqlScriptSplitter.split("-- 注释一\n-- 注释二\n; SELECT 1");
        assertEquals(1, stmts.size(), "纯行注释单元应被丢弃：" + stmts);
        assertEquals("SELECT 1", stmts.get(0));
    }

    @Test
    void lineCommentsEndAtLfCrLfAndCrWithoutHidingFollowingStatements() {
        String[] lineEndings = {"\n", "\r\n", "\r"};
        for (String lineEnding : lineEndings) {
            List<String> stmts = SqlScriptSplitter.split(
                    "select 1 -- harmless" + lineEnding + "; delete from account", false);

            assertEquals(2, stmts.size(), "行尾必须恢复分句状态：" + escaped(lineEnding));
            assertTrue(stmts.get(1).toLowerCase().startsWith("delete from account"));
        }
    }

    @Test
    void commentOnlyPrefixCannotCauseExecutableStatementToBeDropped() {
        String[] lineEndings = {"\n", "\r\n", "\r"};
        for (String lineEnding : lineEndings) {
            List<String> stmts = SqlScriptSplitter.split(
                    "-- harmless" + lineEnding + "delete from account; select 1", false);

            assertEquals(2, stmts.size(), "注释前缀后的语句不能被丢弃：" + escaped(lineEnding));
            assertTrue(stmts.get(0).toLowerCase().contains("delete from account"));
            assertTrue(stmts.get(1).toLowerCase().startsWith("select 1"));
        }
    }

    @Test
    void commentBeforeStatementKept() {
        List<String> stmts = SqlScriptSplitter.split("/* 说明 */ SELECT 1; -- 尾注\nSELECT 2");
        assertEquals(2, stmts.size(), "注释+语句混合单元应保留：" + stmts);
        assertTrue(stmts.get(0).contains("SELECT 1"), "语句前的注释不影响执行：" + stmts.get(0));
        assertTrue(stmts.get(1).contains("SELECT 2"));
    }

    @Test
    void commentMarkerInsideStringIsExecutable() {
        List<String> stmts = SqlScriptSplitter.split("SELECT '--' FROM dual");
        assertEquals(1, stmts.size(), "字符串内的 -- 不是注释：" + stmts);
    }

    private static String escaped(String value) {
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }
}
