package com.datacube.fx;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Restores a tab removed outside the guarded close path when cleanup is not approved. */
final class AsyncTabRemovalRecovery {

    private AsyncTabRemovalRecovery() {}

    static void restoreOnRejection(
            CompletionStage<Boolean> close,
            Consumer<Runnable> fxDispatcher,
            Runnable restore,
            Consumer<? super Throwable> failureReporter) {
        try {
            close.whenComplete((approved, failure) -> {
                if (failure == null && Boolean.TRUE.equals(approved)) return;
                if (failure != null) report(failureReporter, failure);
                dispatchRestore(fxDispatcher, restore, failureReporter);
            });
        } catch (Throwable failure) {
            report(failureReporter, failure);
            dispatchRestore(fxDispatcher, restore, failureReporter);
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
