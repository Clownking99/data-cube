package com.datacube.fx.task;

import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxTaskScopeTest {

    @Test
    void dispatchesSuccessThroughConfiguredUiDispatcher() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner()) {
            BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
            FxTaskScope scope = runner.scope(ui::add);
            AtomicReference<String> result = new AtomicReference<>();

            scope.submit(() -> "done", result::set, error -> { throw new AssertionError(error); }).get();

            assertEquals(1, ui.size());
            ui.take().run();
            assertEquals("done", result.get());
            scope.close();
        }
    }

    @Test
    void dropsQueuedUiCallbackWhenClosedBeforeDispatch() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner()) {
            BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
            FxTaskScope scope = runner.scope(ui::add);
            AtomicBoolean callback = new AtomicBoolean();

            scope.submit(() -> "done", ignored -> callback.set(true),
                    error -> callback.set(true)).get();
            scope.close();
            ui.take().run();

            assertFalse(callback.get());
            assertTrue(scope.isClosed());
        }
    }

    @Test
    void closeInterruptsRunningTaskWithoutFailureCallback() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner()) {
            FxTaskScope scope = runner.scope(Runnable::run);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            AtomicBoolean failureCallback = new AtomicBoolean();

            scope.submit(() -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    throw expected;
                }
                return null;
            }, ignored -> { }, error -> failureCallback.set(true));

            assertTrue(started.await(2, TimeUnit.SECONDS));
            scope.close();

            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertFalse(failureCallback.get());
        }
    }

    @Test
    void directDispatchDropsQueuedAndPostCloseCallbacks() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner()) {
            BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
            FxTaskScope scope = runner.scope(ui::add);
            AtomicBoolean callback = new AtomicBoolean();

            scope.dispatch(() -> callback.set(true));
            scope.close();
            ui.take().run();
            scope.dispatch(() -> callback.set(true));

            assertFalse(callback.get());
            assertTrue(ui.isEmpty());
        }
    }

    @Test
    void surfacesFatalErrorsWithoutRecoverableCallbackOrFutureGet() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner()) {
            CountDownLatch surfaced = new CountDownLatch(1);
            AtomicReference<Error> fatal = new AtomicReference<>();
            FxTaskScope scope = runner.scope(Runnable::run, error -> {
                fatal.set(error);
                surfaced.countDown();
            });
            AtomicBoolean failureCallback = new AtomicBoolean();

            scope.submit(() -> {
                throw new AssertionError("fatal");
            }, ignored -> { }, error -> failureCallback.set(true));

            assertTrue(surfaced.await(2, TimeUnit.SECONDS));
            assertTrue(fatal.get() instanceof AssertionError);
            assertFalse(failureCallback.get());
            scope.close();
        }
    }
}
