package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
