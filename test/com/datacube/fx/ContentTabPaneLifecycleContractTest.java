package com.datacube.fx;

import javafx.scene.Group;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ContentTabPaneLifecycleContractTest {

    @Test
    void legacyManagedSpecUsesTheInteractiveGuardForMandatoryClose() {
        AtomicInteger calls = new AtomicInteger();
        AsyncTabCloseGuard guard = () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED);
        };

        ContentTabPane.ManagedTabSpec spec = new ContentTabPane.ManagedTabSpec(
                new Group(), guard, () -> {}, () -> {});

        assertSame(guard, spec.guard());
        assertSame(guard, spec.mandatoryGuard());
        assertEquals(CloseGuardOutcome.APPROVED,
                spec.mandatoryGuard().requestClose().toCompletableFuture().join());
        assertEquals(1, calls.get());
    }

    @Test
    void contentPaneExposesDistinctInteractiveAndMandatoryCloseAllEntrypoints() throws Exception {
        Method interactive = ContentTabPane.class.getMethod("closeAllManagedTabs");
        Method mandatory = ContentTabPane.class.getMethod("closeAllManagedTabsMandatory");

        assertEquals(CompletionStage.class, interactive.getReturnType());
        assertEquals(CompletionStage.class, mandatory.getReturnType());
    }
}
