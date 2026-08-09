package com.datacube.fx;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Token-aware exactly-once gate for asynchronous close attempts. */
final class AsyncCloseGate {

    private final AtomicLong generations = new AtomicLong();
    private final AtomicReference<State> state = new AtomicReference<>(Idle.INSTANCE);

    Request beginRequest() {
        while (true) {
            State current = state.get();
            if (current != Idle.INSTANCE) return null;
            Request request = new Request(generations.incrementAndGet());
            if (state.compareAndSet(current, new Pending(request))) return request;
        }
    }

    boolean complete(
            Request request,
            boolean approved,
            Runnable closeAction,
            Consumer<? super Throwable> failureReporter) {
        while (true) {
            State current = state.get();
            if (!(current instanceof Pending pending) || pending.request != request) return false;
            State next = approved ? Closed.INSTANCE : Idle.INSTANCE;
            if (!state.compareAndSet(current, next)) continue;
            if (approved) {
                try {
                    closeAction.run();
                } catch (Throwable failure) {
                    report(failureReporter, failure);
                }
            }
            return true;
        }
    }

    private static void report(Consumer<? super Throwable> reporter, Throwable failure) {
        try {
            reporter.accept(failure);
        } catch (Throwable ignored) {
            // Reporting must not turn an already-terminal close into an uncaught failure.
        }
    }

    static final class Request {
        private final long generation;

        private Request(long generation) {
            this.generation = generation;
        }

        long generation() {
            return generation;
        }

        @Override
        public String toString() {
            return "close-request-" + generation;
        }
    }

    private sealed interface State permits Idle, Pending, Closed {}

    private enum Idle implements State { INSTANCE }

    private record Pending(Request request) implements State {}

    private enum Closed implements State { INSTANCE }
}
