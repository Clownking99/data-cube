package com.datacube.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShutdownQuarantineTest {

    @Test
    void cancelledAttemptRecoversInteractionAndCanBeginAgain() {
        ShutdownQuarantine quarantine = new ShutdownQuarantine();

        assertTrue(quarantine.begin());
        assertTrue(quarantine.isQuarantined());
        assertEquals(ShutdownQuarantine.Action.RECOVER,
                quarantine.settle(ShutdownOutcome.CANCELLED, null));
        assertFalse(quarantine.isQuarantined());
        assertTrue(quarantine.begin());
    }

    @Test
    void fatalPartialKeepsApplicationPermanentlyQuarantined() {
        ShutdownQuarantine quarantine = new ShutdownQuarantine();
        assertTrue(quarantine.begin());

        assertEquals(ShutdownQuarantine.Action.FATAL,
                quarantine.settle(ShutdownOutcome.FAILED_PARTIAL, null));

        assertTrue(quarantine.isQuarantined());
        assertFalse(quarantine.begin());
    }

    @Test
    void completedClosesWhilePreTeardownExceptionRecovers() {
        ShutdownQuarantine completed = new ShutdownQuarantine();
        completed.begin();
        assertEquals(ShutdownQuarantine.Action.CLOSE,
                completed.settle(ShutdownOutcome.COMPLETED, null));
        assertTrue(completed.isQuarantined());

        ShutdownQuarantine failedBeforeTeardown = new ShutdownQuarantine();
        failedBeforeTeardown.begin();
        assertEquals(ShutdownQuarantine.Action.RECOVER,
                failedBeforeTeardown.settle(null, new IllegalStateException("before teardown")));
        assertFalse(failedBeforeTeardown.isQuarantined());
    }
}
