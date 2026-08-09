package com.datacube.sqleditor;

import org.junit.jupiter.api.Test;

import static com.datacube.sqleditor.SqlSafetyAnalyzer.Risk.*;
import static com.datacube.sqleditor.SqlSafetyAnalyzer.StatementKind.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlSafetyAnalyzerTest {
    @Test
    void transactionCompletionRequiresValidDialectTrivia() {
        assertEquals("", SqlSafetyAnalyzer.transactionCompletionKeyword(
                "COMMIT /* unterminated", false));
        assertEquals("COMMIT", SqlSafetyAnalyzer.transactionCompletionKeyword(
                "COMMIT /* outer /* inner */ tail */;", false));
        assertEquals("", SqlSafetyAnalyzer.transactionCompletionKeyword(
                "COMMIT /* outer /* inner */ tail */;", true));
    }

    @Test
    void invalidLexicalUnitsRemainVisibleButNeverQualifyAsTransactionTrivia() {
        String nested = "/* outer /* inner */ tail */ DELETE FROM account;";
        String stray = "*/ DELETE FROM account;";
        for (String sql : new String[]{nested, stray}) {
            var statement = SqlSafetyAnalyzer.analyze(sql, true).statements().getFirst();
            assertEquals(WRITE, statement.kind(), sql);
            assertTrue(statement.risks().contains(MISSING_WHERE), sql);
        }

        for (String sql : new String[]{
                "/* unclosed DELETE FROM account;", "/* unclosed COMMIT;"}) {
            var statement = SqlSafetyAnalyzer.analyze(sql, true).statements().getFirst();
            assertEquals(UNKNOWN, statement.kind(), sql);
            assertTrue(statement.risks().contains(UNKNOWN_STATEMENT), sql);
        }

        assertEquals("", SqlSafetyAnalyzer.transactionCompletionKeyword(
                "/* outer /* inner */ tail */ COMMIT;", true));
        assertEquals("", SqlSafetyAnalyzer.transactionCompletionKeyword("*/ COMMIT;", true));
        assertEquals("", SqlSafetyAnalyzer.transactionCompletionKeyword(
                "/* unclosed COMMIT;", true));
    }

    @Test
    void detectsMissingTopLevelWhereWithoutBeingFooledBySubqueryOrLiteral() {
        var unsafe = SqlSafetyAnalyzer.analyze(
                "update account set state='where' where_note=(select note from audit where id=1)", false);
        assertEquals(WRITE, unsafe.statements().getFirst().kind());
        assertTrue(unsafe.statements().getFirst().risks().contains(MISSING_WHERE));

        var safe = SqlSafetyAnalyzer.analyze(
                "delete from account where id in (select id from audit where state='x')", false);
        assertFalse(safe.statements().getFirst().risks().contains(MISSING_WHERE));
    }

    @Test
    void handlesCommentsDollarQuotesOracleQuotesAndCtes() {
        String pg = """
                /* delete from hidden */ with x as (
                  select $$ update t set x=1 $$ as body
                ) delete from target where id in (select 1 from x)
                """;
        assertEquals(WRITE, SqlSafetyAnalyzer.analyze(pg, false).statements().getFirst().kind());
        assertFalse(SqlSafetyAnalyzer.analyze(pg, false).statements().getFirst().risks()
                .contains(MISSING_WHERE));

        String oracle = "select q'[drop table hidden]' from dual";
        assertEquals(READ, SqlSafetyAnalyzer.analyze(oracle, true).statements().getFirst().kind());
    }

    @Test
    void handlesPgEscapeStringsAndOracleNationalQQuotesConsistentlyWithSplitting() {
        var unsafe = SqlSafetyAnalyzer.analyze(
                "update account set note=E'it\\'s where hidden'", false);
        assertTrue(unsafe.statements().getFirst().risks().contains(MISSING_WHERE));

        var batch = SqlSafetyAnalyzer.analyze(
                "select E'it\\'s'; delete from account where id=1", false);
        assertEquals(2, batch.statements().size());
        assertEquals(WRITE, batch.statements().get(1).kind());

        var oracle = SqlSafetyAnalyzer.analyze(
                "select NQ'[It's; drop table hidden]' from dual", true);
        assertEquals(1, oracle.statements().size());
        assertEquals(READ, oracle.statements().getFirst().kind());
        assertFalse(oracle.statements().getFirst().risks().contains(DESTRUCTIVE_DDL));
    }

    @Test
    void identifierDollarSequencesCannotHideFollowingWrites() {
        String[] scripts = {
                "select 1 as foo$bar$; delete from account",
                "select 1 as foo$$; delete from account"
        };
        for (String script : scripts) {
            var analysis = SqlSafetyAnalyzer.analyze(script, false);
            assertEquals(2, analysis.statements().size(), script);
            assertEquals(WRITE, analysis.statements().get(1).kind(), script);
            assertTrue(analysis.statements().get(1).risks().contains(MISSING_WHERE), script);
        }

        var oracle = SqlSafetyAnalyzer.analyze(
                "select $$ marker; delete from account", true);
        assertEquals(2, oracle.statements().size());
        assertEquals(WRITE, oracle.statements().get(1).kind());
    }

    @Test
    void nonAsciiIdentifierCodeUnitsCannotHideFollowingWrites() {
        String[] scripts = {
                "select 1 as e\u0301$bar$; delete from account",
                "select 1 as name\u200C$tag$; delete from account"
        };
        for (String script : scripts) {
            var analysis = SqlSafetyAnalyzer.analyze(script, false);
            assertEquals(2, analysis.statements().size(), script);
            assertEquals(WRITE, analysis.statements().get(1).kind(), script);
            assertTrue(analysis.statements().get(1).risks().contains(MISSING_WHERE), script);
        }
    }

    @Test
    void classifiesExplainAnalyzeAndSessionStateConflicts() {
        assertEquals(READ, SqlSafetyAnalyzer.analyze(
                "explain select * from t", false).statements().getFirst().kind());
        assertEquals(WRITE, SqlSafetyAnalyzer.analyze(
                "explain analyze delete from t where id=1", false).statements().getFirst().kind());
        assertTrue(SqlSafetyAnalyzer.analyze("begin", false).statements().getFirst().risks()
                .contains(SESSION_STATE_CONFLICT));
        assertFalse(SqlSafetyAnalyzer.analyze("commit", false).statements().getFirst().risks()
                .contains(SESSION_STATE_CONFLICT));
    }

    @Test
    void dataModifyingCtesCannotBeHiddenByReadOnlyMainStatement() {
        String[] writes = {
                "with changed as (insert into audit values (1) returning id) select * from changed",
                "with changed as (update account set state='x' where id=1 returning id) select * from changed",
                "with changed as (delete from account where id=1 returning id) select * from changed",
                "with changed as (merge into target using source on target.id=source.id "
                        + "when matched then update set value=source.value returning id) select * from changed"
        };
        for (String sql : writes) {
            assertEquals(WRITE, SqlSafetyAnalyzer.analyze(sql, false).statements().getFirst().kind(), sql);
        }

        var unsafe = SqlSafetyAnalyzer.analyze(
                "with removed as (delete from account returning id) select * from removed", false);
        assertTrue(unsafe.statements().getFirst().risks().contains(MISSING_WHERE));

        var unknown = SqlSafetyAnalyzer.analyze(
                "with opaque as (vacuum account) select * from opaque", false);
        assertEquals(UNKNOWN, unknown.statements().getFirst().kind());
        assertTrue(unknown.statements().getFirst().risks().contains(UNKNOWN_STATEMENT));
    }

    @Test
    void nestedWithInsideCteCannotHideDeleteWithoutWhere() {
        String sql = """
                with outer_change as (
                  with inner_read as (select 1)
                  delete from account returning id
                )
                select * from outer_change
                """;

        var statement = SqlSafetyAnalyzer.analyze(sql, false).statements().getFirst();
        assertEquals(WRITE, statement.kind());
        assertTrue(statement.risks().contains(MISSING_WHERE));
    }

    @Test
    void cteScopeLimitIsExactAndConservativeWithoutStackRecursion() {
        var atLimit = assertDoesNotThrow(() ->
                SqlSafetyAnalyzer.analyze(nestedWith(64), false).statements().getFirst());
        assertEquals(WRITE, atLimit.kind());
        assertTrue(atLimit.risks().contains(MISSING_WHERE));
        assertFalse(atLimit.risks().contains(UNKNOWN_STATEMENT));

        var beyondLimit = assertDoesNotThrow(() ->
                SqlSafetyAnalyzer.analyze(nestedWith(65), false).statements().getFirst());
        assertEquals(UNKNOWN, beyondLimit.kind());
        assertTrue(beyondLimit.risks().contains(MISSING_WHERE));
        assertTrue(beyondLimit.risks().contains(UNKNOWN_STATEMENT));
    }

    @Test
    void explainAnalyzeNeverFallsBackToReadForExecutableOrOpaqueTargets() {
        assertEquals(WRITE, SqlSafetyAnalyzer.analyze(
                "explain analyze execute prepared_write(1)", false)
                .statements().getFirst().kind());

        var opaque = SqlSafetyAnalyzer.analyze(
                "explain analyze provider_specific_command account", false)
                .statements().getFirst();
        assertEquals(UNKNOWN, opaque.kind());
        assertTrue(opaque.risks().contains(UNKNOWN_STATEMENT));

        assertEquals(DDL, SqlSafetyAnalyzer.analyze(
                "explain analyze create table account_copy as select * from account", false)
                .statements().getFirst().kind());

        var cte = SqlSafetyAnalyzer.analyze(
                "explain analyze with removed as (delete from account returning id) "
                        + "select * from removed", false).statements().getFirst();
        assertEquals(WRITE, cte.kind());
        assertTrue(cte.risks().contains(MISSING_WHERE));
    }

    @Test
    void beginTransactionModesAreConflictsUnlessAPlSqlBlockIsConfirmed() {
        String[] transactions = {
                "begin work isolation level serializable read only",
                "begin transaction read write",
                "begin isolation level repeatable read"
        };
        for (String sql : transactions) {
            var statement = SqlSafetyAnalyzer.analyze(sql, false).statements().getFirst();
            assertEquals(TRANSACTION_CONTROL, statement.kind(), sql);
            assertTrue(statement.risks().contains(SESSION_STATE_CONFLICT), sql);
        }

        var plsql = SqlSafetyAnalyzer.analyze("begin\n null;\nend;\n/", true)
                .statements().getFirst();
        assertEquals(WRITE, plsql.kind());
        assertFalse(plsql.risks().contains(SESSION_STATE_CONFLICT));
    }

    @Test
    void analyzesEveryStatementBeforeExecution() {
        var analysis = SqlSafetyAnalyzer.analyze(
                "select 1; update t set x=1; drop table t", false);
        assertEquals(3, analysis.statements().size());
        assertEquals(READ, analysis.statements().get(0).kind());
        assertTrue(analysis.statements().get(1).risks().contains(MISSING_WHERE));
        assertTrue(analysis.statements().get(2).risks().contains(DESTRUCTIVE_DDL));
    }

    @Test
    void lineCommentTerminatorsCannotHideFollowingWrites() {
        String[] lineEndings = {"\n", "\r\n", "\r"};
        for (String lineEnding : lineEndings) {
            var analysis = SqlSafetyAnalyzer.analyze(
                    "select 1 -- harmless" + lineEnding + "; delete from account", false);

            assertEquals(2, analysis.statements().size(), escaped(lineEnding));
            assertEquals(WRITE, analysis.statements().get(1).kind(), escaped(lineEnding));
            assertTrue(analysis.statements().get(1).risks().contains(MISSING_WHERE),
                    escaped(lineEnding));
        }
    }

    @Test
    void commentOnlyPrefixCannotHideExecutableWriteFromAnalysis() {
        String[] lineEndings = {"\n", "\r\n", "\r"};
        for (String lineEnding : lineEndings) {
            var analysis = SqlSafetyAnalyzer.analyze(
                    "-- harmless" + lineEnding + "delete from account; select 1", false);

            assertEquals(2, analysis.statements().size(), escaped(lineEnding));
            assertEquals(WRITE, analysis.statements().get(0).kind(), escaped(lineEnding));
            assertTrue(analysis.statements().get(0).risks().contains(MISSING_WHERE),
                    escaped(lineEnding));
            assertEquals(READ, analysis.statements().get(1).kind(), escaped(lineEnding));
        }
    }

    private static String nestedWith(int layers) {
        String sql = "delete from account returning id";
        for (int layer = layers; layer >= 1; layer--) {
            sql = "with c" + layer + " as (" + sql + ") select * from c" + layer;
        }
        return sql;
    }

    private static String escaped(String value) {
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }
}
