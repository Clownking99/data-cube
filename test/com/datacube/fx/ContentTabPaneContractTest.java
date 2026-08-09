package com.datacube.fx;

import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.CompletionStage;

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

        assertNotNull(existing);
        assertNotNull(guarded);
        assertTrue(existing.isAnnotationPresent(Deprecated.class));
    }

    @Test
    void asyncCloseGuardReturnsCompletionStage() {
        Method[] methods = Arrays.stream(AsyncTabCloseGuard.class.getDeclaredMethods())
                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                .toArray(Method[]::new);

        assertEquals(1, methods.length);
        assertEquals(CompletionStage.class, methods[0].getReturnType());
        assertEquals(0, methods[0].getParameterCount());
    }

    @Test
    void exposesAsynchronousCloseAllAndShutdownContracts() throws Exception {
        Method closeAll = ContentTabPane.class.getDeclaredMethod("closeAllManagedTabs");
        Method shutdown = AppShell.class.getDeclaredMethod("shutdownAsync");

        assertEquals(CompletionStage.class, closeAll.getReturnType());
        assertEquals(CompletionStage.class, shutdown.getReturnType());
    }
}
