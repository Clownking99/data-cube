package com.datacube.sqleditor;

import org.junit.jupiter.api.Test;

import static com.datacube.sqleditor.SqlSafetyAnalyzer.Risk.*;
import static com.datacube.sqleditor.SqlSafetyAnalyzer.StatementKind.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlSafetyAnalyzerTest {
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
}
