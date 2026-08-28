package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionTestControllerTest {
    static ConnConfig config() {
        return new ConnConfig("test", "test", DbType.POSTGRESQL,
                "example.invalid", 5432, "db", "user", "sentinel-secret", Map.of());
    }

    static final class Pending implements ConnectionTestController.Submitter {
        int calls;
        Callable<String> work;
        Consumer<String> success;
        Consumer<Throwable> failure;

        @Override public void submit(Callable<String> task, Consumer<String> ok,
                                     Consumer<Throwable> failed) {
            calls++;
            work = task;
            success = ok;
            failure = failed;
        }
    }

    @Test void singleFlightSnapshotAndNullSuccess() throws Exception {
        Pending pending = new Pending();
        ConnConfig snapshot = config();
        AtomicInteger calls = new AtomicInteger();
        try (var controller = new ConnectionTestController(pending, () -> {}, cfg -> {
            assertSame(snapshot, cfg);
            calls.incrementAndGet();
            return null;
        })) {
            assertEquals(ConnectionTestController.Phase.IDLE, controller.phase());
            controller.start(snapshot);
            controller.start(snapshot);
            controller.edited();
            controller.start(snapshot);
            assertEquals(1, pending.calls);
            assertEquals(0, calls.get(), "submission must not run IO inline");
            assertEquals(ConnectionTestController.Phase.TESTING, controller.phase());
            pending.success.accept(pending.work.call());
            assertEquals(1, calls.get());
            assertEquals(ConnectionTestController.Phase.SUCCEEDED, controller.phase());
            controller.edited();
            assertEquals(ConnectionTestController.Phase.IDLE, controller.phase());
        }
    }

    @Test void nonNullResultAndExceptionAreSafeFailuresAndCanRetry() {
        Pending pending = new Pending();
        try (var controller = new ConnectionTestController(pending, () -> {}, cfg -> null)) {
            controller.start(config());
            pending.success.accept("password=sentinel-secret jdbc:private");
            assertEquals(ConnectionTestController.Phase.FAILED, controller.phase());
            assertFalse(controller.phase().text().contains("sentinel-secret"));
            assertFalse(controller.phase().text().contains("jdbc:"));
            controller.start(config());
            pending.failure.accept(new IllegalStateException("sentinel-secret"));
            assertEquals(ConnectionTestController.Phase.FAILED, controller.phase());
            assertFalse(controller.phase().text().contains("sentinel-secret"));
            assertEquals(2, pending.calls);
            controller.edited();
            assertEquals(ConnectionTestController.Phase.IDLE, controller.phase());
        }
    }

    @Test void submissionRejectionRecoversAndCanRetryWithoutRawException() {
        AtomicInteger attempts = new AtomicInteger();
        try (var controller = new ConnectionTestController((work, ok, failed) -> {
            if (attempts.incrementAndGet() == 1) throw new RejectedExecutionException("sentinel-secret");
            ok.accept(null);
        }, () -> {}, cfg -> null)) {
            controller.start(config());
            assertEquals(ConnectionTestController.Phase.UNAVAILABLE, controller.phase());
            assertFalse(controller.phase().text().contains("sentinel-secret"));
            controller.edited();
            assertEquals(ConnectionTestController.Phase.IDLE, controller.phase());
            controller.start(config());
            assertEquals(ConnectionTestController.Phase.SUCCEEDED, controller.phase());
            assertEquals(2, attempts.get());
        }
    }

    @Test void closeDropsBothLateCallbacksAndIsIdempotent() {
        Pending pending = new Pending();
        AtomicInteger stops = new AtomicInteger();
        AtomicInteger updates = new AtomicInteger();
        var controller = new ConnectionTestController(pending, stops::incrementAndGet, cfg -> null);
        controller.phaseProperty().addListener((o, before, after) -> updates.incrementAndGet());
        controller.start(config());
        int beforeClose = updates.get();
        controller.close();
        controller.close();
        pending.success.accept(null);
        pending.failure.accept(new IllegalStateException("late"));
        controller.edited();
        controller.start(config());
        assertEquals(beforeClose, updates.get());
        assertEquals(1, stops.get());
        assertEquals(1, pending.calls);
    }
}
