package com.datacube.fx;

import com.datacube.spi.ScriptErrorPolicy.Decision;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Independent failure paths; retained dispatches never construct a dialog or replace its decision logic. */
class ScriptErrorQuestionGateTest {
    @Test void sealedQuestionWaitsForPhysicalCancellationAndCannotShowLater() throws Exception {
        var dispatch = new LinkedBlockingQueue<Runnable>();
        var owners = new AtomicInteger();
        var gate = new ScriptErrorQuestionGate(() -> false, () -> {owners.incrementAndGet(); return null;}, dispatch::add);
        try (var call = new Call(gate)) {
            Runnable show = dispatch.poll(3, TimeUnit.SECONDS);
            assertNotNull(show, "real question must have been queued");
            gate.seal();
            show.run(); // A sealed question must return before accessing JavaFX or its owner.
            assertFalse(call.result.isDone(), "dismissing a question cannot outrun physical cancellation");
            assertEquals(0, owners.get());
            gate.finish();
            assertEquals(Decision.ABORT, call.await());
            show.run();
            assertEquals(0, owners.get(), "late dispatch must not revive a finished question");
        }
    }

    static Stream<Throwable> dispatchFailures() {
        return Stream.of(new RejectedExecutionException("synthetic rejected dispatch"),
                new AssertionError("synthetic dispatch Error"));
    }
    @ParameterizedTest @MethodSource("dispatchFailures")
    void failedFxDispatchAbortsWithoutLeavingAWorkerWait(Throwable failure) throws Exception {
        var gate = new ScriptErrorQuestionGate(() -> false, () -> {fail("no owner after rejected dispatch"); return null;},
                action -> {if (failure instanceof Error error) throw error; throw (RuntimeException) failure;});
        try (var call = new Call(gate)) {assertEquals(Decision.ABORT, call.await());}
    }

    @Test void rejectedDismissDoesNotReleaseTheWorkerBeforePhysicalCancellation() throws Exception {
        var show = new CompletableFuture<Runnable>();
        var dispatched = new AtomicBoolean();
        var gate = new ScriptErrorQuestionGate(() -> false, () -> {fail("no dialog after finish"); return null;}, action -> {
            if (dispatched.compareAndSet(false, true)) show.complete(action);
            else throw new RejectedExecutionException("synthetic rejected dismissal");
        });
        try (var call = new Call(gate)) {
            Runnable queued = show.get(3, TimeUnit.SECONDS);
            gate.seal();
            assertFalse(call.result.isDone());
            gate.finish();
            assertEquals(Decision.ABORT, call.await());
            queued.run();
        }
    }

    @Test void interruptedQuestionRestoresInterruptAndRejectsItsLateShow() throws Exception {
        var dispatch = new LinkedBlockingQueue<Runnable>();
        var owners = new AtomicInteger();
        var gate = new ScriptErrorQuestionGate(() -> false, () -> {owners.incrementAndGet(); return null;}, dispatch::add);
        try (var call = new Call(gate)) {
            Runnable show = dispatch.poll(3, TimeUnit.SECONDS);
            assertNotNull(show);
            call.worker.interrupt();
            assertEquals(Decision.ABORT, call.await());
            assertTrue(call.interrupted.get(), "the worker must retain its interrupt signal");
            show.run();
            assertEquals(0, owners.get());
        }
    }

    @Test void closedOwnerAbortsBeforeSchedulingAnyQuestion() throws Exception {
        var gate = new ScriptErrorQuestionGate(() -> true, () -> {fail("closed owner"); return null;},
                action -> fail("closed owner must not enqueue a question"));
        try (var call = new Call(gate)) {assertEquals(Decision.ABORT, call.await());}
    }

    private static final class Call implements AutoCloseable {
        final ScriptErrorQuestionGate gate;
        final CompletableFuture<Decision> result = new CompletableFuture<>();
        final AtomicBoolean interrupted = new AtomicBoolean();
        final Thread worker;
        Call(ScriptErrorQuestionGate gate) {
            this.gate = gate;
            worker = Thread.startVirtualThread(() -> {
                try {result.complete(gate.onError(1, "synthetic", "synthetic failure"));}
                catch (Throwable failure) {result.completeExceptionally(failure);}
                finally {interrupted.set(Thread.currentThread().isInterrupted());}
            });
        }
        Decision await() throws Exception {
            Decision answer = result.get(3, TimeUnit.SECONDS);
            assertTrue(worker.join(Duration.ofSeconds(3)), "policy call must return");
            return answer;
        }
        @Override public void close() throws Exception {
            gate.finish();
            if (worker.isAlive()) worker.interrupt();
            assertTrue(worker.join(Duration.ofSeconds(3)), "bounded fixture cleanup");
        }
    }
}
