package com.datacube.service;

import com.datacube.spi.SqlParameter;
import com.datacube.spi.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.datacube.service.RelationalWriteSafetyTest.*;

class JdbcWriteSafetyTest {
    @ParameterizedTest
    @EnumSource(value = DbType.class, names = {"ORACLE", "POSTGRESQL"})
    void directReadonlyScriptsPreparedAnalyzeAndCommitRejectBeforeOpen(DbType type) throws Exception {
        Fixture f = new Fixture(type, true, "PRODUCTION");
        try (JdbcEditorSession session = f.manager.openEditorSession("target")) {
            session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
            for (String sql : List.of("UPDATE items SET id=2 WHERE id=1", "CREATE TABLE t(id int)",
                    "CALL procedure()", "unclassified command", "COMMIT")) {
                assertThrows(IllegalStateException.class,
                        () -> session.executeScript(sql, null, 10, null, type == DbType.ORACLE));
            }
            assertThrows(IllegalStateException.class, () -> session.executePrepared(
                    "UPDATE items SET id=? WHERE id=1", List.of(new SqlParameter(Types.INTEGER, 2)), null, 0));
            assertThrows(IllegalStateException.class, () -> session.explain("SELECT 1", null, true));
            assertThrows(IllegalStateException.class, session::commit);
            if (type == DbType.ORACLE) {
                assertThrows(IllegalStateException.class, () -> session.explain("SELECT 1 FROM DUAL", null, false));
            }
            assertEquals(0, f.opens.get());
            assertEquals(0, f.writes.get());
            assertEquals(JdbcEditorSession.TransactionState.IDLE, session.snapshot().transactionState());
            session.executeScript("SELECT 1", null, 10, null, type == DbType.ORACLE);
            session.rollback();
            assertEquals(1, f.rollbacks.get());
        }
        assertEquals(1, f.closes.get());
    }

    @ParameterizedTest
    @EnumSource(value = DbType.class, names = {"ORACLE", "POSTGRESQL"})
    void productionRequestsBindSqlSchemaParametersOperationAndTarget(DbType type) throws Exception {
        Fixture f = new Fixture(type, false, "PRODUCTION");
        try (JdbcEditorSession session = f.manager.openEditorSession("target")) {
            var first = session.prepareScript("UPDATE items SET id=2 WHERE id=1", "a", 10, null, type == DbType.ORACLE);
            var confirmation = first.confirm();
            var others = List.<WriteOperation<?>>of(
                    session.prepareScript("UPDATE items SET id=3 WHERE id=1", "a", 10, null, type == DbType.ORACLE),
                    session.prepareScript("UPDATE items SET id=2 WHERE id=1", "b", 10, null, type == DbType.ORACLE),
                    session.prepareExplain("SELECT 1", "a", true),
                    session.preparePrepared("UPDATE items SET id=? WHERE id=1",
                            List.of(new SqlParameter(Types.INTEGER, 2)), "a", 0));
            for (var other : others) {
                assertThrows(IllegalStateException.class, () -> other.execute(null));
                assertThrows(IllegalStateException.class, () -> other.execute(confirmation));
            }
            assertThrows(IllegalStateException.class, () -> first.execute(null));
            assertEquals(0, f.opens.get());
            assertEquals(0, f.writes.get());
            first.execute(confirmation);
            for (var other : others) other.execute(other.confirm());
            assertEquals(5, f.writes.get());
            assertEquals(1, f.opens.get());
            assertEquals(2, f.lastParameters.getFirst().value());
            assertThrows(IllegalStateException.class, () -> first.execute(confirmation));
        }
        assertEquals(1, f.closes.get());
    }

    @Test
    void tighteningConfigurationRejectsNewWritesAndCommitWithoutChangingPendingTransaction() throws Exception {
        Fixture f = new Fixture(DbType.POSTGRESQL, false, "TEST");
        try (JdbcEditorSession session = f.manager.openEditorSession("target")) {
            session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
            session.executeScript("UPDATE items SET id=2 WHERE id=1", null, 0, null, false);
            var pending = session.snapshot();
            var oldCommit = session.prepareCommit();
            var permit = oldCommit.confirm();
            f.manager.register(new ConnectionSafetyOptions(ConnectionEnvironment.PRODUCTION, true, 60).applyTo(f.config));
            assertThrows(IllegalStateException.class, () -> session.executeScript("INSERT INTO items VALUES (1)", null, 0, null, false));
            assertThrows(IllegalStateException.class, session::commit);
            assertThrows(IllegalStateException.class, () -> oldCommit.execute(permit));
            assertEquals(pending.transactionState(), session.snapshot().transactionState());
            assertEquals(0, f.commits.get());
            assertEquals(0, f.rollbacks.get());
            assertEquals(1, f.writes.get());
            assertEquals(1, f.opens.get());
            session.rollback();
            assertEquals(1, f.rollbacks.get());
            assertEquals(JdbcEditorSession.TransactionState.IDLE, session.snapshot().transactionState());
        }
        assertEquals(1, f.closes.get());
    }

