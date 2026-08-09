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
    void sessionStateConflictsAreAlwaysBlocked() {
        ConnectionSafetyOptions options =
                new ConnectionSafetyOptions(ConnectionEnvironment.DEVELOPMENT, false, 60);
        assertTrue(SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("start transaction", false), options).blocked());
    }
}
