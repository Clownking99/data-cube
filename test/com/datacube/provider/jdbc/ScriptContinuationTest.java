package com.datacube.provider.jdbc;

import com.datacube.provider.oracle.OracleSqlDialect;
import com.datacube.provider.oracle.OracleSqlRunner;
import com.datacube.provider.postgres.PgSqlDialect;
import com.datacube.provider.postgres.PgSqlRunner;
import com.datacube.spi.*;
import com.datacube.spi.model.QueryResult;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class ScriptContinuationTest {
    @ParameterizedTest @CsvSource({"true,bad,1", "false,bad,1", "true,ok;bad,2", "false,ok;bad,2",
            "true,bad; -- trailing comment,1", "false,bad; /* trailing comment */;,1"})
    void terminalFailureNeverAsksToContinue(boolean oracle, String script, int count) {
        var jdbc = new Stub(); var control = new SqlExecutionControl();
        var results = runner(oracle).executeScript(jdbc.connection(), script, null,
                new SqlExecutionOptions(10, 0, control), (index, sql, message) -> { fail("No remaining SQL"); return ScriptErrorPolicy.Decision.ABORT; });
        assertEquals(count, results.size()); assertEquals(count, jdbc.sql.size()); assertEquals(count, jdbc.closed);
        assertEquals(QueryResult.FailureKind.SQL_ERROR, results.getLast().result().failureKind);
        assertEquals("synthetic failure", results.getLast().result().errorMessage);
        assertFalse(control.hasActiveStatement());
    }

    @ParameterizedTest @CsvSource({"true,CONTINUE,4,2", "false,CONTINUE,4,2", "true,CONTINUE_ALL,4,1",
            "false,CONTINUE_ALL,4,1", "true,ABORT,1,1", "false,ABORT,1,1"})
    void intermediateFailureHonorsDecisionAndNeverAsksForLastFailure(boolean oracle, ScriptErrorPolicy.Decision decision, int executed, int prompts) {
        var jdbc = new Stub(); var requested = new ArrayList<Integer>();
        var results = runner(oracle).executeScript(jdbc.connection(), "bad1;bad2;ok;bad4;", null,
                SqlExecutionOptions.defaults(10), (index, sql, message) -> { requested.add(index); assertEquals("bad" + index, sql); return decision; });
        assertEquals(executed, results.size()); assertEquals(executed, jdbc.sql.size()); assertEquals(executed, jdbc.closed);
        assertEquals(prompts == 2 ? List.of(1, 2) : List.of(1), requested);
        assertEquals(QueryResult.FailureKind.SQL_ERROR, results.getLast().result().failureKind);
    }

    @ParameterizedTest @CsvSource({"true", "false"})
    void absentPolicyStillStopsAtFirstFailure(boolean oracle) {
        var jdbc = new Stub();
        var results = runner(oracle).executeScript(jdbc.connection(), "bad;ok", null, SqlExecutionOptions.defaults(10), null);
        assertEquals(1, results.size()); assertEquals(List.of("bad"), jdbc.sql);
    }

    @org.junit.jupiter.api.Test void oracleBlockAndTrailingSlashAreOneStatementForContinuation() {
        var jdbc = new Stub(); jdbc.failPrefix = "BEGIN";
        var results = runner(true).executeScript(jdbc.connection(), "BEGIN NULL; END;\n/\n-- trailing", null,
                SqlExecutionOptions.defaults(10), (index, sql, message) -> { fail("The block has no successor"); return ScriptErrorPolicy.Decision.ABORT; });
        assertEquals(1, results.size()); assertEquals(List.of("BEGIN NULL; END;"), jdbc.sql);
        assertEquals(QueryResult.FailureKind.SQL_ERROR, results.getFirst().result().failureKind);
    }

    @ParameterizedTest @CsvSource({"true", "false"})
    void terminalTimeoutIsReturnedWithoutContinuationPrompt(boolean oracle) {
        var jdbc = new Stub(); jdbc.timeout = true;
        var results = runner(oracle).executeScript(jdbc.connection(), "bad", null, SqlExecutionOptions.defaults(10),
                (index, sql, message) -> { fail("No statement after timeout"); return ScriptErrorPolicy.Decision.ABORT; });
        assertEquals(1, results.size()); assertEquals(QueryResult.FailureKind.TIMEOUT, results.getFirst().result().failureKind); assertEquals(1, jdbc.closed);
    }

    private static SqlRunner runner(boolean oracle) { return oracle ? new OracleSqlRunner(new OracleSqlDialect()) : new PgSqlRunner(new PgSqlDialect()); }
    private static final class Stub {
        final List<String> sql = new ArrayList<>(); int closed; String failPrefix = "bad"; boolean timeout;
        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("createStatement")) return statement();
                throw new AssertionError("Unexpected connection method " + method.getName());
            });
        }
        Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "execute" -> { String value = (String) args[0]; sql.add(value); if (value.startsWith(failPrefix)) { if (timeout) throw new SQLTimeoutException("synthetic timeout"); throw new SQLException("synthetic failure"); } yield false; }
                    case "getUpdateCount" -> 1;
                    case "close" -> { closed++; yield null; }
                    case "setMaxRows", "setQueryTimeout" -> null;
                    default -> throw new AssertionError("Unexpected statement method " + method.getName());
                };
            });
        }
    }
}
