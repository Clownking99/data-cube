package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConstructionOwnerTest {

    @Test
    void requiresExplicitRollbackCauseAndCannotBeUsedWithTryWithResources() {
        assertFalse(AutoCloseable.class.isAssignableFrom(ConstructionOwner.class));
        assertThrows(NoSuchMethodException.class,
                () -> ConstructionOwner.class.getDeclaredMethod("close"));
    }

    @Test
    void failureClosesAllOwnedResourcesInReverseOrderAndReportsAggregate() {
        List<String> closed = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        ConstructionOwner owner = new ConstructionOwner(failures::add);
        owner.own(() -> closed.add("scope"));
        owner.own(() -> {
            closed.add("queue");
            throw new IllegalStateException("queue close");
        });
        owner.own(() -> closed.add("socket"));

        IllegalStateException build = new IllegalStateException("build");
        ConstructionOwner.Rollback rollback = owner.close(build);

        assertEquals(List.of("socket", "queue", "scope"), closed);
        assertEquals(1, failures.size());
        assertEquals(ConstructionOwner.RollbackOutcome.FAILED_PARTIAL, rollback.outcome());
        assertInstanceOf(PartialCloseException.class, rollback.failure());
    }

    @Test
    void commitTransfersOwnershipWithoutClosingResources() {
        List<String> closed = new ArrayList<>();
        ConstructionOwner owner = new ConstructionOwner(ignored -> {});
        owner.own(() -> closed.add("scope"));

        owner.commit();
        owner.close(new IllegalStateException("unused"));

        assertEquals(List.of(), closed);
    }

    @Test
    void sqlEditorStyleBuildFailureClosesQueueThenScope() {
        List<String> closed = new ArrayList<>();

        ConstructionOwner construction = new ConstructionOwner(ignored -> {});
        construction.own(() -> closed.add("scope"));
        construction.own(() -> closed.add("metadata-queue"));
        IllegalStateException build = new IllegalStateException("build failed");

        ConstructionOwner.Rollback rollback = construction.close(build);

        assertEquals(List.of("metadata-queue", "scope"), closed);
        assertEquals(ConstructionOwner.RollbackOutcome.SAFE, rollback.outcome());
        SafeConstructionFailure safe = assertInstanceOf(
                SafeConstructionFailure.class, rollback.failure());
        assertSame(build, safe.getCause());
    }

    @Test
    void blockingRollbackIsDeferredUntilMandatoryAbortRuns() {
        List<String> calls = new ArrayList<>();
        ConstructionOwner owner = new ConstructionOwner(ignored -> {});
        owner.own(() -> calls.add("scope"));
        owner.ownBlocking(() -> calls.add("socket"));

        ConstructionOwner.Rollback rollback = owner.close(new IllegalStateException("build"));

        assertEquals(List.of("scope"), calls);
        SafeConstructionFailure safe = assertInstanceOf(
                SafeConstructionFailure.class, rollback.failure());
        assertTrue(safe.requiresMandatoryAbort());
        safe.mandatoryAbortCleanup().run();
        assertEquals(List.of("scope", "socket"), calls);
    }
}
