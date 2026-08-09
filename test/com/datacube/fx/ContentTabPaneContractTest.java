package com.datacube.fx;

import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ContentTabPaneContractTest {

    @Test
    void exposesExistingAndGuardedManagedTabOverloads() throws Exception {
        Method existing = ContentTabPane.class.getDeclaredMethod(
                "openManagedTab", String.class, Node.class, Runnable.class);
        Method guarded = ContentTabPane.class.getDeclaredMethod(
                "openManagedTab", String.class, Node.class, AsyncTabCloseGuard.class, Runnable.class);

        assertNotNull(existing);
        assertNotNull(guarded);
    }

    @Test
    void asyncCloseGuardHasOneConsumerCallbackMethod() {
        Method[] methods = AsyncTabCloseGuard.class.getDeclaredMethods();

        assertEquals(1, methods.length);
        assertEquals(void.class, methods[0].getReturnType());
        assertEquals(1, methods[0].getParameterCount());
        assertEquals(Consumer.class, methods[0].getParameterTypes()[0]);
    }
}
