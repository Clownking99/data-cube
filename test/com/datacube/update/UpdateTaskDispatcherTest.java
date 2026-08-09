package com.datacube.update;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateTaskDispatcherTest {

    @Test
    void closePreventsQueuedOperationFromStarting() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        AtomicBoolean ran = new AtomicBoolean();
        Object dispatcher = newDispatcher(command -> queued.add(command), ignored -> {});

        invoke(dispatcher, "execute", () -> ran.set(true));

        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertTrue(ran.get());

        ran.set(false);
        dispatcher = newDispatcher(command -> queued.add(command), ignored -> {});
        invoke(dispatcher, "execute", () -> ran.set(true));
        ((AutoCloseable) dispatcher).close();
        queued.removeFirst().run();
        assertFalse(ran.get());
    }

    @Test
    void closeSuppressesQueuedCallback() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        AtomicBoolean ran = new AtomicBoolean();
        Object dispatcher = newDispatcher(Runnable::run, command -> queued.add(command));

        invoke(dispatcher, "dispatch", () -> ran.set(true));

        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertTrue(ran.get());

        ran.set(false);
        dispatcher = newDispatcher(Runnable::run, command -> queued.add(command));
        invoke(dispatcher, "dispatch", () -> ran.set(true));
        ((AutoCloseable) dispatcher).close();
        queued.removeFirst().run();
        assertFalse(ran.get());
    }

    private static Object newDispatcher(Executor background, Consumer<Runnable> callbacks)
            throws Exception {
        Class<?> type = Class.forName("com.datacube.update.UpdateTaskDispatcher");
        var constructor = type.getDeclaredConstructor(Executor.class, Consumer.class);
        constructor.setAccessible(true);
        return constructor.newInstance(background, callbacks);
    }

    private static void invoke(Object target, String method, Runnable action) throws Exception {
        var declared = target.getClass().getDeclaredMethod(method, Runnable.class);
        declared.setAccessible(true);
        declared.invoke(target, action);
    }
}
