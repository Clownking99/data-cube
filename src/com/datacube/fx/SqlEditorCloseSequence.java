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

    /** Completes a retryable attempt even when best-effort FX recovery or feedback fails. */
    static void finishRetryableFailure(
            Throwable primaryFailure,
            Runnable restoreUi,
            Runnable userFeedback,
            Consumer<? super Throwable> terminalCompletion) {
        Objects.requireNonNull(primaryFailure, "primaryFailure");
        Objects.requireNonNull(restoreUi, "restoreUi");
        Objects.requireNonNull(userFeedback, "userFeedback");
        Objects.requireNonNull(terminalCompletion, "terminalCompletion");
        try {
            restoreUi.run();
        } catch (Throwable restoreFailure) {
            addSuppressed(primaryFailure, restoreFailure);
        }
        try {
            userFeedback.run();
        } catch (Throwable feedbackFailure) {
            addSuppressed(primaryFailure, feedbackFailure);
        }
        terminalCompletion.accept(primaryFailure);
    }

    private static void addSuppressed(Throwable primary, Throwable secondary) {
        if (primary == secondary) return;
        try {
            primary.addSuppressed(secondary);
        } catch (Throwable ignored) {
            // Terminal completion remains authoritative even for unusual Throwable implementations.
        }
    }
}
