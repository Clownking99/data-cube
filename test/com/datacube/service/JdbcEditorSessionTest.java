package com.datacube.service;

import com.datacube.spi.ScriptErrorPolicy;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.SqlRunner;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.ConnectionEnvironment;
import com.datacube.spi.model.ConnectionSafetyOptions;
import com.datacube.spi.model.DbType;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JdbcEditorSessionTest {

    @Test
    void manualExecutionCommitAndRollbackUpdateSnapshot() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        StubRunner runner = new StubRunner(QueryResult.update(1, 1));
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, runner);

        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
        session.executeScript("update t set x=1 where id=1", null, 100, null, false);
        assertEquals(JdbcEditorSession.TransactionState.ACTIVE,
                session.snapshot().transactionState());

        session.commit();
        assertEquals(1, jdbc.commits.get());
        assertEquals(JdbcEditorSession.TransactionState.IDLE,
                session.snapshot().transactionState());

        session.executeScript("select 1", null, 100, null, false);
        session.rollback();
        assertEquals(1, jdbc.rollbacks.get());
        assertEquals(JdbcEditorSession.TransactionState.IDLE,
                session.snapshot().transactionState());
        session.close();
    }

    @Test
    void manualScriptStopsAtFirstErrorAndCloseRollsBack() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        StubRunner runner = new StubRunner(QueryResult.error("boom", 1));
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", ConnectionSafetyOptions.from(config()), jdbc::open, runner);
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);

        session.executeScript("bad; select 1", null, 100,
                (index, sql, message) -> ScriptErrorPolicy.Decision.CONTINUE_ALL, false);
        assertEquals(JdbcEditorSession.TransactionState.ERROR_PENDING,
                session.snapshot().transactionState());
        session.close();

        assertNull(runner.lastPolicy);
        assertEquals(1, jdbc.rollbacks.get());
        assertEquals(1, jdbc.closes.get());
        assertEquals(JdbcEditorSession.ConnectionState.CLOSED,
                session.snapshot().connectionState());
    }

    @Test
    void firstConnectionAppliesReadOnlyAndCurrentTransactionMode() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.PRODUCTION, true, 17),
                jdbc::open, new StubRunner(QueryResult.update(1, 1)));

        assertEquals(JdbcEditorSession.ConnectionState.DISCONNECTED,
                session.snapshot().connectionState());
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
        session.executeScript("update t set x=1", null, 25, null, false);

        JdbcHandle handle = jdbc.handles.getFirst();
        assertTrue(handle.readOnly);
        assertFalse(handle.autoCommit);
        assertEquals(JdbcEditorSession.ConnectionState.CONNECTED,
                session.snapshot().connectionState());
        session.close();
    }

    @Test
    void failedConnectionConfigurationClosesPartialConnectionAndMarksSessionBroken() {
        JdbcStub jdbc = new JdbcStub();
        jdbc.setAutoCommitFailure = new SQLException("configuration failed");
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, true, 17),
                jdbc::open, new StubRunner(QueryResult.update(1, 1)));

        JdbcEditorSession.ExecutionBatch batch =
                session.executeScript("select 1", null, 25, null, false);

        assertEquals(QueryResult.Kind.ERROR, batch.outcomes().getFirst().result().kind);
        assertEquals(1, jdbc.opens.get());
        assertEquals(1, jdbc.closes.get());
        assertEquals(JdbcEditorSession.ConnectionState.BROKEN,
                session.snapshot().connectionState());
        session.close();
    }

    @Test
    void pendingManualWorkMustBeResolvedBeforeEnablingAutoCommit() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, new StubRunner(QueryResult.update(1, 1)));
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
        session.executeScript("update t set x=1", null, 100, null, false);

        assertThrows(IllegalStateException.class,
                () -> session.setTransactionMode(JdbcEditorSession.TransactionMode.AUTO_COMMIT));
        assertEquals(JdbcEditorSession.TransactionMode.MANUAL,
                session.snapshot().transactionMode());

        session.rollback();
        session.setTransactionMode(JdbcEditorSession.TransactionMode.AUTO_COMMIT);
        assertTrue(jdbc.handles.getFirst().autoCommit);
        assertEquals(JdbcEditorSession.TransactionMode.AUTO_COMMIT,
                session.snapshot().transactionMode());
        session.close();
    }

    @Test
    void manualCommitAndRollbackScriptsUseSessionTransactionMethods() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        StubRunner runner = new StubRunner(QueryResult.update(1, 1));
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, runner);
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);

        session.executeScript("update t set x=1", null, 100, null, false);
        session.executeScript(" COMMIT; ", null, 100, null, false);
        assertEquals(1, jdbc.commits.get());
        assertEquals(JdbcEditorSession.TransactionState.IDLE,
                session.snapshot().transactionState());

        session.executeScript("select 1", null, 100, null, false);
        session.executeScript("ROLLBACK", null, 100, null, false);
        assertEquals(1, jdbc.rollbacks.get());
        assertEquals(2, runner.scriptCalls.get(), "transaction-only scripts bypass the runner");
        session.close();
    }

    @Test
    void transactionCommandsAllowCommentsAndRedundantEmptyStatements() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        StubRunner runner = new StubRunner(QueryResult.update(1, 1));
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, runner);
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);

        for (String commit : List.of("COMMIT;;", "/*x*/ COMMIT;", "COMMIT; --x")) {
            session.executeScript("select 1", null, 100, null, false);
            session.executeScript(commit, null, 100, null, false);
            assertEquals(JdbcEditorSession.TransactionState.IDLE,
                    session.snapshot().transactionState(), commit);
        }
        for (String rollback : List.of("ROLLBACK;;", "/*x*/ ROLLBACK;", "ROLLBACK; --x")) {
            session.executeScript("select 1", null, 100, null, false);
            session.executeScript(rollback, null, 100, null, false);
            assertEquals(JdbcEditorSession.TransactionState.IDLE,
                    session.snapshot().transactionState(), rollback);
        }

        assertEquals(3, jdbc.commits.get());
        assertEquals(3, jdbc.rollbacks.get());
        assertEquals(6, runner.scriptCalls.get());
        session.close();
    }

    @Test
    void transactionCommandMustBeTheOnlyExecutableToken() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        StubRunner runner = new StubRunner(QueryResult.update(1, 1));
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, runner);
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);

        session.executeScript("COMMIT WORK;", null, 100, null, false);

        assertEquals(0, jdbc.commits.get());
        assertEquals(1, runner.scriptCalls.get());
        assertEquals(JdbcEditorSession.TransactionState.ACTIVE,
                session.snapshot().transactionState());
        session.close();
    }

    @Test
    void unterminatedTransactionCommentFallsBackToRunner() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        StubRunner runner = new StubRunner(QueryResult.error("syntax error", 1));
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, runner);
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);

        JdbcEditorSession.ExecutionBatch batch = session.executeScript(
                "COMMIT /* unterminated", null, 100, null, false);

        assertEquals(0, jdbc.commits.get());
        assertEquals(1, runner.scriptCalls.get());
        assertEquals(QueryResult.FailureKind.SQL_ERROR,
                batch.outcomes().getFirst().result().failureKind);
        assertEquals(JdbcEditorSession.TransactionState.ERROR_PENDING,
                session.snapshot().transactionState());
        session.close();
    }

    @Test
    void postgresNestedTransactionCommentCompletesWhileOracleFallsBack() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        StubRunner runner = new StubRunner(QueryResult.update(1, 1));
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, runner);
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
        session.executeScript("select 1", null, 100, null, false);

        session.executeScript(
                "COMMIT /* outer /* inner */ tail */;", null, 100, null, false);

        assertEquals(1, jdbc.commits.get());
        assertEquals(1, runner.scriptCalls.get());
        assertEquals(JdbcEditorSession.TransactionState.IDLE,
                session.snapshot().transactionState());

        session.executeScript("select 2", null, 100, null, true);
        session.executeScript(
                "COMMIT /* outer /* inner */ tail */;", null, 100, null, true);

        assertEquals(1, jdbc.commits.get(), "Oracle nested comment must not bypass the runner");
        assertEquals(3, runner.scriptCalls.get());
        assertEquals(JdbcEditorSession.TransactionState.ACTIVE,
                session.snapshot().transactionState());
        session.close();
    }

    @Test
    void failedCommitKeepsPendingStateForExplicitRecovery() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, new StubRunner(QueryResult.update(1, 1)));
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
        session.executeScript("update t set x=1", null, 100, null, false);
        jdbc.commitFailure = new SQLException("commit failed");

        SQLException failure = assertThrows(SQLException.class, session::commit);

        assertEquals("commit failed", failure.getMessage());
        assertEquals(JdbcEditorSession.TransactionState.ACTIVE,
                session.snapshot().transactionState());
        jdbc.commitFailure = null;
        session.rollback();
        session.close();
    }

    @Test
    void brokenActiveTransactionCannotCommitOnAReplacementConnection() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        FirstThenBlockingRunner runner = new FirstThenBlockingRunner(
                QueryResult.update(1, 1), QueryResult.update(1, 1));
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, runner);
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
        session.executeScript("update t set x=1", null, 100, null, false);
        AtomicReference<Throwable> executionFailure = new AtomicReference<>();
        Thread execution = Thread.ofVirtual().start(() -> {
            try {
                session.executeScript("select slow", null, 100, null, false);
            } catch (Throwable failure) {
                executionFailure.set(failure);
            }
        });
        await(runner.blocked);
        assertEquals(JdbcEditorSession.CancelOutcome.CONNECTION_CLOSED, session.cancel());
        runner.release.countDown();
        join(execution);

        IllegalStateException executeFailure = assertThrows(IllegalStateException.class,
                () -> session.executeScript("select after broken", null, 100, null, false));
        IllegalStateException failure = assertThrows(IllegalStateException.class, session::commit);

        assertTrue(executeFailure.getMessage().contains("重新连接"));
        assertTrue(failure.getMessage().contains("重新连接"));
        assertNull(executionFailure.get());
        assertEquals(1, jdbc.opens.get());
        assertEquals(2, runner.scriptCalls.get(), "broken pending SQL must not reach the runner");
        assertEquals(JdbcEditorSession.ConnectionState.BROKEN,
                session.snapshot().connectionState());
        assertEquals(JdbcEditorSession.TransactionState.ACTIVE,
                session.snapshot().transactionState());
        session.close();
    }

    @Test
    void brokenErrorPendingTransactionCannotRollbackOnAReplacementConnection() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        FirstThenBlockingRunner runner = new FirstThenBlockingRunner(
                QueryResult.error("first failed", 1), QueryResult.error("cancelled", 1));
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, runner);
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
        session.executeScript("bad", null, 100, null, false);
        Thread execution = Thread.ofVirtual().start(
                () -> session.executeScript("select slow", null, 100, null, false));
        await(runner.blocked);
        assertEquals(JdbcEditorSession.CancelOutcome.CONNECTION_CLOSED, session.cancel());
        runner.release.countDown();
        join(execution);

        IllegalStateException explainFailure = assertThrows(IllegalStateException.class,
                () -> session.explain("select after broken", null, false));
        IllegalStateException failure = assertThrows(IllegalStateException.class, session::rollback);

        assertTrue(explainFailure.getMessage().contains("重新连接"));
        assertTrue(failure.getMessage().contains("重新连接"));
        assertEquals(1, jdbc.opens.get());
        assertEquals(0, runner.explainCalls.get(), "broken pending explain must not reach the runner");
        assertEquals(JdbcEditorSession.ConnectionState.BROKEN,
                session.snapshot().connectionState());
        assertEquals(JdbcEditorSession.TransactionState.ERROR_PENDING,
                session.snapshot().transactionState());
        session.close();
    }

    @Test
    void closeIsIdempotentAndFinishesCleanupWhenJdbcCleanupThrows() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, new StubRunner(QueryResult.error("boom", 1)));
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
        session.executeScript("bad", null, 100, null, false);
        jdbc.rollbackFailure = new SQLException("rollback failed");
        jdbc.closeFailure = new SQLException("close failed");

        assertDoesNotThrow(session::close);
        assertDoesNotThrow(session::close);

        assertEquals(1, jdbc.rollbacks.get());
        assertEquals(1, jdbc.closes.get());
        assertEquals(JdbcEditorSession.ConnectionState.CLOSED,
                session.snapshot().connectionState());
        assertEquals(JdbcEditorSession.TransactionState.IDLE,
                session.snapshot().transactionState());
    }

    @Test
    void closeRetriesAConnectionWhoseFirstCloseFailureLeftItOpen() {
        JdbcStub jdbc = new JdbcStub();
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, new StubRunner(QueryResult.update(1, 1)));
        session.executeScript("select 1", null, 100, null, false);
        jdbc.closeFailure = new SQLException("close failed before release");
        jdbc.closeFailureLeavesOpen = true;

        session.close();
        assertEquals(1, jdbc.closes.get());
        assertFalse(jdbc.handles.getFirst().closed);
        assertEquals(JdbcEditorSession.ConnectionState.CLOSED,
                session.snapshot().connectionState());

        jdbc.closeFailure = null;
        session.close();

        assertEquals(2, jdbc.closes.get());
        assertTrue(jdbc.handles.getFirst().closed);
    }

    @Test
    void cancelWithoutActiveStatementClosesDedicatedConnectionWithoutWaitingForExecution() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        BlockingRunner runner = new BlockingRunner();
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, runner);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread execution = Thread.ofVirtual().start(() -> {
            try {
                session.executeScript("select 1", null, 100, null, false);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        await(runner.entered);

        assertEquals(JdbcEditorSession.CancelOutcome.CONNECTION_CLOSED, session.cancel());
        assertEquals(1, jdbc.closes.get());
        assertEquals(JdbcEditorSession.ConnectionState.BROKEN,
                session.snapshot().connectionState());

        runner.release.countDown();
        join(execution);
        assertNull(failure.get());
        assertFalse(session.snapshot().running());
        assertFalse(session.snapshot().cancelling());
        session.close();
    }

    @Test
    void cancelActiveStatementUsesJdbcCancelAndKeepsConnectionOwnedBySession() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        ActiveStatementRunner runner = new ActiveStatementRunner(false, false);
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, runner);
        Thread execution = Thread.ofVirtual().start(
                () -> session.executeScript("select slow", null, 100, null, false));
        await(runner.entered);

        assertEquals(JdbcEditorSession.CancelOutcome.CANCELLED, session.cancel());
        join(execution);

        assertEquals(1, runner.cancelCalls.get());
        assertEquals(0, jdbc.closes.get());
        assertEquals(JdbcEditorSession.ConnectionState.CONNECTED,
                session.snapshot().connectionState());
        assertFalse(session.snapshot().running());
        session.close();
    }

    @Test
    void cancelFailureClosesDedicatedConnectionAndMarksItBroken() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        ActiveStatementRunner runner = new ActiveStatementRunner(true, false);
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, runner);
        Thread execution = Thread.ofVirtual().start(
                () -> session.executeScript("select slow", null, 100, null, false));
        await(runner.entered);

        assertEquals(JdbcEditorSession.CancelOutcome.CONNECTION_CLOSED, session.cancel());
        join(execution);

        assertEquals(1, runner.cancelCalls.get());
        assertEquals(1, jdbc.closes.get());
        assertEquals(JdbcEditorSession.ConnectionState.BROKEN,
                session.snapshot().connectionState());
        session.close();
    }

    @Test
    void closeCancelsRunningExecutionThenClosesOwnedConnectionExactlyOnce() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        ActiveStatementRunner runner = new ActiveStatementRunner(false, false);
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, runner);
        Thread execution = Thread.ofVirtual().start(
                () -> session.executeScript("select slow", null, 100, null, false));
        await(runner.entered);

        Thread closer = Thread.ofVirtual().start(session::close);
        join(execution);
        join(closer);
        session.close();

        assertEquals(1, runner.cancelCalls.get());
        assertEquals(1, jdbc.closes.get());
        assertEquals(JdbcEditorSession.ConnectionState.CLOSED,
                session.snapshot().connectionState());
        assertFalse(session.snapshot().running());
    }

    @Test
    void closeRequestRejectsAnOperationAlreadyQueuedBehindRunningExecution() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        ActiveStatementRunner runner = new ActiveStatementRunner(false, false);
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, runner);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        Thread first = Thread.ofVirtual().start(() -> {
            try {
                session.executeScript("select slow", null, 100, null, false);
            } catch (Throwable failure) {
                firstFailure.set(failure);
            }
        });
        await(runner.entered);

        CountDownLatch secondCalling = new CountDownLatch(1);
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread second = Thread.ofVirtual().start(() -> {
            secondCalling.countDown();
            try {
                session.executeScript("select queued", null, 100, null, false);
            } catch (Throwable failure) {
                secondFailure.set(failure);
            }
        });
        await(secondCalling);
        awaitWaiting(second);

        Thread closer = Thread.ofVirtual().start(session::close);
        join(first);
        join(second);
        join(closer);

        assertNull(firstFailure.get());
        assertInstanceOf(IllegalStateException.class, secondFailure.get());
        assertEquals(1, runner.scriptCalls.get(), "queued SQL must never reach the runner");
        assertEquals(JdbcEditorSession.ConnectionState.CLOSED,
                session.snapshot().connectionState());
    }

    @Test
    void closeBetweenPrecheckAndPublicationPreventsExecuteFromOpeningJdbc() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        StubRunner runner = new StubRunner(QueryResult.update(1, 1));
        CountDownLatch beforePublish = new CountDownLatch(1);
        CountDownLatch allowPublish = new CountDownLatch(1);
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, runner, () -> {
                    beforePublish.countDown();
                    awaitUnchecked(allowPublish);
                });
        AtomicReference<Throwable> executionFailure = new AtomicReference<>();
        Thread execution = Thread.ofVirtual().start(() -> {
            try {
                session.executeScript("select must-not-run", null, 100, null, false);
            } catch (Throwable failure) {
                executionFailure.set(failure);
            }
        });
        await(beforePublish);

        Thread closer = Thread.ofVirtual().start(session::close);
        awaitWaiting(closer);
        allowPublish.countDown();
        join(execution);
        join(closer);

        assertInstanceOf(IllegalStateException.class, executionFailure.get());
        assertEquals(0, jdbc.opens.get());
        assertEquals(0, runner.scriptCalls.get());
        assertEquals(JdbcEditorSession.ConnectionState.CLOSED,
                session.snapshot().connectionState());
    }

    @Test
    void closeBetweenPrecheckAndPublicationPreventsExplainFromOpeningJdbc() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        StubRunner runner = new StubRunner(QueryResult.update(1, 1));
        CountDownLatch beforePublish = new CountDownLatch(1);
        CountDownLatch allowPublish = new CountDownLatch(1);
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, runner, () -> {
                    beforePublish.countDown();
                    awaitUnchecked(allowPublish);
                });
        AtomicReference<Throwable> executionFailure = new AtomicReference<>();
        Thread execution = Thread.ofVirtual().start(() -> {
            try {
                session.explain("select must-not-run", null, false);
            } catch (Throwable failure) {
                executionFailure.set(failure);
            }
        });
        await(beforePublish);

        Thread closer = Thread.ofVirtual().start(session::close);
        awaitWaiting(closer);
        allowPublish.countDown();
        join(execution);
        join(closer);

        assertInstanceOf(IllegalStateException.class, executionFailure.get());
        assertEquals(0, jdbc.opens.get());
        assertEquals(0, runner.explainCalls.get());
        assertEquals(JdbcEditorSession.ConnectionState.CLOSED,
                session.snapshot().connectionState());
    }

    @Test
    void unsupportedStatementTimeoutIsVisibleInSnapshot() {
        JdbcStub jdbc = new JdbcStub();
        ActiveStatementRunner runner = new ActiveStatementRunner(false, true);
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 9),
                jdbc::open, runner);

        session.executeScript("select 1", null, 100, null, false);

        assertFalse(session.snapshot().timeoutSupported());
        assertEquals(9, runner.queryTimeout.get());
        session.close();
    }

    @Test
    void reconnectReplacesOnlyTheSessionConnection() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, true, 30),
                jdbc::open, new StubRunner(QueryResult.update(1, 1)));
        session.executeScript("select 1", null, 100, null, false);
        Connection first = jdbc.handles.getFirst().connection;

        session.reconnect();

        assertEquals(2, jdbc.opens.get());
        assertEquals(1, jdbc.closes.get());
        assertNotSame(first, jdbc.handles.getLast().connection);
        assertTrue(jdbc.handles.getLast().readOnly);
        assertEquals(JdbcEditorSession.ConnectionState.CONNECTED,
                session.snapshot().connectionState());
        session.close();
    }

    @Test
    void reconnectRollsBackPendingManualWorkBeforeReplacingConnection() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                jdbc::open, new StubRunner(QueryResult.update(1, 1)));
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
        session.executeScript("update t set x=1", null, 100, null, false);

        session.reconnect();

        assertEquals(1, jdbc.rollbacks.get());
        assertEquals(1, jdbc.closes.get());
        assertEquals(2, jdbc.opens.get());
        assertEquals(JdbcEditorSession.TransactionState.IDLE,
                session.snapshot().transactionState());
        assertFalse(jdbc.handles.getLast().autoCommit);
        session.close();
    }

    @Test
    void cancelWhileTransactionCommandConnectionIsOpeningPreventsCommit() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch allowOpen = new CountDownLatch(1);
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                () -> {
                    opening.countDown();
                    awaitUnchecked(allowOpen);
                    return jdbc.open();
                }, new StubRunner(QueryResult.update(1, 1)));
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
        AtomicReference<JdbcEditorSession.ExecutionBatch> batch = new AtomicReference<>();
        Thread execution = Thread.ofVirtual().start(() -> batch.set(
                session.executeScript("COMMIT", null, 100, null, false)));
        await(opening);

        assertEquals(JdbcEditorSession.CancelOutcome.CONNECTION_CLOSED, session.cancel());
        allowOpen.countDown();
        join(execution);

        assertEquals(0, jdbc.commits.get());
        assertEquals(1, jdbc.closes.get());
        assertEquals(QueryResult.FailureKind.CANCELLED,
                batch.get().outcomes().getFirst().result().failureKind);
        assertEquals(JdbcEditorSession.ConnectionState.BROKEN,
                session.snapshot().connectionState());
        session.close();
    }

    @Test
    void cancelWhileExplainConnectionIsOpeningReturnsCancelledFailureKind() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch allowOpen = new CountDownLatch(1);
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
                () -> {
                    opening.countDown();
                    awaitUnchecked(allowOpen);
                    return jdbc.open();
                }, new StubRunner(QueryResult.update(1, 1)));
        AtomicReference<QueryResult> result = new AtomicReference<>();
        Thread execution = Thread.ofVirtual().start(
                () -> result.set(session.explain("select 1", null, false)));
        await(opening);

        assertEquals(JdbcEditorSession.CancelOutcome.CONNECTION_CLOSED, session.cancel());
        allowOpen.countDown();
        join(execution);

        assertEquals(QueryResult.FailureKind.CANCELLED, result.get().failureKind);
        assertEquals(1, jdbc.closes.get());
        assertEquals(JdbcEditorSession.ConnectionState.BROKEN,
                session.snapshot().connectionState());
        session.close();
    }

    @Test
    void unsupportedTimeoutCapabilityDoesNotRecoverAfterStatementFreeOperation() {
        JdbcStub jdbc = new JdbcStub();
        TimeoutThenStatementFreeRunner runner = new TimeoutThenStatementFreeRunner();
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 12),
                jdbc::open, runner);

        session.executeScript("select timeout-unsupported", null, 100, null, false);
        assertFalse(session.snapshot().timeoutSupported());
        session.executeScript("select no-statement", null, 100, null, false);

        assertFalse(session.snapshot().timeoutSupported());
        assertEquals(1, runner.timeoutAttempts.get());
        session.close();
    }

    @Test
    void successfulReconnectResetsTimeoutCapabilityForNewConnection() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        TimeoutThenStatementFreeRunner runner = new TimeoutThenStatementFreeRunner();
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 12),
                jdbc::open, runner);
        session.executeScript("select timeout-unsupported", null, 100, null, false);
        assertFalse(session.snapshot().timeoutSupported());

        session.reconnect();

        assertTrue(session.snapshot().timeoutSupported());
        assertEquals(2, jdbc.opens.get());
        session.close();
    }

    @Test
    void autoCommitForwardsErrorPolicyAndKeepsTransactionIdle() {
        JdbcStub jdbc = new JdbcStub();
        StubRunner runner = new StubRunner(QueryResult.error("boom", 1));
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 23),
                jdbc::open, runner);
        ScriptErrorPolicy policy = (index, sql, message) -> ScriptErrorPolicy.Decision.CONTINUE;

        JdbcEditorSession.ExecutionBatch batch =
                session.executeScript("bad", "public", 77, policy, false);

        assertSame(policy, runner.lastPolicy);
        assertEquals(77, runner.lastOptions.maxRows());
        assertEquals(23, runner.lastOptions.queryTimeoutSeconds());
        assertEquals(1, batch.outcomes().size());
        assertEquals(JdbcEditorSession.TransactionState.IDLE,
                session.snapshot().transactionState());
        session.close();
    }

    @Test
    void manualExplainUsesSafetyTimeoutAndTracksFailureAsPending() throws Exception {
        JdbcStub jdbc = new JdbcStub();
        StubRunner runner = new StubRunner(QueryResult.error("explain failed", 1));
        JdbcEditorSession session = new JdbcEditorSession(
                "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 41),
                jdbc::open, runner);
        session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);

        QueryResult result = session.explain("select 1", "public", true);

        assertEquals(QueryResult.Kind.ERROR, result.kind);
        assertEquals(0, runner.lastOptions.maxRows());
        assertEquals(41, runner.lastOptions.queryTimeoutSeconds());
        assertEquals(JdbcEditorSession.TransactionState.ERROR_PENDING,
                session.snapshot().transactionState());
        session.close();
    }

    private static ConnConfig config() {
        return new ConnConfig("conn", "test", DbType.POSTGRESQL, "localhost", 5432,
                "db", "user", "encrypted", Map.of());
    }

    private static final class StubRunner implements SqlRunner {
        private final QueryResult result;
        private final AtomicInteger scriptCalls = new AtomicInteger();
        private final AtomicInteger explainCalls = new AtomicInteger();
        private ScriptErrorPolicy lastPolicy;
        private SqlExecutionOptions lastOptions;

        private StubRunner(QueryResult result) {
            this.result = result;
        }

        @Override
        public QueryResult execute(
                Connection connection, String sql, String schema, SqlExecutionOptions options) {
            return result;
        }

        @Override
        public List<ScriptOutcome> executeScript(
                Connection connection,
                String script,
                String schema,
                SqlExecutionOptions options,
                ScriptErrorPolicy policy) {
            scriptCalls.incrementAndGet();
            lastPolicy = policy;
            lastOptions = options;
            return List.of(new ScriptOutcome(1, script, result));
        }

        @Override
        public QueryResult explain(
                Connection connection,
                String sql,
                String schema,
                boolean analyze,
                SqlExecutionOptions options) {
            explainCalls.incrementAndGet();
            lastOptions = options;
            return result;
        }
    }

    private static final class BlockingRunner implements SqlRunner {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public QueryResult execute(
                Connection connection, String sql, String schema, SqlExecutionOptions options) {
            return QueryResult.update(1, 1);
        }

        @Override
        public List<ScriptOutcome> executeScript(
                Connection connection, String script, String schema,
                SqlExecutionOptions options, ScriptErrorPolicy policy) {
            entered.countDown();
            awaitUnchecked(release);
            return List.of(new ScriptOutcome(1, script, QueryResult.cancelled("cancelled", 1)));
        }

        @Override
        public QueryResult explain(
                Connection connection, String sql, String schema, boolean analyze,
                SqlExecutionOptions options) {
            return QueryResult.update(1, 1);
        }
    }

    private static final class FirstThenBlockingRunner implements SqlRunner {
        private final QueryResult firstResult;
        private final QueryResult blockedResult;
        private final AtomicInteger scriptCalls = new AtomicInteger();
        private final AtomicInteger explainCalls = new AtomicInteger();
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private FirstThenBlockingRunner(QueryResult firstResult, QueryResult blockedResult) {
            this.firstResult = firstResult;
            this.blockedResult = blockedResult;
        }

        @Override
        public QueryResult execute(
                Connection connection, String sql, String schema, SqlExecutionOptions options) {
            return firstResult;
        }

        @Override
        public List<ScriptOutcome> executeScript(
                Connection connection, String script, String schema,
                SqlExecutionOptions options, ScriptErrorPolicy policy) {
            QueryResult result;
            if (scriptCalls.getAndIncrement() == 0) {
                result = firstResult;
            } else {
                blocked.countDown();
                awaitUnchecked(release);
                result = blockedResult;
            }
            return List.of(new ScriptOutcome(1, script, result));
        }

        @Override
        public QueryResult explain(
                Connection connection, String sql, String schema, boolean analyze,
                SqlExecutionOptions options) {
            explainCalls.incrementAndGet();
            return firstResult;
        }
    }

    private static final class ActiveStatementRunner implements SqlRunner {
        private final boolean cancelFails;
        private final boolean timeoutUnsupported;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicInteger queryTimeout = new AtomicInteger(-1);
        private final AtomicInteger scriptCalls = new AtomicInteger();

        private ActiveStatementRunner(boolean cancelFails, boolean timeoutUnsupported) {
            this.cancelFails = cancelFails;
            this.timeoutUnsupported = timeoutUnsupported;
        }

        @Override
        public QueryResult execute(
                Connection connection, String sql, String schema, SqlExecutionOptions options) {
            return QueryResult.update(1, 1);
        }

        @Override
        public List<ScriptOutcome> executeScript(
                Connection connection, String script, String schema,
                SqlExecutionOptions options, ScriptErrorPolicy policy) {
            scriptCalls.incrementAndGet();
            Statement statement = (Statement) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                        if (method.getName().equals("setQueryTimeout")) {
                            queryTimeout.set((Integer) args[0]);
                            if (timeoutUnsupported) {
                                throw new SQLFeatureNotSupportedException("unsupported");
                            }
                            return null;
                        }
                        if (method.getName().equals("cancel")) {
                            cancelCalls.incrementAndGet();
                            cancelled.countDown();
                            if (cancelFails) throw new SQLException("cancel failed");
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });
            try {
                var activation = options.control().activate(statement, options.queryTimeoutSeconds());
                entered.countDown();
                if (!timeoutUnsupported) awaitUnchecked(cancelled);
                options.control().release(activation);
                QueryResult result = options.control().cancellationRequested()
                        ? QueryResult.cancelled("cancelled", 1)
                        : QueryResult.update(1, 1);
                return List.of(new ScriptOutcome(1, script, result));
            } catch (SQLException error) {
                return List.of(new ScriptOutcome(1, script, QueryResult.error(error.getMessage(), 1)));
            }
        }

        @Override
        public QueryResult explain(
                Connection connection, String sql, String schema, boolean analyze,
                SqlExecutionOptions options) {
            return QueryResult.update(1, 1);
        }
    }

    private static final class TimeoutThenStatementFreeRunner implements SqlRunner {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger timeoutAttempts = new AtomicInteger();

        @Override
        public QueryResult execute(
                Connection connection, String sql, String schema, SqlExecutionOptions options) {
            return QueryResult.update(1, 1);
        }

        @Override
        public List<ScriptOutcome> executeScript(
                Connection connection, String script, String schema,
                SqlExecutionOptions options, ScriptErrorPolicy policy) {
            if (calls.getAndIncrement() == 0) {
                Statement statement = (Statement) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{Statement.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("setQueryTimeout")) {
                                timeoutAttempts.incrementAndGet();
                                throw new SQLFeatureNotSupportedException("unsupported");
                            }
                            return defaultValue(method.getReturnType());
                        });
                try {
                    var activation = options.control().activate(
                            statement, options.queryTimeoutSeconds());
                    options.control().release(activation);
                } catch (SQLException failure) {
                    throw new AssertionError(failure);
                }
            }
            return List.of(new ScriptOutcome(1, script, QueryResult.update(1, 1)));
        }

        @Override
        public QueryResult explain(
                Connection connection, String sql, String schema, boolean analyze,
                SqlExecutionOptions options) {
            return QueryResult.update(1, 1);
        }
    }

    private static final class JdbcStub {
        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final List<JdbcHandle> handles = new ArrayList<>();
        private SQLException commitFailure;
        private SQLException rollbackFailure;
        private SQLException closeFailure;
        private SQLException setAutoCommitFailure;
        private boolean closeFailureLeavesOpen;

        private Connection open() {
            opens.incrementAndGet();
            JdbcHandle handle = new JdbcHandle();
            Connection connection = (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "setAutoCommit" -> {
                                if (setAutoCommitFailure != null) throw setAutoCommitFailure;
                                handle.autoCommit = (Boolean) args[0];
                                yield null;
                            }
                            case "getAutoCommit" -> handle.autoCommit;
                            case "setReadOnly" -> {
                                handle.readOnly = (Boolean) args[0];
                                yield null;
                            }
                            case "isReadOnly" -> handle.readOnly;
                            case "commit" -> {
                                commits.incrementAndGet();
                                if (commitFailure != null) throw commitFailure;
                                yield null;
                            }
                            case "rollback" -> {
                                rollbacks.incrementAndGet();
                                if (rollbackFailure != null) throw rollbackFailure;
                                yield null;
                            }
                            case "close" -> {
                                if (!handle.closed) {
                                    closes.incrementAndGet();
                                    if (closeFailure != null) {
                                        if (!closeFailureLeavesOpen) handle.closed = true;
                                        throw closeFailure;
                                    }
                                    handle.closed = true;
                                }
                                yield null;
                            }
                            case "isClosed" -> handle.closed;
                            case "isValid" -> !handle.closed;
                            default -> defaultValue(method.getReturnType());
                        };
                    });
            handle.connection = connection;
            handles.add(handle);
            return connection;
        }
    }

    private static final class JdbcHandle {
        private Connection connection;
        private boolean autoCommit = true;
        private boolean readOnly;
        private boolean closed;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for virtual-thread operation");
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for virtual-thread operation");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(5_000);
        assertFalse(thread.isAlive(), "virtual-thread operation did not finish");
    }

    private static void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && thread.getState() != Thread.State.WAITING
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.WAITING, thread.getState(),
                "virtual thread did not queue on the single-flight lock");
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
