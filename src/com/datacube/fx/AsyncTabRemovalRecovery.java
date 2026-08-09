package com.datacube.fx;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Restores a tab removed outside the guarded close path with the correct interaction state. */
final class AsyncTabRemovalRecovery {

    private AsyncTabRemovalRecovery() {}

    static void restoreOnIncomplete(
            CompletionStage<TabCloseOutcome> close,
            Consumer<Runnable> fxDispatcher,
            Consumer<Boolean> restoreWithDisabledState,
            Consumer<? super Throwable> failureReporter) {
        try {
            close.whenComplete((outcome, failure) -> {
                if (failure == null && outcome == TabCloseOutcome.COMPLETED) return;
                if (failure != null) report(failureReporter, failure);
                else if (outcome == null) {
                    report(failureReporter, new NullPointerException("tab close completed with null outcome"));
                }
                boolean disabled = failure != null
                        || outcome == null
                        || outcome == TabCloseOutcome.TIMED_OUT_STILL_CLOSING
                        || outcome == TabCloseOutcome.FAILED_PARTIAL;
                dispatchRestore(
                        fxDispatcher,
                        () -> restoreWithDisabledState.accept(disabled),
                        failureReporter);
            });
        } catch (Throwable failure) {
            report(failureReporter, failure);
            dispatchRestore(
                    fxDispatcher,
                    () -> restoreWithDisabledState.accept(true),
                    failureReporter);
        }
    }

    private static void dispatchRestore(
            Consumer<Runnable> fxDispatcher,
            Runnable restore,
            Consumer<? super Throwable> failureReporter) {
        try {
            fxDispatcher.accept(() -> {
                try {
                    restore.run();
                } catch (Throwable failure) {
                    report(failureReporter, failure);
                }
            });
        } catch (Throwable failure) {
            report(failureReporter, failure);
        }
    }

    private static void report(Consumer<? super Throwable> reporter, Throwable failure) {
        try {
            reporter.accept(failure);
        } catch (Throwable ignored) {
            // Recovery reporting must never escape onto the FX event loop.
        }
    }
}
