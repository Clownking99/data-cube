package com.datacube.fx;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real default/no-workspace close path; the worker terminal gap is controlled, not stress-tested. */
class ContentTabPaneCloseAttemptTest {
    @Test void repeatedCloseDuringWorkerTerminalGapDoesNotSealRotatedTracker() throws Exception {
        ContentTabPane tabs = FxUiTestSupport.call(ContentTabPane::new);
        AtomicBoolean approve = new AtomicBoolean();
        AtomicInteger factories = new AtomicInteger(), finalizers = new AtomicInteger();
        CountDownLatch rotated = new CountDownLatch(1), releaseTerminal = new CountDownLatch(1);
        CompletableFuture<CloseGuardOutcome> abort = new CompletableFuture<>();
        Thread worker = null;
        try {
            FxUiTestSupport.call(() -> {
                assertNotNull(tabs.openManagedTab("original", () -> spec(approve, factories, finalizers)));
                MandatoryAbortTracker tracker = read(tabs, "mandatoryAborts");
                var lease = tracker.acquireLease();
                assertTrue(lease.acquired());
                lease.abort(() -> abort);
                return null;
            });
            CompletionStage<TabCloseOutcome> first = FxUiTestSupport.call(tabs::closeAllManagedTabsMandatory);
            assertFalse(first.toCompletableFuture().isDone());
            Object oldTracker = read(tabs, "mandatoryAborts");
            AsyncManagedTabRegistry<Tab> registry = read(tabs, "guardedTabs");
            BiConsumer<TabCloseOutcome, Runnable> originalTerminal = read(registry, "terminal");
            write(registry, "terminal", (BiConsumer<TabCloseOutcome, Runnable>) (outcome, commit) -> {
                assertFalse(Platform.isFxApplicationThread(), "default terminal must be driven by worker abort settlement");
                originalTerminal.accept(outcome, commit);
                assertNotSame(oldTracker, read(tabs, "mandatoryAborts"), "rotation already happened");
                rotated.countDown();
                await(releaseTerminal);
            });
            worker = Thread.startVirtualThread(() -> abort.complete(CloseGuardOutcome.APPROVED));
            assertTrue(rotated.await(5, TimeUnit.SECONDS), "worker reached post-rotation terminal gap");
            CompletionStage<TabCloseOutcome> repeated;
            try {
                repeated = FxUiTestSupport.call(tabs::closeAllManagedTabsMandatory);
                var cancelledCopy = FxUiTestSupport.call(tabs::closeAllManagedTabsMandatory).toCompletableFuture();
                assertTrue(cancelledCopy.cancel(false), "public cancellation must not cancel the shared internal attempt");
                assertFalse(first.toCompletableFuture().isDone());
                assertFalse(repeated.toCompletableFuture().isDone());
            } finally {
                releaseTerminal.countDown();
            }
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertEquals(TabCloseOutcome.CANCELLED, first.toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(TabCloseOutcome.CANCELLED, repeated.toCompletableFuture().get(5, TimeUnit.SECONDS));

            FxUiTestSupport.call(() -> {
                Tab next = tabs.openManagedTab("new factory after cancellation", () -> spec(approve, factories, finalizers));
                assertNotNull(next, "a repeated old attempt must not hard-seal the freshly rotated tracker");
                assertEquals(2, factories.get());
                assertEquals(2, ((TabPane) tabs.getNode()).getTabs().size());
                return null;
            });
            approve.set(true);
            assertEquals(TabCloseOutcome.COMPLETED,
                    tabs.closeAllManagedTabsMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(2, finalizers.get());
            assertTrue(FxUiTestSupport.call(() -> ((TabPane) tabs.getNode()).getTabs().isEmpty()));
        } finally {
            releaseTerminal.countDown();
            abort.complete(CloseGuardOutcome.APPROVED);
            if (worker != null) worker.join(5000);
            approve.set(true);
            // Only synthetic Labels were allocated. Settle owned guards even when the RED assertion fails.
            tabs.closeAllManagedTabsMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void synchronousEmptyAttemptAndCallbackFailureAlwaysSettleReturnedCopies() throws Exception {
        ContentTabPane empty = FxUiTestSupport.call(ContentTabPane::new);
        assertEquals(TabCloseOutcome.COMPLETED,
                FxUiTestSupport.call(empty::closeAllManagedTabsMandatory).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(TabCloseOutcome.COMPLETED,
                empty.closeAllManagedTabsMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS));

        ContentTabPane failed = FxUiTestSupport.call(ContentTabPane::new);
        FxUiTestSupport.call(() -> {
            failed.workspaceLifecycle(() -> { throw new IllegalStateException("synthetic callback failure"); },
                    CompletableFuture::completedFuture);
            return null;
        });
        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                failed.closeAllManagedTabsMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                failed.closeAllManagedTabsMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    private static ContentTabPane.ManagedTabSpec spec(AtomicBoolean approve, AtomicInteger factories,
            AtomicInteger finalizers) {
        factories.incrementAndGet();
        return new ContentTabPane.ManagedTabSpec(new Label("synthetic"),
                () -> CompletableFuture.completedFuture(approve.get()
                        ? CloseGuardOutcome.APPROVED : CloseGuardOutcome.REJECTED),
                finalizers::incrementAndGet, () -> {});
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "terminal gap release"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    @SuppressWarnings("unchecked")
    private static <T> T read(Object target, String name) {
        try { return (T) field(target, name).get(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void write(Object target, String name, Object value) {
        try { field(target, name).set(target, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Field field(Object target, String name) throws NoSuchFieldException {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
}
