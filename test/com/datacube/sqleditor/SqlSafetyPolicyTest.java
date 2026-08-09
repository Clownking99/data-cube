package com.datacube.sqleditor;

import com.datacube.spi.model.ConnectionEnvironment;
import com.datacube.spi.model.ConnectionSafetyOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlSafetyPolicyTest {
    @Test
    void readOnlyBlocksWritesDdlAndUnknownStatements() {
        ConnectionSafetyOptions options =
                new ConnectionSafetyOptions(ConnectionEnvironment.DEVELOPMENT, true, 60);
        assertTrue(SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("insert into t values (1)", false), options).blocked());
        assertFalse(SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("select * from t", false), options).blocked());
    }

    @Test
    void productionRequiresConfirmationForEveryNonReadStatement() {
        ConnectionSafetyOptions options =
                new ConnectionSafetyOptions(ConnectionEnvironment.PRODUCTION, false, 60);
        assertTrue(SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("update t set x=1 where id=1", false), options)
                .confirmationRequired());
        assertFalse(SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("select 1", false), options).confirmationRequired());
    }

    @Test
    void dangerousStatementsRequireConfirmationInEveryEnvironment() {
        ConnectionSafetyOptions options =
                new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 60);
        assertTrue(SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("delete from t", false), options).confirmationRequired());
        assertTrue(SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("drop table t", false), options).confirmationRequired());
    }

    @Test
    void developmentRequiresConfirmationForNestedCteDeleteWithoutWhere() {
        ConnectionSafetyOptions options =
                new ConnectionSafetyOptions(ConnectionEnvironment.DEVELOPMENT, false, 60);
        String sql = """
                with outer_change as (
                  with inner_read as (select 1)
                  delete from account returning id
                )
                select * from outer_change
                """;

        var decision = SqlSafetyPolicy.decide(SqlSafetyAnalyzer.analyze(sql, false), options);

        assertFalse(decision.blocked());
        assertTrue(decision.confirmationRequired());
        assertEquals(1, decision.relevantStatements().size());
    }

    @Test
    void developmentRequiresConfirmationBeyondCteScopeLimit() {
        ConnectionSafetyOptions options =
                new ConnectionSafetyOptions(ConnectionEnvironment.DEVELOPMENT, false, 60);

        var decision = assertDoesNotThrow(() -> SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze(nestedWith(65), false), options));

        assertFalse(decision.blocked());
        assertTrue(decision.confirmationRequired());
        assertEquals(1, decision.relevantStatements().size());
    }

    @Test
    void sessionStateConflictsAreAlwaysBlocked() {
        ConnectionSafetyOptions options =
                new ConnectionSafetyOptions(ConnectionEnvironment.DEVELOPMENT, false, 60);
        assertTrue(SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("start transaction", false), options).blocked());
    }

    @Test
    void blockedDecisionCollectsSessionAndReadOnlyViolationsInScriptOrder() {
        ConnectionSafetyOptions options =
                new ConnectionSafetyOptions(ConnectionEnvironment.DEVELOPMENT, true, 60);

        var decision = SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("begin; insert into t values (1)", false), options);

        assertTrue(decision.blocked());
        assertFalse(decision.confirmationRequired());
        assertEquals(2, decision.relevantStatements().size());
        assertEquals(1, decision.relevantStatements().get(0).index());
        assertEquals(2, decision.relevantStatements().get(1).index());
        assertTrue(decision.message().contains("会话状态"));
    }

    private static String nestedWith(int layers) {
        String sql = "delete from account returning id";
        for (int layer = layers; layer >= 1; layer--) {
            sql = "with c" + layer + " as (" + sql + ") select * from c" + layer;
        }
        return sql;
    }
}
