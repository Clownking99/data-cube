package com.datacube.migration;

import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class PgVerifierTest {
    @Test void targetStatisticsKeepLongEstimatesAndBindSchemaWithoutChangingUrl() throws Exception {
        Fixture f = new Fixture();
        PgVerifier.Statistics stats = f.verify();
        assertEquals(5_000_000_000L, stats.estimatedLiveRows());
        assertEquals(3, stats.tablesAndViews());
        assertEquals(2, stats.sequences());
        assertEquals(4, stats.routines());
        assertEquals(List.of(f.schema, f.schema, f.schema, f.schema), f.parameters);
        assertEquals(4, f.statementsClosed);
        assertEquals(4, f.resultsClosed);
        assertEquals(1, f.connectionsClosed);
        assertTrue(f.readOnly);
        assertTrue(f.sqls.stream().allMatch(sql -> sql.contains("?") && !sql.contains(f.schema)));
        assertEquals(5_000_000_000L, f.logger.summary.get("估算活跃行数（可过期）"));
        assertTrue(f.logger.summary.get("覆盖范围").toString().contains("不能证明"));
        assertFalse(f.logger.messages.toString().contains(f.schema));
    }

    @Test void missingEstimateIsUnknownAndZeroEstimateRemainsZero() throws Exception {
        Fixture missing = new Fixture(); missing.values[1] = null;
        assertNull(missing.verify().estimatedLiveRows());
        assertEquals("未知 / 尚无统计", missing.logger.summary.get("估算活跃行数（可过期）"));
        Fixture zero = new Fixture(); zero.values[1] = 0L;
        assertEquals(0L, zero.verify().estimatedLiveRows());
        assertEquals(0L, zero.logger.summary.get("估算活跃行数（可过期）"));
    }

    @Test void failedQueryClosesResourcesAndPublishesNoPartialSuccess() {
        Fixture f = new Fixture(); f.failQuery = 1;
        assertThrows(SQLException.class, f::verify);
        assertEquals(2, f.statementsClosed);
        assertEquals(1, f.resultsClosed);
        assertEquals(1, f.connectionsClosed);
        assertTrue(f.logger.summary.isEmpty());
        assertFalse(f.logger.messages.toString().contains("synthetic-secret"));
    }

    @Test void cancellationBeforeOrDuringOpenAcquiresNoFurtherResources() {
        Fixture before = new Fixture(); before.cancellation.cancel();
        assertThrows(CancellationException.class, before::verify);
        assertEquals(0, before.openCalls);
        Fixture during = new Fixture(); during.cancelOnOpen = true;
        assertThrows(CancellationException.class, during::verify);
        assertEquals(1, during.connectionsClosed);
        assertTrue(during.sqls.isEmpty());
        assertTrue(during.logger.summary.isEmpty());
    }

    @Test void cancellationAfterReadDiscardsResultsAndClosesAllResources() {
        Fixture f = new Fixture(); f.cancelAfterRead = true;
        assertThrows(CancellationException.class, f::verify);
        assertEquals(1, f.connectionsClosed);
        assertEquals(1, f.statementsClosed);
        assertEquals(1, f.resultsClosed);
        assertTrue(f.logger.summary.isEmpty());
    }

    private static final class Fixture {
        final String schema = "x'; DROP SCHEMA y; --";
        final MigrationCancellation cancellation = new MigrationCancellation();
        final MigrationTestLogger logger = new MigrationTestLogger();
        final List<String> sqls = new ArrayList<>(), parameters = new ArrayList<>();
        final Long[] values = {3L, 5_000_000_000L, 2L, 4L};
        int openCalls, statementsClosed, resultsClosed, connectionsClosed, failQuery = -1;
        boolean readOnly, cancelOnOpen, cancelAfterRead;
        PgVerifier.Statistics verify() throws Exception {
            return new PgVerifier(logger, cancellation, (url, user, password) -> {
                openCalls++;
                assertEquals("jdbc:synthetic:target?existing=1", url);
                Connection connection = proxy(Connection.class, (method, args) -> switch (method) {
                    case "setReadOnly" -> { readOnly = (boolean) args[0]; yield null; }
                    case "close" -> { connectionsClosed++; yield null; }
                    case "prepareStatement" -> { int index = sqls.size(); sqls.add((String) args[0]); yield statement(index); }
                    default -> throw new AssertionError(method);
                });
                if (cancelOnOpen) cancellation.cancel();
                return connection;
            }).verify("jdbc:synthetic:target?existing=1", "synthetic", "synthetic-secret", schema);
        }
        PreparedStatement statement(int index) {
            return proxy(PreparedStatement.class, (method, args) -> switch (method) {
                case "setString" -> { assertEquals(1, args[0]); parameters.add((String) args[1]); yield null; }
                case "setQueryTimeout" -> { assertEquals(30, args[0]); yield null; }
                case "close" -> { statementsClosed++; yield null; }
                case "executeQuery" -> {
                    if (index == failQuery) throw new SQLException("synthetic-secret");
                    yield proxy(ResultSet.class, (m, a) -> switch (m) {
                        case "next" -> true;
                        case "getLong" -> values[index] == null ? 0L : values[index];
                        case "wasNull" -> { if (cancelAfterRead) cancellation.cancel(); yield values[index] == null; }
                        case "close" -> { resultsClosed++; yield null; }
                        default -> throw new AssertionError(m);
                    });
                }
                default -> throw new AssertionError(method);
            });
        }
    }
    @FunctionalInterface private interface Call { Object invoke(String method, Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("equals")) return p == a[0];
            return call.invoke(m.getName(), a);
        }));
    }
}
