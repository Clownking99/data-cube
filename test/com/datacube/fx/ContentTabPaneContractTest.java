package com.datacube.fx;

import javafx.scene.Node;
import javafx.scene.Group;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentTabPaneContractTest {

    @Test
    void exposesExistingAndGuardedManagedTabOverloads() throws Exception {
        Method existing = ContentTabPane.class.getDeclaredMethod(
                "openManagedTab", String.class, Node.class, Runnable.class);
        Method guarded = ContentTabPane.class.getDeclaredMethod(
                "openManagedTab", String.class, Node.class, AsyncTabCloseGuard.class, Runnable.class);
        Method reservedFactory = ContentTabPane.class.getDeclaredMethod(
                "openManagedTab", String.class, Supplier.class);
        Method leasedFactory = ContentTabPane.class.getDeclaredMethod(
                "openManagedTab", String.class, ContentTabPane.ManagedTabFactory.class);

        assertNotNull(existing);
        assertNotNull(guarded);
        assertNotNull(reservedFactory);
        assertNotNull(leasedFactory);
        assertTrue(existing.isAnnotationPresent(Deprecated.class));
    }

    @Test
    void asyncCloseGuardReturnsCompletionStage() {
        Method[] methods = Arrays.stream(AsyncTabCloseGuard.class.getDeclaredMethods())
                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                .toArray(Method[]::new);

        assertEquals(1, methods.length);
        assertEquals(CompletionStage.class, methods[0].getReturnType());
        assertTrue(methods[0].getGenericReturnType().getTypeName().contains("CloseGuardOutcome"));
        assertEquals(0, methods[0].getParameterCount());
    }

    @Test
    void exposesAsynchronousCloseAllAndShutdownContracts() throws Exception {
        Method closeAll = ContentTabPane.class.getDeclaredMethod("closeAllManagedTabs");
        Method shutdown = AppShell.class.getDeclaredMethod("shutdownAsync");

        assertEquals(CompletionStage.class, closeAll.getReturnType());
        assertTrue(closeAll.getGenericReturnType().getTypeName().contains("TabCloseOutcome"));
        assertEquals(CompletionStage.class, shutdown.getReturnType());
        assertTrue(shutdown.getGenericReturnType().getTypeName().contains("ShutdownOutcome"));
    }

    @Test
    void managedFactoryRequiresIndependentMandatoryGuardAndAbortCleanup() throws Exception {
        var components = ContentTabPane.ManagedTabSpec.class.getRecordComponents();

        assertEquals(5, components.length);
        assertEquals("guard", components[1].getName());
        assertEquals(AsyncTabCloseGuard.class, components[1].getType());
        assertEquals("mandatoryGuard", components[2].getName());
        assertEquals(AsyncTabCloseGuard.class, components[2].getType());
        assertEquals("uiFinalizer", components[3].getName());
        assertEquals(Runnable.class, components[3].getType());
        assertEquals("mandatoryAbortCleanup", components[4].getName());
        assertEquals(Runnable.class, components[4].getType());
    }

    @Test
    void fourArgumentManagedSpecMapsBothModesToTheSameGuard() {
        AsyncTabCloseGuard guard = () ->
                CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED);

        ContentTabPane.ManagedTabSpec spec = new ContentTabPane.ManagedTabSpec(
                new Group(), guard, () -> {}, () -> {});

        assertSame(guard, spec.guard());
        assertSame(guard, spec.mandatoryGuard());
    }

    @Test
    void selectionListenerCapturesWhetherPreviousTabWasAlreadyRemoved() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/ContentTabPane.java"));

        assertTrue(source.contains("selectionTracker.changed(before, selected,"
                + " tabPane.getTabs().contains(before))"));
    }
}
