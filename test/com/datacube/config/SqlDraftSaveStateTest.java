package com.datacube.config;

import org.junit.jupiter.api.Test;
import static com.datacube.config.SqlDraftSaveState.State.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlDraftSaveStateTest {
    private static SqlDraftSaveState fresh() { return new SqlDraftSaveState(null, true, true); }

    @Test void freshEditorHasNoCheckpointAndRestoreDoesNotScheduleOrRewrite() {
        SqlDraftSaveState empty = fresh();
        assertEquals(EMPTY, empty.state());
        assertTrue(empty.savedAt().isEmpty());
        assertTrue(empty.dueAt().isEmpty());
        assertNull(empty.capture(0, true));
        SqlDraftSaveState restored = new SqlDraftSaveState(123L, true, true);
        assertEquals(SAVED, restored.state());
        assertEquals(123, restored.savedAt().orElseThrow());
        assertTrue(restored.dueAt().isEmpty());
        assertNull(restored.capture(0, true));
    }

    @Test void idleDeadlineMovesWithInputAndOnlyPublicationMarksSaved() {
        SqlDraftSaveState state = fresh();
        state.edited(0);
        assertEquals(WAITING, state.state());
        assertEquals(1000, state.dueAt().orElseThrow());
        assertNull(state.capture(899, false));
        state.edited(900);
        assertEquals(1900, state.dueAt().orElseThrow());
        assertNull(state.capture(1899, false));
        SqlDraftSaveState.Ticket ticket = state.capture(1900, false);
        assertNotNull(ticket);
        assertEquals(SAVING, state.state());
        assertTrue(state.dueAt().isEmpty());
        assertTrue(state.savedAt().isEmpty());
        assertNull(state.capture(1900, true));
        assertTrue(state.succeeded(ticket, 9999));
        assertEquals(SAVED, state.state());
        assertEquals(9999, state.savedAt().orElseThrow());
    }

    @Test void continuousInputCannotPostponeCapturePastTenSeconds() {
        SqlDraftSaveState state = fresh();
        state.edited(0);
        for (long now = 900; now <= 9900; now += 900) state.edited(now);
        assertEquals(10000, state.dueAt().orElseThrow());
        assertNull(state.capture(9999, false));
        assertNotNull(state.capture(10000, false));
        state.edited(10001);
        assertEquals(11001, state.dueAt().orElseThrow());
    }

    @Test void inputDuringPublicationStartsNewWindowAndRejectsOldSuccess() {
        SqlDraftSaveState state = fresh();
        state.edited(0);
        SqlDraftSaveState.Ticket old = state.capture(1000, false);
        state.edited(1001);
        assertFalse(state.succeeded(old, 20000));
        assertEquals(WAITING, state.state());
        assertEquals(2001, state.dueAt().orElseThrow());
        assertTrue(state.savedAt().isEmpty());
        SqlDraftSaveState.Ticket latest = state.capture(2001, false);
        assertTrue(state.succeeded(latest, 20001));
        assertEquals(SAVED, state.state());
        assertEquals(20001, state.savedAt().orElseThrow());
    }

    @Test void oldFailureAndRepeatedCompletionCannotOverwriteNewResult() {
        SqlDraftSaveState state = fresh();
        state.edited(0);
        SqlDraftSaveState.Ticket old = state.capture(0, true);
        state.edited(1);
        SqlDraftSaveState.Ticket latest = state.capture(1, true);
        assertFalse(state.failed(old));
        assertEquals(SAVING, state.state());
        assertTrue(state.succeeded(latest, 42));
        assertFalse(state.failed(latest));
        assertFalse(state.succeeded(old, 43));
        assertFalse(state.succeeded(latest, 44));
        assertEquals(42, state.savedAt().orElseThrow());
        assertEquals(SAVED, state.state());
    }

    @Test void ordinaryFailureWaitsForExplicitRetryWithANewAttemptTicket() {
        SqlDraftSaveState state = new SqlDraftSaveState(20L, true, true);
        state.edited(0);
        SqlDraftSaveState.Ticket failed = state.capture(0, true);
        assertTrue(state.failed(failed));
        assertEquals(FAILED, state.state());
        assertEquals(20, state.savedAt().orElseThrow());
        assertTrue(state.dueAt().isEmpty());
        assertNull(state.capture(50000, false));
        state.retry(50001);
        assertEquals(50001, state.dueAt().orElseThrow());
        SqlDraftSaveState.Ticket retried = state.capture(50001, false);
        assertEquals(failed.revision(), retried.revision());
        assertNotEquals(failed.attempt(), retried.attempt());
        assertFalse(state.succeeded(failed, 21));
        assertTrue(state.succeeded(retried, 22));
        assertEquals(22, state.savedAt().orElseThrow());
    }

    @Test void newEditOrCloseForceCanRetryButIdleSuccessCannotBeResaved() {
        SqlDraftSaveState state = fresh();
        state.edited(0);
        assertTrue(state.failed(state.capture(0, true)));
        state.edited(1);
        assertEquals(1001, state.dueAt().orElseThrow());
        assertTrue(state.failed(state.capture(1, true)));
        SqlDraftSaveState.Ticket close = state.capture(2, true);
        assertNotNull(close);
        assertTrue(state.succeeded(close, 30));
        state.retry(3);
        assertTrue(state.dueAt().isEmpty());
        assertNull(state.capture(3, true));
    }

    @Test void clearInvalidatesTicketsAndCloseCannotResurrectUneditedText() {
        SqlDraftSaveState state = new SqlDraftSaveState(9L, true, true);
        state.edited(0);
        SqlDraftSaveState.Ticket old = state.capture(0, true);
        state.edited(1);
        state.clear();
        assertEquals(EMPTY, state.state());
        assertTrue(state.dueAt().isEmpty());
        assertTrue(state.savedAt().isEmpty());
        assertFalse(state.succeeded(old, 10));
        assertNull(state.capture(10000, true));
        state.edited(10001);
        SqlDraftSaveState.Ticket newGeneration = state.capture(10001, true);
        assertNotEquals(old.generation(), newGeneration.generation());
        assertTrue(state.succeeded(newGeneration, 11));
    }

    @Test void disableCancelsPendingAndEditsDoNotImplicitlyResume() {
        SqlDraftSaveState state = fresh();
        state.edited(0);
        SqlDraftSaveState.Ticket old = state.capture(0, true);
        state.pause(false);
        state.edited(1);
        state.retry(2);
        assertEquals(DISABLED, state.state());
        assertTrue(state.dueAt().isEmpty());
        assertNull(state.capture(3, true));
        assertFalse(state.failed(old));
        state.clear();
        assertEquals(DISABLED, state.state());
        state.resume(4, true);
        assertEquals(WAITING, state.state());
        assertEquals(1004, state.dueAt().orElseThrow());
        assertTrue(state.succeeded(state.capture(4, true), 7));
        assertFalse(state.succeeded(old, 8));
    }

    @Test void unavailableRequiresOwnerRecoveryAndCanResumeWithoutSavingEmptyText() {
        SqlDraftSaveState state = new SqlDraftSaveState(12L, true, false);
        assertEquals(UNAVAILABLE, state.state());
        state.edited(0);
        state.retry(1);
        assertNull(state.capture(2, true));
        state.clear();
        assertEquals(UNAVAILABLE, state.state());
        state.resume(3, false);
        assertEquals(EMPTY, state.state());
        assertTrue(state.dueAt().isEmpty());
        state.edited(4);
        SqlDraftSaveState.Ticket old = state.capture(4, true);
        state.edited(5);
        state.pause(true);
        assertEquals(UNAVAILABLE, state.state());
        assertFalse(state.succeeded(old, 13));
        assertTrue(state.dueAt().isEmpty());
    }

    @Test void pausedCheckpointTimestampSurvivesWithoutPretendingAnEditWasSaved() {
        SqlDraftSaveState state = new SqlDraftSaveState(100L, false, true);
        assertEquals(DISABLED, state.state());
        assertEquals(100, state.savedAt().orElseThrow());
        state.resume(0, false);
        assertEquals(SAVED, state.state());
        assertEquals(100, state.savedAt().orElseThrow());
        state.pause(true);
        state.resume(1, true);
        assertEquals(WAITING, state.state());
        assertEquals(100, state.savedAt().orElseThrow());
    }

    @Test void invalidTimesAreRejectedAndDeadlineAdditionCannotWrap() {
        assertThrows(IllegalArgumentException.class, () -> new SqlDraftSaveState(-1L, true, true));
        SqlDraftSaveState state = fresh();
        assertThrows(IllegalArgumentException.class, () -> state.edited(-1));
        state.edited(10);
        assertThrows(IllegalArgumentException.class, () -> state.capture(9, false));
        assertEquals(1010, state.dueAt().orElseThrow());
        state.edited(Long.MAX_VALUE - 1);
        assertEquals(10010, state.dueAt().orElseThrow());
        SqlDraftSaveState.Ticket ticket = state.capture(Long.MAX_VALUE - 1, false);
        assertThrows(IllegalArgumentException.class, () -> state.succeeded(ticket, -1));
        assertTrue(state.succeeded(ticket, 0));
        state.edited(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, state.dueAt().orElseThrow());
        assertNotNull(state.capture(Long.MAX_VALUE, false));
    }
}
