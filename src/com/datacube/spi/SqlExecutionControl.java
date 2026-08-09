package com.datacube.spi;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次 SQL 执行的 JDBC Statement 控制句柄。
 *
 * <p>provider 在执行前发布 Statement，并在所有退出路径释放；调用方可从其他线程安全地请求取消。
 */
public final class SqlExecutionControl {
    private static final String CANCELLED_MESSAGE = "SQL execution cancelled";

    private final AtomicReference<Activation> activeStatement = new AtomicReference<>();
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private volatile boolean timeoutSupported = true;

    /** 发布当前 Statement，并在驱动支持时配置查询超时。 */
    public Activation activate(Statement statement, int queryTimeoutSeconds) throws SQLException {
        Objects.requireNonNull(statement, "statement");
        Activation activation = new Activation(statement);
        if (!activeStatement.compareAndSet(null, activation)) {
            throw new IllegalStateException("A SQL statement is already active");
        }
        try {
            ensureNotCancelled(activation);
            if (queryTimeoutSeconds > 0 && timeoutSupported) {
                try {
                    statement.setQueryTimeout(queryTimeoutSeconds);
                } catch (SQLFeatureNotSupportedException unsupported) {
                    timeoutSupported = false;
                }
            }
            ensureNotCancelled(activation);
            return activation;
        } catch (SQLException | RuntimeException | Error failure) {
            activeStatement.compareAndSet(activation, null);
            throw failure;
        }
    }

    /** 在真正调用 JDBC execute 前再次关闭 activate/cancel 之间的竞态窗口。 */
    public void ensureNotCancelled(Activation activation) throws SQLException {
        Objects.requireNonNull(activation, "activation");
        if (!cancellationRequested.get()) return;
        if (activeStatement.get() == activation) activation.deliverCancel();
        throw new SQLException(CANCELLED_MESSAGE);
    }

    /** 仅当参数仍是当前所有者时释放，避免迟到的 finally 清除后继 Statement。 */
    public void release(Statement statement) {
        if (statement == null) return;
        Activation activation = activeStatement.get();
        if (activation != null && activation.statement == statement) {
            activeStatement.compareAndSet(activation, null);
        }
    }

    /** 按 activate 返回的 owner token 精确释放；provider 应优先使用此重载。 */
    public void release(Activation activation) {
        if (activation != null) activeStatement.compareAndSet(activation, null);
    }

    /**
     * 幂等地请求取消当前 Statement。
     *
     * @return 请求时是否存在活动 Statement
     */
    public boolean cancel() throws SQLException {
        cancellationRequested.set(true);
        Activation activation = activeStatement.get();
        if (activation == null) return false;
        activation.deliverCancel();
        return true;
    }

    public boolean hasActiveStatement() {
        return activeStatement.get() != null;
    }

    public boolean cancellationRequested() {
        return cancellationRequested.get();
    }

    public boolean timeoutSupported() {
        return timeoutSupported;
    }

    /** 单个活动 Statement 的所有权与独立取消投递状态。 */
    public static final class Activation {
        private final Statement statement;
        private final AtomicBoolean cancelDelivered = new AtomicBoolean();

        private Activation(Statement statement) {
            this.statement = statement;
        }

        private void deliverCancel() throws SQLException {
            if (!cancelDelivered.compareAndSet(false, true)) return;
            try {
                statement.cancel();
            } catch (SQLException failure) {
                cancelDelivered.compareAndSet(true, false);
                throw failure;
            }
        }
    }
}
