package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlEditorCloseSequenceTest {

    @Test
    void failedTransactionGateDoesNotRunDestructiveCleanup() {
        List<String> events = new ArrayList<>();
        RuntimeException gateFailure = new RuntimeException("transaction failed");

        RuntimeException actual = assertThrows(RuntimeException.class, () ->
                SqlEditorCloseSequence.run(
                        () -> {
                            events.add("transaction");
                            throw gateFailure;
                        },
                        () -> events.add("destructive")));

        assertSame(gateFailure, actual);
        assertEquals(List.of("transaction"), events);
    }

    @Test
    void successfulTransactionGateRunsDestructiveCleanupInOrder() {
        List<String> events = new ArrayList<>();

        SqlEditorCloseSequence.run(
                () -> events.add("transaction"),
                () -> events.add("destructive"));

        assertEquals(List.of("transaction", "destructive"), events);
    }

    @Test
    void mandatoryTransactionFailureIsFatalAndSkipsStrictCleanup() {
        List<String> events = new ArrayList<>();

        CloseGuardOutcome outcome = SqlEditorCloseSequence.runMandatory(
                () -> {
                    events.add("rollback");
                    throw new IllegalStateException("rollback failed");
                },
                () -> events.add("strict-cleanup"));

        assertEquals(CloseGuardOutcome.FAILED_PARTIAL, outcome);
        assertEquals(List.of("rollback"), events);
    }

    @Test
    void mandatorySuccessApprovesOnlyAfterDestructiveCleanup() {
        List<String> events = new ArrayList<>();

        CloseGuardOutcome outcome = SqlEditorCloseSequence.runMandatory(
                () -> events.add("rollback"),
                () -> events.add("strict-cleanup"));

        assertEquals(CloseGuardOutcome.APPROVED, outcome);
        assertEquals(List.of("rollback", "strict-cleanup"), events);
    }

    @Test
    void retryableFailureSettlesEvenWhenUiRestoreAndUserFeedbackBothThrow() {
        RuntimeException transactionFailure = new RuntimeException("commit failed");
        RuntimeException restoreFailure = new RuntimeException("restore failed");
        RuntimeException feedbackFailure = new RuntimeException("feedback failed");
        CompletableFuture<Void> terminal = new CompletableFuture<>();

        SqlEditorCloseSequence.finishRetryableFailure(
                transactionFailure,
                () -> { throw restoreFailure; },
                () -> { throw feedbackFailure; },
                terminal::completeExceptionally);

        CompletionException actual = assertThrows(CompletionException.class, terminal::join);
        assertSame(transactionFailure, actual.getCause());
        assertEquals(List.of(restoreFailure, feedbackFailure),
                List.of(transactionFailure.getSuppressed()));
    }

    @Test
    void retryableFailureAttemptsUserFeedbackAfterUiRestoreFailure() {
        RuntimeException transactionFailure = new RuntimeException("rollback failed");
        AtomicInteger feedback = new AtomicInteger();
        CompletableFuture<Void> terminal = new CompletableFuture<>();

        SqlEditorCloseSequence.finishRetryableFailure(
                transactionFailure,
                () -> { throw new IllegalStateException("refresh failed"); },
                feedback::incrementAndGet,
                terminal::completeExceptionally);

        assertEquals(1, feedback.get());
        assertTrue(terminal.isCompletedExceptionally());
    }
}
