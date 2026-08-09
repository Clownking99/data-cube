package com.datacube.service;

import com.datacube.sqleditor.SqlSafetyAnalyzer;
import com.datacube.spi.ScriptErrorPolicy;
import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.SqlRunner;
import com.datacube.spi.model.ConnectionSafetyOptions;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Caller-owned JDBC session for one SQL editor tab. */
public final class JdbcEditorSession implements AutoCloseable {

    private static final Runnable NO_OPERATION_PUBLISH_HOOK = () -> {};

    public enum ConnectionState { DISCONNECTED, CONNECTED, BROKEN, CLOSED }
    public enum TransactionMode { AUTO_COMMIT, MANUAL }
    public enum TransactionState { IDLE, ACTIVE, ERROR_PENDING }
    public enum CancelOutcome { CANCELLED, CONNECTION_CLOSED, NOTHING_RUNNING }

    public record ExecutionBatch(List<ScriptOutcome> outcomes, long elapsedMillis) {
        public ExecutionBatch {
            outcomes = List.copyOf(outcomes);
        }
    }

    public record Snapshot(
            String connectionId,
            ConnectionState connectionState,
            TransactionMode transactionMode,
            TransactionState transactionState,
            boolean running,
            boolean cancelling,
            boolean timeoutSupported,
            ConnectionSafetyOptions safety) {
        public boolean hasPendingTransaction() {
            return transactionState != TransactionState.IDLE;
        }
    }

    @FunctionalInterface
    interface ConnectionOpener {
        Connection open() throws SQLException;
    }

    private final String connectionId;
    private final ConnectionSafetyOptions safety;
    private final ConnectionOpener opener;
    private final SqlRunner runner;
    private final Runnable beforeOperationPublish;
    private final ReentrantLock singleFlight = new ReentrantLock();
    private final AtomicReference<Connection> connection = new AtomicReference<>();
    private final AtomicReference<SqlExecutionControl> activeControl = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean cancelling = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final Object cleanupMonitor = new Object();

    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private volatile TransactionMode transactionMode = TransactionMode.AUTO_COMMIT;
    private volatile TransactionState transactionState = TransactionState.IDLE;
    private volatile Connection transactionConnection;
    private volatile Connection cleanupConnection;
    private volatile boolean timeoutSupported = true;

    JdbcEditorSession(
            String connectionId,
            ConnectionSafetyOptions safety,
            ConnectionOpener opener,
            SqlRunner runner) {
        this(connectionId, safety, opener, runner, NO_OPERATION_PUBLISH_HOOK);
    }

