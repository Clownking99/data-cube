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
    private final AtomicReference<Statement> activeStatement = new AtomicReference<>();
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private volatile boolean timeoutSupported = true;

    /** 发布当前 Statement，并在驱动支持时配置查询超时。 */
    public void activate(Statement statement, int queryTimeoutSeconds) throws SQLException {
        Objects.requireNonNull(statement, "statement");
        if (!activeStatement.compareAndSet(null, statement)) {
            throw new IllegalStateException("A SQL statement is already active");
        }
        try {
            if (queryTimeoutSeconds > 0 && timeoutSupported) {
                try {
                    statement.setQueryTimeout(queryTimeoutSeconds);
                } catch (SQLFeatureNotSupportedException unsupported) {
                    timeoutSupported = false;
                }
            }
        } catch (SQLException | RuntimeException | Error failure) {
            activeStatement.compareAndSet(statement, null);
            throw failure;
        }
    }

    /** 仅当参数仍是当前所有者时释放，避免迟到的 finally 清除后继 Statement。 */
    public void release(Statement statement) {
        if (statement != null) activeStatement.compareAndSet(statement, null);
    }

    /**
     * 幂等地请求取消当前 Statement。
     *
     * @return 请求时是否存在活动 Statement
     */
    public boolean cancel() throws SQLException {
        boolean firstRequest = cancellationRequested.compareAndSet(false, true);
        Statement statement = activeStatement.get();
        if (statement == null) return false;
        if (firstRequest) statement.cancel();
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
}
