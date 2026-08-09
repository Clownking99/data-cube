package com.datacube.fx;

import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void managedFactoryRequiresIndependentMandatoryAbortCleanup() throws Exception {
        var components = ContentTabPane.ManagedTabSpec.class.getRecordComponents();

        assertEquals(4, components.length);
        assertEquals("mandatoryAbortCleanup", components[3].getName());
        assertEquals(Runnable.class, components[3].getType());
    }
}
