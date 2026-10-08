package com.datacube.provider.jdbc;

import com.datacube.provider.oracle.OracleSqlDialect;
import com.datacube.provider.oracle.OracleSqlRunner;
import com.datacube.provider.postgres.PgSqlDialect;
import com.datacube.provider.postgres.PgSqlRunner;
import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.model.QueryResult;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class DriverCancellationClassificationTest {
    static Stream<Arguments> paths() {
        return Stream.of("oracle-sql", "oracle-prepared", "oracle-explain", "oracle-schema-sql",
                "oracle-schema-prepared", "oracle-schema-explain", "postgres-sql",
                "postgres-schema-prepared", "jdbc-prepared")
            .flatMap(path -> Stream.of(Arguments.of(path, false), Arguments.of(path, true)));
    }

    @ParameterizedTest(name="{0}: cancellationRequested={1}")
    @MethodSource("paths")
    void explicitCancellationTakesPrecedenceOverDriversTimeoutException(String path, boolean cancel) {
        SqlExecutionControl control = new SqlExecutionControl();
        AtomicInteger executions = new AtomicInteger(), closes = new AtomicInteger(), cancels = new AtomicInteger();
        String sentinel = "synthetic-driver-detail-5c12";
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p,m,a) -> {
            switch(m.getName()) {
                case "execute", "executeQuery" -> {
                    executions.incrementAndGet();
                    if(cancel) assertTrue(control.cancel(), "the executing statement must be published");
                    throw new SQLTimeoutException(sentinel, "72000", 1013);
                }
                case "close" -> { closes.incrementAndGet(); return null; }
                case "cancel" -> { cancels.incrementAndGet(); return null; }
                default -> { return null; }
            }
        });
        Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (p,m,a) -> switch(m.getName()) {
                    case "createStatement", "prepareStatement" -> statement;
                    default -> null;
                });
        SqlExecutionOptions options = new SqlExecutionOptions(10,2,control);
        String schema = path.contains("schema") ? "DEMO" : null;
        var oracle = new OracleSqlRunner(new OracleSqlDialect());
        var postgres = new PgSqlRunner(new PgSqlDialect());
        QueryResult result = switch(path) {
            case "oracle-sql", "oracle-schema-sql" -> oracle.execute(connection,"SELECT 1 FROM DUAL",schema,options);
            case "oracle-prepared", "oracle-schema-prepared" -> oracle.executePrepared(connection,"SELECT 1 FROM DUAL",List.of(),schema,options);
            case "oracle-explain", "oracle-schema-explain" -> oracle.explain(connection,"SELECT 1 FROM DUAL",schema,false,options);
            case "postgres-sql" -> postgres.execute(connection,"SELECT 1",schema,options);
            case "postgres-schema-prepared" -> postgres.executePrepared(connection,"SELECT 1",List.of(),schema,options);
            case "jdbc-prepared" -> JdbcPreparedQueryExecutor.execute(connection,"SELECT 1",List.of(),options);
            default -> throw new AssertionError(path);
        };
        assertEquals(QueryResult.Kind.ERROR,result.kind);
        assertEquals(cancel ? QueryResult.FailureKind.CANCELLED : QueryResult.FailureKind.TIMEOUT,result.failureKind);
        assertEquals(1,executions.get());
        assertEquals(1,closes.get());
        assertEquals(cancel ? 1 : 0,cancels.get());
        assertFalse(control.hasActiveStatement());
        if(path.contains("prepared")) assertFalse(result.errorMessage.contains(sentinel),"prepared diagnostics remain sanitized");
    }
}
