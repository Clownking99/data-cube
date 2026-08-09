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
    void analyzesEveryStatementBeforeExecution() {
        var analysis = SqlSafetyAnalyzer.analyze(
                "select 1; update t set x=1; drop table t", false);
        assertEquals(3, analysis.statements().size());
        assertEquals(READ, analysis.statements().get(0).kind());
        assertTrue(analysis.statements().get(1).risks().contains(MISSING_WHERE));
        assertTrue(analysis.statements().get(2).risks().contains(DESTRUCTIVE_DDL));
    }
}
