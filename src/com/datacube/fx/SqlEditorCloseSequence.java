package com.datacube.fx;

import java.util.Objects;
import java.util.function.Consumer;

/** Runs the transaction decision as a hard gate before any destructive editor cleanup. */
final class SqlEditorCloseSequence {
    private SqlEditorCloseSequence() {}

    static void run(Runnable transactionGate, Runnable destructiveCleanup) {
        Objects.requireNonNull(transactionGate, "transactionGate").run();
        Objects.requireNonNull(destructiveCleanup, "destructiveCleanup").run();
    }

    static CloseGuardOutcome runMandatory(
            Runnable transactionGate, Runnable destructiveCleanup) {
        return runMandatory(transactionGate, destructiveCleanup, ignored -> {});
    }

    static CloseGuardOutcome runMandatory(
            Runnable transactionGate,
            Runnable destructiveCleanup,
            Consumer<? super Throwable> failureReporter) {
        Objects.requireNonNull(failureReporter, "failureReporter");
        try {
            run(transactionGate, destructiveCleanup);
            return CloseGuardOutcome.APPROVED;
        } catch (Throwable failure) {
            try {
                failureReporter.accept(failure);
            } catch (Throwable ignored) {
                // Reporting cannot upgrade a failed mandatory cleanup to success.
            }
            return CloseGuardOutcome.FAILED_PARTIAL;
        }
    }
}
