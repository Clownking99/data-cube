package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxSerialTaskQueue;
import com.datacube.service.SqlCompletionMetadata.*;
import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.model.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;
import org.junit.jupiter.api.Test;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.field;
import static org.junit.jupiter.api.Assertions.*;

class SqlCompletionLookupTest {
    private Target target(String host, String table) { return new Target(new ConnConfig("id", "synthetic", DbType.POSTGRESQL, host, 1, "db", "u", "", Map.of()), "s", table); }
    @Test void supersededLookupStaysSingleFlightUntilActuallyFinishedAndLateNamesAreDiscarded() throws Exception {
        try (var runner = new FxTaskRunner(); var queue = new FxSerialTaskQueue(runner)) {
            var started = new CountDownLatch(1); var release = new CountDownLatch(1);
            var control = new AtomicReference<SqlExecutionControl>(); var calls = new AtomicInteger(); var refreshes = new AtomicInteger();
            var lookup = FxUiTestSupport.call(() -> new SqlCompletionLookup(runner, queue, (target, token) -> {
                calls.incrementAndGet(); control.set(token); started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return new Names(List.of(target.table()), "");
            }, refreshes::incrementAndGet, ignored -> {}));
            try {
                FxUiTestSupport.call(() -> lookup.request(target("first.invalid", "old")));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    lookup.request(target("second.invalid", "new"));
                    assertTrue(control.get().cancellationRequested());
                    lookup.request(target("second.invalid", "new")); return null;
                });
                assertEquals(1, calls.get(), "cancellation is not proof that the old JDBC call has stopped");
                release.countDown(); drain(queue);
                assertEquals(0, refreshes.get());
                FxUiTestSupport.call(() -> lookup.request(target("second.invalid", "new"))); drain(queue);
                assertEquals(List.of("new"), FxUiTestSupport.call(() -> lookup.request(target("second.invalid", "new"))));
                assertEquals(2, calls.get()); assertEquals(1, refreshes.get());
            } finally { release.countDown(); FxUiTestSupport.call(() -> { lookup.close(); return null; }); }
        }
    }
    @Test void deadlineAndCloseCancelWithoutLateRefreshAndExplicitRetryClearsFailure() throws Exception {
        try (var runner = new FxTaskRunner(); var queue = new FxSerialTaskQueue(runner)) {
            var started = new CountDownLatch(1); var release = new CountDownLatch(1);
            var control = new AtomicReference<SqlExecutionControl>(); var refreshes = new AtomicInteger(); var notice = new AtomicReference<String>();
            var lookup = FxUiTestSupport.call(() -> new SqlCompletionLookup(runner, queue, (target, token) -> {
                control.set(token); started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
                return new Names(List.of("late"), "");
            }, refreshes::incrementAndGet, notice::set));
            try {
                FxUiTestSupport.call(() -> lookup.request(target("mock.invalid", "table")));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    var timer = (PauseTransition) field(lookup, "timeout"); timer.stop(); timer.getOnFinished().handle(new ActionEvent());
                    assertTrue(control.get().cancellationRequested()); assertTrue(notice.get().contains("超时"));
                    return null;
                });
                release.countDown(); drain(queue);
                assertEquals(0, refreshes.get());
                FxUiTestSupport.call(() -> { lookup.retryFailures(); lookup.request(target("mock.invalid", "table")); lookup.close(); return null; });
                drain(queue); assertEquals(0, refreshes.get());
                assertEquals(List.of(), FxUiTestSupport.call(() -> lookup.request(target("mock.invalid", "table"))));
            } finally { release.countDown(); FxUiTestSupport.call(() -> { lookup.close(); return null; }); }
        }
    }
    private static void drain(FxSerialTaskQueue queue) throws Exception {
        queue.submit(() -> null, ignored -> {}, error -> fail(error)).get(5, TimeUnit.SECONDS);
        FxUiTestSupport.call(() -> null);
    }
    @Test void backgroundResourceClosePublishesCancellationBeforeFxCleanupCanRun() throws Exception {
        try (var runner = new FxTaskRunner(); var queue = new FxSerialTaskQueue(runner)) {
            var started = new CountDownLatch(1); var release = new CountDownLatch(1);
            var fxBlocked = new CountDownLatch(1); var releaseFx = new CountDownLatch(1);
            var control = new AtomicReference<SqlExecutionControl>(); var refreshed = new AtomicInteger();
            var lookup = FxUiTestSupport.call(() -> new SqlCompletionLookup(runner, queue, (target, token) -> {
                control.set(token); started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
                return new Names(List.of("late"), "");
            }, refreshed::incrementAndGet, ignored -> {}));
            try {
                FxUiTestSupport.call(() -> lookup.request(target("mock.invalid", "table")));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                javafx.application.Platform.runLater(() -> {
                    fxBlocked.countDown();
                    try { assertTrue(releaseFx.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                });
                assertTrue(fxBlocked.await(5, TimeUnit.SECONDS));
                lookup.close();
                assertTrue(control.get().cancellationRequested(), "resource cancellation cannot wait for the FX queue");
                release.countDown(); releaseFx.countDown(); drain(queue);
                assertEquals(0, refreshed.get());
            } finally { release.countDown(); releaseFx.countDown(); lookup.close(); }
        }
    }
}