    @Test
    void productionCommitNeedsItsOwnConfirmationAndRejectsChangedTransaction() throws Exception {
        Fixture f = new Fixture(DbType.POSTGRESQL, false, "PRODUCTION");
        try (JdbcEditorSession session = f.manager.openEditorSession("target")) {
            session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
            var write = session.prepareScript("UPDATE items SET id=2 WHERE id=1", null, 0, null, false);
            write.execute(write.confirm());
            var commit = session.prepareCommit();
            var permit = commit.confirm();
            assertThrows(IllegalStateException.class, session::commit);
            assertThrows(IllegalStateException.class, () -> session.executeScript("COMMIT", null, 0, null, false));
            session.executeScript("SELECT 1", null, 1, null, false);
            assertThrows(IllegalStateException.class, () -> commit.execute(permit));
            assertEquals(0, f.commits.get());
            var current = session.prepareCommit();
            current.execute(current.confirm());
            assertEquals(1, f.commits.get());
            assertEquals(JdbcEditorSession.TransactionState.IDLE, session.snapshot().transactionState());
            session.executeScript("SELECT 2", null, 1, null, false);
            var scriptCommit = session.prepareScript("COMMIT", null, 0, null, false);
            scriptCommit.execute(scriptCommit.confirm());
            assertEquals(2, f.commits.get());
        }
    }

    @Test
    void configChangeWhileOpeningSessionPreventsDispatchAndCloseReleasesTheOwnedConnection() throws Exception {
        Fixture f = new Fixture(DbType.POSTGRESQL, false, "TEST");
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        f.onOpen = () -> { entered.countDown(); await(resume); };
        try (JdbcEditorSession session = f.manager.openEditorSession("target");
             var executor = Executors.newSingleThreadExecutor()) {
            Future<?> run = executor.submit(() -> session.executeScript(
                    "UPDATE items SET id=2 WHERE id=1", null, 0, null, false));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                f.manager.unregister("target");
            } finally { resume.countDown(); }
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class, () -> run.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(0, f.writes.get());
            assertEquals(JdbcEditorSession.TransactionState.IDLE, session.snapshot().transactionState());
        }
        assertEquals(1, f.closes.get());
    }

    @Test
    void closeAndRollbackInvalidateLateConfirmationsWithoutImplicitCommit() throws Exception {
        Fixture f = new Fixture(DbType.POSTGRESQL, false, "PRODUCTION");
        JdbcEditorSession session = f.manager.openEditorSession("target");
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
        session.executeScript("SELECT 1", null, 1, null, false);
        var commit = session.prepareCommit();
        var approval = commit.confirm();
        session.rollback();
        assertThrows(IllegalStateException.class, () -> commit.execute(approval));
        session.executeScript("SELECT 2", null, 1, null, false);
        var late = session.prepareScript("INSERT INTO items VALUES (1)", null, 0, null, false);
        var confirmed = late.confirm();
        session.closeStrict();
        assertThrows(IllegalStateException.class, () -> late.execute(confirmed));
        assertEquals(0, f.commits.get());
        assertEquals(2, f.rollbacks.get());
        assertEquals(1, f.closes.get());
        assertEquals(2, f.writes.get());
    }

    @Test
    void preparedTransactionControlCannotBypassSessionState() {
        Fixture f = new Fixture(DbType.POSTGRESQL, false, "TEST");
        try (JdbcEditorSession session = f.manager.openEditorSession("target")) {
            for (String sql : List.of("COMMIT", "ROLLBACK", "SET autocommit=1")) {
                assertThrows(IllegalStateException.class,
                        () -> session.executePrepared(sql, List.of(), null, 0));
            }
        }
        assertEquals(0, f.opens.get());
        assertEquals(0, f.writes.get());
    }
}
