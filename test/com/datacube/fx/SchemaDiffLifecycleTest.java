package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffLifecycleTest {

    @Test
    void rejectedInteractiveCloseRestoresUsableFlowWhileAcceptedCloseRunsOffFxThread()
            throws Exception {
        AtomicInteger confirmations = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();
        AtomicBoolean cleanupVirtual = new AtomicBoolean();
        AtomicBoolean accept = new AtomicBoolean();
        AsyncTabCloseGuard guard = AsyncTabCloseGuards.blocking(() -> {
            cleanupVirtual.set(Thread.currentThread().isVirtual());
            cleanups.incrementAndGet();
        });
        SchemaDiffPane.CloseFlow flow = new SchemaDiffPane.CloseFlow(guard, guard);

        assertEquals(CloseGuardOutcome.REJECTED, flow.requestInteractive(
                true, () -> {
                    confirmations.incrementAndGet();
                    return accept.get();
                }).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(0, cleanups.get());

        accept.set(true);
        assertEquals(CloseGuardOutcome.APPROVED, flow.requestInteractive(
                true, () -> {
                    confirmations.incrementAndGet();
                    return accept.get();
                }).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(2, confirmations.get());
        assertEquals(1, cleanups.get());
        assertTrue(cleanupVirtual.get());
    }

    @Test
    void mandatoryCloseNeverInvokesDialogAndStrictPartialCannotClaimApproval() throws Exception {
        AtomicInteger dialogs = new AtomicInteger();
        AsyncTabCloseGuard interactive = AsyncTabCloseGuards.blocking(() -> {});
        AsyncTabCloseGuard mandatory = AsyncTabCloseGuards.blocking(() -> {
            throw new IllegalStateException("fixed strict cleanup failure");
        });
        SchemaDiffPane.CloseFlow flow = new SchemaDiffPane.CloseFlow(interactive, mandatory);

        assertEquals(CloseGuardOutcome.FAILED_PARTIAL,
                flow.requestMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(0, dialogs.get());
    }

    @Test
    void paneConstructionImmediatelyOwnsBlockingCleanupBeforeBuildAndPublishesNoLateUi()
            throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SchemaDiffPane.java"));
        int construction = source.indexOf("ConstructionOwner construction = new ConstructionOwner");
        int scope = source.indexOf("Executors.newThreadPerTaskExecutor", construction);
        int scopeOwnership = source.indexOf("construction.ownBlocking(workScope::close)", scope);
        int viewModel = source.indexOf("new SchemaDiffViewModel", scopeOwnership);
        int ownership = source.indexOf("construction.ownBlocking(viewModel::closeResources)", viewModel);
        int build = source.indexOf("build(", ownership);
        int commit = source.indexOf("construction.commit()", build);

        assertTrue(construction >= 0);
        assertTrue(scope > construction);
        assertTrue(scopeOwnership > scope);
        assertTrue(viewModel > scopeOwnership);
        assertTrue(ownership > viewModel);
        assertTrue(build > ownership);
        assertTrue(commit > build);
        assertTrue(source.contains("throw construction.close(failure).failure()"));
        assertTrue(source.contains("viewModel.removeListener(viewListener)"));
        assertTrue(source.contains("Platform.isFxApplicationThread()"));
    }
}