    JdbcEditorSession(
            String connectionId,
            ConnectionSafetyOptions safety,
            ConnectionOpener opener,
            SqlRunner runner,
            Runnable beforeOperationPublish) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.safety = Objects.requireNonNull(safety, "safety");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.beforeOperationPublish = Objects.requireNonNull(
                beforeOperationPublish, "beforeOperationPublish");
    }

    public ExecutionBatch executeScript(
            String script,
            String schema,
            int maxRows,
            ScriptErrorPolicy policy,
            boolean oracleMode) {
        Objects.requireNonNull(script, "script");
        singleFlight.lock();
        SqlExecutionControl control = null;
        long startedAt = System.currentTimeMillis();
        try {
            ensureOpen();
            control = beginOperation();
            ensureOpen();
            if (transactionMode == TransactionMode.MANUAL) {
                TransactionCommand command = transactionCommand(script, oracleMode);
                if (command != null) {
                    return executeTransactionCommand(command, script, startedAt, control);
                }
            }

            SqlExecutionOptions options =
                    new SqlExecutionOptions(maxRows, safety.queryTimeoutSeconds(), control);
            ScriptErrorPolicy effectivePolicy =
                    transactionMode == TransactionMode.MANUAL ? null : policy;
            List<ScriptOutcome> outcomes =
                    runner.executeScript(connection(control), script, schema, options, effectivePolicy);
            updateTransactionState(outcomes);
            long elapsedMillis = System.currentTimeMillis() - startedAt;
            return new ExecutionBatch(outcomes, elapsedMillis);
        } catch (SQLException failure) {
            QueryResult result = executionFailure(failure, startedAt, control);
            List<ScriptOutcome> outcomes = List.of(new ScriptOutcome(1, script, result));
            updateTransactionState(outcomes);
            return new ExecutionBatch(outcomes, System.currentTimeMillis() - startedAt);
        } finally {
            finishOperation(control);
            singleFlight.unlock();
        }
    }

    public QueryResult explain(String sql, String schema, boolean analyze) {
        Objects.requireNonNull(sql, "sql");
        singleFlight.lock();
        SqlExecutionControl control = null;
        long startedAt = System.currentTimeMillis();
        try {
            ensureOpen();
            control = beginOperation();
            ensureOpen();
            SqlExecutionOptions options =
                    new SqlExecutionOptions(0, safety.queryTimeoutSeconds(), control);
            QueryResult result = runner.explain(connection(control), sql, schema, analyze, options);
            updateTransactionState(List.of(new ScriptOutcome(1, sql, result)));
            return result;
        } catch (SQLException failure) {
            QueryResult result = executionFailure(failure, startedAt, control);
            updateTransactionState(List.of(new ScriptOutcome(1, sql, result)));
            return result;
        } finally {
            finishOperation(control);
            singleFlight.unlock();
        }
    }

    public void setTransactionMode(TransactionMode mode) throws SQLException {
        Objects.requireNonNull(mode, "mode");
        singleFlight.lock();
        try {
            ensureOpen();
            if (mode == transactionMode) return;
            if (mode == TransactionMode.AUTO_COMMIT && transactionState != TransactionState.IDLE) {
                throw new IllegalStateException("请先提交或回滚当前事务");
            }
            Connection current = connection.get();
            if (current != null) current.setAutoCommit(mode == TransactionMode.AUTO_COMMIT);
            transactionMode = mode;
        } finally {
            singleFlight.unlock();
        }
    }

    public void commit() throws SQLException {
        singleFlight.lock();
        try {
            ensureOpen();
            requireManual();
            transactionTarget(null).commit();
            transactionState = TransactionState.IDLE;
            transactionConnection = null;
        } finally {
            singleFlight.unlock();
        }
    }

    public void rollback() throws SQLException {
        singleFlight.lock();
        try {
            ensureOpen();
            requireManual();
            transactionTarget(null).rollback();
            transactionState = TransactionState.IDLE;
            transactionConnection = null;
        } finally {
            singleFlight.unlock();
        }
    }

    /** Requests cancellation without waiting for the single-flight operation lock. */
    public CancelOutcome cancel() {
        if (!running.get()) return CancelOutcome.NOTHING_RUNNING;
        cancelling.set(true);
        SqlExecutionControl control = activeControl.get();
        try {
            if (control != null && control.cancel()) return CancelOutcome.CANCELLED;
        } catch (SQLException ignored) {
            // Fall through to the driver-independent cancellation path.
        }
        breakConnection();
        return CancelOutcome.CONNECTION_CLOSED;
    }

    public void reconnect() throws SQLException {
        singleFlight.lock();
        try {
            ensureOpen();
            SQLException rollbackFailure = null;
            Connection current = connection.get();
            if (current != null
                    && transactionMode == TransactionMode.MANUAL
                    && transactionState != TransactionState.IDLE) {
                try {
                    current.rollback();
                } catch (SQLException failure) {
                    rollbackFailure = failure;
                }
            }
            closeCurrentConnection(ConnectionState.DISCONNECTED);
            transactionState = TransactionState.IDLE;
            transactionConnection = null;
            if (rollbackFailure != null) throw rollbackFailure;
            connection(null);
            timeoutSupported = true;
        } finally {
            singleFlight.unlock();
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                connectionId,
                connectionState,
                transactionMode,
                transactionState,
                running.get(),
                cancelling.get(),
                timeoutSupported,
                safety);
    }

    @Override
    public void close() {
        closeRequested.set(true);
        cancel();
        singleFlight.lock();
        try {
            Connection current = connection.getAndSet(null);
            if (current != null
                    && transactionMode == TransactionMode.MANUAL
                    && transactionState != TransactionState.IDLE) {
                try {
                    current.rollback();
                } catch (SQLException ignored) {
                    // Closing the owned connection remains mandatory.
                }
            }
            closeOrRetain(current);
            transactionState = TransactionState.IDLE;
            transactionConnection = null;
            connectionState = ConnectionState.CLOSED;
        } finally {
            singleFlight.unlock();
        }
    }

    private ExecutionBatch executeTransactionCommand(
            TransactionCommand command,
            String script,
            long startedAt,
            SqlExecutionControl control) {
        try {
            if (command == TransactionCommand.COMMIT) {
                transactionTarget(control).commit();
            } else {
                transactionTarget(control).rollback();
            }
            transactionState = TransactionState.IDLE;
            transactionConnection = null;
            return new ExecutionBatch(List.of(), System.currentTimeMillis() - startedAt);
        } catch (SQLException failure) {
            QueryResult result = executionFailure(failure, startedAt, control);
            return new ExecutionBatch(
                    List.of(new ScriptOutcome(1, script, result)),
                    System.currentTimeMillis() - startedAt);
        }
    }

    private SqlExecutionControl beginOperation() {
        SqlExecutionControl control = new SqlExecutionControl();
        beforeOperationPublish.run();
        cancelling.set(false);
        activeControl.set(control);
        running.set(true);
        return control;
    }

    private void finishOperation(SqlExecutionControl control) {
        if (control == null) return;
        timeoutSupported = timeoutSupported && control.timeoutSupported();
        running.set(false);
        activeControl.compareAndSet(control, null);
        cancelling.set(false);
    }

    private Connection connection(SqlExecutionControl control) throws SQLException {
        Connection current = connection.get();
        requireOwnedTransactionConnection(current);
        if (current != null) return current;
        if (cleanupConnection != null) {
            connectionState = ConnectionState.BROKEN;
            throw new SQLException("前一 JDBC 连接尚未完成关闭");
        }
        if (control != null && control.cancellationRequested()) {
            throw new SQLException("SQL execution cancelled");
        }

        Connection opened = null;
        try {
            opened = opener.open();
            opened.setReadOnly(safety.readOnly());
            opened.setAutoCommit(transactionMode == TransactionMode.AUTO_COMMIT);
            if (connectionState == ConnectionState.CLOSED
                    || (control != null && control.cancellationRequested())) {
                throw new SQLException("SQL execution cancelled");
            }
            connection.set(opened);
            connectionState = ConnectionState.CONNECTED;
            if (control != null && control.cancellationRequested()) {
                breakConnection();
                throw new SQLException("SQL execution cancelled");
            }
            return opened;
        } catch (SQLException failure) {
            if (opened != null && connection.compareAndSet(opened, null)) closeOrRetain(opened);
            else if (opened != null && connection.get() != opened) closeOrRetain(opened);
            if (connectionState != ConnectionState.CLOSED) connectionState = ConnectionState.BROKEN;
            throw failure;
        }
    }

    private void updateTransactionState(List<ScriptOutcome> outcomes) {
        if (transactionMode != TransactionMode.MANUAL) return;
        TransactionState previous = transactionState;
        boolean failed = outcomes.stream()
                .map(ScriptOutcome::result)
                .anyMatch(result -> result != null && result.kind == QueryResult.Kind.ERROR);
        transactionState = failed || previous == TransactionState.ERROR_PENDING
                ? TransactionState.ERROR_PENDING : TransactionState.ACTIVE;
        if (previous == TransactionState.IDLE) transactionConnection = connection.get();
    }

    private Connection transactionTarget(SqlExecutionControl control) throws SQLException {
        if (transactionState == TransactionState.IDLE) return connection(control);
        Connection current = connection.get();
        requireOwnedTransactionConnection(current);
        return current;
    }

    private void requireOwnedTransactionConnection(Connection current) {
        if (transactionMode == TransactionMode.MANUAL
                && transactionState != TransactionState.IDLE
                && (connectionState != ConnectionState.CONNECTED
                || current == null
                || current != transactionConnection)) {
            throw new IllegalStateException("事务所属连接已断开，请先重新连接");
        }
    }

    private void breakConnection() {
        Connection current = connection.getAndSet(null);
        if (connectionState != ConnectionState.CLOSED) connectionState = ConnectionState.BROKEN;
        closeOrRetain(current);
    }

    private void closeCurrentConnection(ConnectionState nextState) {
        Connection current = connection.getAndSet(null);
        closeOrRetain(current);
        connectionState = nextState;
    }

    private void requireManual() {
        if (transactionMode != TransactionMode.MANUAL) {
            throw new IllegalStateException("当前会话不是手动事务模式");
        }
    }

    private void ensureOpen() {
        if (closeRequested.get() || connectionState == ConnectionState.CLOSED) {
            throw new IllegalStateException("JDBC 编辑器会话已关闭");
        }
    }

    private static TransactionCommand transactionCommand(String script, boolean oracleMode) {
        String keyword = SqlSafetyAnalyzer.transactionCompletionKeyword(script, oracleMode);
        if (keyword.equals("COMMIT")) return TransactionCommand.COMMIT;
        if (keyword.equals("ROLLBACK")) return TransactionCommand.ROLLBACK;
        return null;
    }

    private static String message(SQLException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static QueryResult executionFailure(
            SQLException failure, long startedAt, SqlExecutionControl control) {
        long elapsedMillis = System.currentTimeMillis() - startedAt;
        return control != null && control.cancellationRequested()
                ? QueryResult.cancelled(message(failure), elapsedMillis)
                : QueryResult.error(message(failure), elapsedMillis);
    }

    private void closeOrRetain(Connection connection) {
        synchronized (cleanupMonitor) {
            if (connection != null && cleanupConnection == null) cleanupConnection = connection;
            Connection cleanup = cleanupConnection;
            if (cleanup == null) return;
            try {
                cleanup.close();
                cleanupConnection = null;
            } catch (SQLException ignored) {
                try {
                    if (cleanup.isClosed()) cleanupConnection = null;
                } catch (SQLException unconfirmed) {
                    // Keep the reference so a later close() can retry.
                }
            }
        }
    }

    private enum TransactionCommand { COMMIT, ROLLBACK }
}
