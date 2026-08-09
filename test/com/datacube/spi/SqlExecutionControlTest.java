package com.datacube.spi;

import com.datacube.spi.model.QueryResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SqlExecutionControlTest {
    @Test
    void appliesTimeoutCancelsActiveStatementAndReleasesIt() throws Exception {
        AtomicInteger timeout = new AtomicInteger(-1);
        AtomicBoolean cancelled = new AtomicBoolean();
        Statement statement = statement((proxy, method, args) -> {
            if (method.getName().equals("setQueryTimeout")) {
                timeout.set((Integer) args[0]);
                return null;
            }
            if (method.getName().equals("cancel")) {
                cancelled.set(true);
                return null;
            }
            return defaultValue(method.getReturnType());
        });
        SqlExecutionControl control = new SqlExecutionControl();

        control.activate(statement, 25);
        assertEquals(25, timeout.get());
        assertTrue(control.cancel());
        control.release(statement);

        assertTrue(cancelled.get());
        assertFalse(control.hasActiveStatement());
        assertTrue(control.cancellationRequested());
    }

    @Test
    void refusesASecondOwnerAndOnlyTheOwnerCanRelease() throws Exception {
        Statement first = statement((proxy, method, args) -> defaultValue(method.getReturnType()));
        Statement second = statement((proxy, method, args) -> defaultValue(method.getReturnType()));
        SqlExecutionControl control = new SqlExecutionControl();

        control.activate(first, 0);
        assertThrows(IllegalStateException.class, () -> control.activate(second, 0));
        control.release(second);
        assertTrue(control.hasActiveStatement());

        control.release(first);
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void remembersUnsupportedTimeoutAndSkipsLaterTimeoutConfiguration() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Statement unsupported = statement((proxy, method, args) -> {
            if (method.getName().equals("setQueryTimeout")) {
                attempts.incrementAndGet();
                throw new SQLFeatureNotSupportedException("unsupported");
            }
            return defaultValue(method.getReturnType());
        });
        Statement later = statement((proxy, method, args) -> {
            if (method.getName().equals("setQueryTimeout")) attempts.incrementAndGet();
            return defaultValue(method.getReturnType());
        });
        SqlExecutionControl control = new SqlExecutionControl();

        control.activate(unsupported, 8);
        control.release(unsupported);
        control.activate(later, 8);
        control.release(later);

        assertEquals(1, attempts.get());
        assertFalse(control.timeoutSupported());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void clearsOwnershipWhenTimeoutConfigurationFails() {
        Statement statement = statement((proxy, method, args) -> {
            if (method.getName().equals("setQueryTimeout")) throw new SQLException("timeout setup failed");
            return defaultValue(method.getReturnType());
        });
        SqlExecutionControl control = new SqlExecutionControl();

        SQLException error = assertThrows(SQLException.class, () -> control.activate(statement, 4));

        assertEquals("timeout setup failed", error.getMessage());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void concurrentCancellationInvokesJdbcCancelOnlyOnce() throws Exception {
        AtomicInteger cancelCalls = new AtomicInteger();
        Statement statement = statement((proxy, method, args) -> {
            if (method.getName().equals("cancel")) cancelCalls.incrementAndGet();
            return defaultValue(method.getReturnType());
        });
        SqlExecutionControl control = new SqlExecutionControl();
        control.activate(statement, 0);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<Thread> threads = new java.util.ArrayList<>();

        for (int i = 0; i < 16; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    assertTrue(control.cancel());
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }));
        }
        for (Thread thread : threads) thread.join();
        control.release(statement);

        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(1, cancelCalls.get());
        assertTrue(control.cancellationRequested());
    }

    @Test
    void cancelBeforePublishIsDeliveredToTheNextOwnerAndStopsActivation() throws Exception {
        AtomicInteger cancelCalls = new AtomicInteger();
        Statement statement = statement((proxy, method, args) -> {
            if (method.getName().equals("cancel")) cancelCalls.incrementAndGet();
            return defaultValue(method.getReturnType());
        });
        SqlExecutionControl control = new SqlExecutionControl();

        assertFalse(control.cancel());
        assertThrows(SQLException.class, () -> control.activate(statement, 0));

        assertEquals(1, cancelCalls.get());
        assertTrue(control.cancellationRequested());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void cancelBetweenOwnersIsDeliveredToTheNextOwner() throws Exception {
        Statement first = statement((proxy, method, args) -> defaultValue(method.getReturnType()));
        AtomicInteger secondCancelCalls = new AtomicInteger();
        Statement second = statement((proxy, method, args) -> {
            if (method.getName().equals("cancel")) secondCancelCalls.incrementAndGet();
            return defaultValue(method.getReturnType());
        });
        SqlExecutionControl control = new SqlExecutionControl();

        control.activate(first, 0);
        control.release(first);
        assertFalse(control.cancel());
        assertThrows(SQLException.class, () -> control.activate(second, 0));

        assertEquals(1, secondCancelCalls.get());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void failedJdbcCancelCanBeRetriedForTheSameOwner() throws Exception {
        AtomicInteger cancelCalls = new AtomicInteger();
        Statement statement = statement((proxy, method, args) -> {
            if (method.getName().equals("cancel") && cancelCalls.incrementAndGet() == 1) {
                throw new SQLException("temporary cancel failure");
            }
            return defaultValue(method.getReturnType());
        });
        SqlExecutionControl control = new SqlExecutionControl();
        control.activate(statement, 0);

        assertThrows(SQLException.class, control::cancel);
        assertTrue(control.cancel());
        control.release(statement);

        assertEquals(2, cancelCalls.get());
        assertTrue(control.cancellationRequested());
    }

    @Test
    void eachNewOwnerHasIndependentCancelDeliveryState() throws Exception {
        AtomicInteger firstCancelCalls = new AtomicInteger();
        AtomicInteger secondCancelCalls = new AtomicInteger();
        Statement first = statement((proxy, method, args) -> {
            if (method.getName().equals("cancel")) firstCancelCalls.incrementAndGet();
            return defaultValue(method.getReturnType());
        });
        Statement second = statement((proxy, method, args) -> {
            if (method.getName().equals("cancel")) secondCancelCalls.incrementAndGet();
            return defaultValue(method.getReturnType());
        });
        SqlExecutionControl control = new SqlExecutionControl();

        control.activate(first, 0);
        assertTrue(control.cancel());
        control.release(first);
        assertThrows(SQLException.class, () -> control.activate(second, 0));

        assertEquals(1, firstCancelCalls.get());
        assertEquals(1, secondCancelCalls.get());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void lateReleaseFromPriorOwnerCannotClearTheCurrentOwner() throws Exception {
        Statement first = statement((proxy, method, args) -> defaultValue(method.getReturnType()));
        Statement second = statement((proxy, method, args) -> defaultValue(method.getReturnType()));
        SqlExecutionControl control = new SqlExecutionControl();

        SqlExecutionControl.Activation firstOwner = control.activate(first, 0);
        control.release(firstOwner);
        SqlExecutionControl.Activation secondOwner = control.activate(second, 0);
        control.release(firstOwner);

        assertTrue(control.hasActiveStatement());
        control.release(secondOwner);
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void optionsNormalizeBoundsAndQueryResultKeepsTypedFailuresCompatible() {
        SqlExecutionControl control = new SqlExecutionControl();
        SqlExecutionOptions options = new SqlExecutionOptions(-5, -9, control);

        assertEquals(0, options.maxRows());
        assertEquals(0, options.queryTimeoutSeconds());
        assertSame(control, options.control());
        assertThrows(NullPointerException.class, () -> new SqlExecutionOptions(1, 1, null));

        QueryResult sqlError = QueryResult.error("sql", 1);
        QueryResult cancelled = QueryResult.cancelled("cancel", 2);
        QueryResult timeout = QueryResult.timeout("timeout", 3);
        QueryResult query = QueryResult.query(List.of("id"), List.of(List.of(1)), 4)
                .withColumnComments(List.of("identifier"));

        assertEquals(QueryResult.Kind.ERROR, sqlError.kind);
        assertEquals(QueryResult.FailureKind.SQL_ERROR, sqlError.failureKind);
        assertEquals(QueryResult.FailureKind.CANCELLED, cancelled.failureKind);
        assertEquals(QueryResult.FailureKind.TIMEOUT, timeout.failureKind);
        assertNull(query.failureKind);
        assertEquals(List.of("identifier"), query.columnComments);
    }

    private static Statement statement(InvocationHandler handler) {
        return (Statement) Proxy.newProxyInstance(
                SqlExecutionControlTest.class.getClassLoader(), new Class<?>[]{Statement.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
