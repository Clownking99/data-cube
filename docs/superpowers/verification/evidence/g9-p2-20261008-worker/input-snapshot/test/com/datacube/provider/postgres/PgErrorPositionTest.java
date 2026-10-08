package com.datacube.provider.postgres;

import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.SqlResultBudget;
import com.datacube.spi.model.QueryResult;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import static org.junit.jupiter.api.Assertions.*;

class PgErrorPositionTest {
    static PSQLException error(String fields) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C42601\0Msynthetic\0" + fields + "\0"));
    }
    @Test void onlyOriginalStructuredPositionWithUnchangedDriverSqlIsAccepted() throws Exception {
        Connection connection = connection(false, false, false, error("P8\0"), new AtomicInteger());
        assertEquals(8, PgErrorPosition.originalPosition(error("P8\0"), connection, "select broken"));
        assertEquals(0, PgErrorPosition.originalPosition(error("p8\0qinternal sql\0"), connection, "select broken"));
        assertEquals(0, PgErrorPosition.originalPosition(new SQLException("Position: 8"), connection, "select broken"));
        assertEquals(0, PgErrorPosition.originalPosition(error("P999\0"), connection, "select broken"));
        assertEquals(0, PgErrorPosition.originalPosition(error("P8\0"), connection(true, false, false, error("P8\0"), new AtomicInteger()), "select broken"));
    }
    @Test void schemaAndFetchFailuresCannotPointIntoUserSqlAndResourcesClose() {
        var runner = new PgSqlRunner(new PgSqlDialect());
        AtomicInteger closes = new AtomicInteger();
        var executed = runner.execute(connection(false, false, false, error("P8\0"), closes), "select broken", null, SqlExecutionOptions.defaults(5));
        assertEquals(8, executed.errorPosition); assertTrue(executed.executionMillis >= 0); assertEquals(-1, executed.fetchMillis);
        assertEquals(1, closes.get());
        var schema = runner.execute(connection(false, false, false, error("P8\0"), closes), "select broken", "app", SqlExecutionOptions.defaults(5));
        assertEquals(0, schema.errorPosition); assertEquals(-1, schema.executionMillis);
        var fetch = runner.execute(connection(false, true, false, error("P8\0"), closes), "select broken", null, SqlExecutionOptions.defaults(5));
        assertEquals(0, fetch.errorPosition); assertTrue(fetch.executionMillis >= 0); assertTrue(fetch.fetchMillis >= 0);
        var update = runner.execute(connection(false, false, true, null, closes), "update t set n=1", null, SqlExecutionOptions.defaults(5));
        assertEquals(QueryResult.Kind.UPDATE, update.kind); assertTrue(update.executionMillis >= 0); assertEquals(0, update.fetchMillis);
        assertEquals(4, closes.get());
    }
    @Test void boundingAndCopiesKeepTimingAndErrorProvenance() {
        var result = QueryResult.error("x".repeat(20_000), 9).withExecutionDetails(7, 2, 3).withRetentionNotice("notice");
        var bounded = new SqlResultBudget().retain(1, "select broken", result).result();
        assertEquals(7, bounded.errorPosition); assertEquals(2, bounded.executionMillis); assertEquals(3, bounded.fetchMillis);
        var query = QueryResult.query(List.of("x"), List.of(), 5).withExecutionDetails(0, 2, 3).withColumnComments(List.of("comment")).withRetentionNotice("notice");
        assertEquals(2, query.executionMillis); assertEquals(3, query.fetchMillis);
    }
    private static Connection connection(boolean rewritten, boolean fetchFailure, boolean update, SQLException failure, AtomicInteger closes) {
        Statement stmt = (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> switch (method.getName()) {
            case "execute" -> { if (fetchFailure) yield true; if (update) yield false; throw failure; }
            case "getResultSet" -> throw failure;
            case "getUpdateCount" -> 1;
            case "close" -> { closes.incrementAndGet(); yield null; }
            default -> null;
        });
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
            case "createStatement" -> stmt;
            case "nativeSQL" -> rewritten ? "rewritten" : args[0];
            default -> null;
        });
    }
}
