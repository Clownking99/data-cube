package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectEditorPaneLifecycleTest {

    @Test
    void isAutoCloseableAndRequiresSharedTaskRunner() throws Exception {
        assertTrue(AutoCloseable.class.isAssignableFrom(ObjectEditorPane.class));
        assertNotNull(ObjectEditorPane.class.getConstructor(
                String.class, Callable.class, Function.class, FxTaskRunner.class));
    }
}
