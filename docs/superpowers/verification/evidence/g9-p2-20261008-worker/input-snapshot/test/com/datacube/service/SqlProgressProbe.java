package com.datacube.service;

import com.datacube.config.DraftTestCipher;
import com.datacube.provider.postgres.*;
import com.datacube.spi.*;
import com.datacube.spi.model.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Real PG runner/session above synthetic JDBC only; no network or persisted connections. */
public final class SqlProgressProbe implements AutoCloseable {
    public final CountDownLatch secondStarted = new CountDownLatch(1), releaseSecond = new CountDownLatch(1);
    public final AtomicInteger executed = new AtomicInteger();
    public final ConnConfig config = new ConnConfig("progress", "synthetic progress", DbType.POSTGRESQL,
            "synthetic.invalid", 1, "synthetic", "synthetic", "", Map.of("environment", "TEST"));
    public final ConnectionManager manager;
    public SqlProgressProbe() {
        var dialect = new PgSqlDialect();
        var factory = proxy(ConnectionFactory.class, (p, m, a) -> {
            if (m.getName().equals("open")) return connection();
            if (m.getName().equals("ensureDriverLoaded")) return null;
            throw new AssertionError("No connection probes");
        });
        var provider = proxy(DatabaseProvider.class, (p, m, a) -> switch (m.getName()) {
            case "type" -> DbType.POSTGRESQL;
            case "connectionFactory" -> factory;
            case "dialect" -> dialect;
            case "sqlRunner" -> new PgSqlRunner(dialect);
            case "resultFilterSqlRenderer", "schemaDiffCapability" -> Optional.empty();
            case "metadataReader" -> proxy(MetadataReader.class, (mp, mm, ma) -> List.of());
            default -> throw new AssertionError(m.getName());
        });
        manager = new ConnectionManager(DraftTestCipher.create(), ignored -> provider);
        manager.register(config);
    }
    private Connection connection() {
        return proxy(Connection.class, (p, m, a) -> switch (m.getName()) {
            case "createStatement" -> statement();
            case "getAutoCommit", "isValid" -> true;
            default -> zero(m.getReturnType());
        });
    }
    private Statement statement() {
        var number = new AtomicInteger(); var cancelled = new AtomicBoolean();
        return proxy(Statement.class, (p, m, a) -> switch (m.getName()) {
            case "execute" -> {
                if (((String) a[0]).startsWith("SET ")) yield false;
                int n = executed.incrementAndGet(); number.set(n);
                if (n == 2) {
                    secondStarted.countDown();
                    if (!releaseSecond.await(10, TimeUnit.SECONDS)) throw new AssertionError("test did not release second statement");
                }
                if (cancelled.get()) throw new SQLException("synthetic cancelled");
                yield true;
            }
            case "cancel" -> { cancelled.set(true); releaseSecond.countDown(); yield null; }
            case "getResultSet" -> result(number.get());
            default -> zero(m.getReturnType());
        });
    }
    private ResultSet result(int value) {
        var position = new AtomicInteger();
        var metadata = proxy(ResultSetMetaData.class, (p, m, a) -> switch (m.getName()) {
            case "getColumnCount" -> 1;
            case "getColumnType" -> Types.INTEGER;
            case "getColumnTypeName" -> "integer";
            case "getColumnLabel", "getColumnName" -> "n";
            case "getSchemaName", "getTableName" -> "";
            default -> zero(m.getReturnType());
        });
        return proxy(ResultSet.class, (p, m, a) -> switch (m.getName()) {
            case "next" -> position.getAndIncrement() == 0;
            case "getMetaData" -> metadata;
            case "getObject" -> value;
            default -> zero(m.getReturnType());
        });
    }
    private static Object zero(Class<?> type) {
        if (type == boolean.class) return false; if (type == int.class) return 0; if (type == long.class) return 0L; return null;
    }
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    @Override public void close() { releaseSecond.countDown(); manager.closeAll(); }
}
