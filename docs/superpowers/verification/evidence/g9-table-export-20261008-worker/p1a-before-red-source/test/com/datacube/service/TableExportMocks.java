package com.datacube.service;

import com.datacube.config.CredentialCipher;
import com.datacube.spi.DataAccessor;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.model.*;
import com.datacube.provider.postgres.PgSqlDialect;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Entirely synthetic export source; never creates a JDBC driver connection. */
public final class TableExportMocks {
    public enum Failure { NONE, CONNECT, FIRST, MIDDLE }
    public final AtomicInteger opens = new AtomicInteger(), pages = new AtomicInteger();
    public volatile Failure failure = Failure.NONE;
    public volatile Runnable beforePage = () -> {};
    public volatile Object value = "complete value";
    public final ConnectionManager manager;

    public TableExportMocks() {
        Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isClosed" -> false;
                    case "isValid", "getAutoCommit" -> true;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var factory = new com.datacube.spi.ConnectionFactory() {
            public void ensureDriverLoaded() {}
            public Connection open(ConnConfig config) throws SQLException {
                opens.incrementAndGet();
                if (failure == Failure.CONNECT) throw new SQLException("synthetic sensitive connect");
                return connection;
            }
            public String test(ConnConfig config) { throw new AssertionError("No connection test"); }
        };
        DataAccessor data = new DataAccessor() {
            public PagedResult page(TableRef t, long offset, int limit, List<SortKey> sorts, String filter)
                    throws SQLException {
                beforePage.run();
                pages.incrementAndGet();
                if (failure == Failure.FIRST || failure == Failure.MIDDLE && offset > 0)
                    throw new SQLException("synthetic sensitive row");
                return new PagedResult(List.of("value"), List.of(List.of(value)),
                        failure == Failure.MIDDLE && limit > 1);
            }
            public long count(TableRef t, String filter) { throw new AssertionError("No count"); }
        };
        DatabaseProvider provider = (DatabaseProvider) Proxy.newProxyInstance(
                DatabaseProvider.class.getClassLoader(), new Class<?>[]{DatabaseProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "type" -> DbType.POSTGRESQL;
                    case "connectionFactory" -> factory;
                    case "dialect" -> new PgSqlDialect();
                    case "dataAccessor" -> data;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        manager = new ConnectionManager(new CredentialCipher(), ignored -> provider);
        manager.register(new ConnConfig("synthetic", "synthetic", DbType.POSTGRESQL,
                "synthetic.invalid", 1, "synthetic", "synthetic", "", Map.of("readOnly", "true")));
    }
}
