package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LazyValueTest {

    @Test
    void doesNotConstructUntilFirstGetAndConstructsOnlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        LazyValue<Object> lazy = new LazyValue<>(() -> {
            calls.incrementAndGet();
            return new Object();
        });

        assertTrue(lazy.peek().isEmpty());
        assertEquals(0, calls.get());

        Object first = lazy.get();

        assertSame(first, lazy.get());
        assertEquals(1, calls.get());
    }

    @Test
    void cleanupCallbackRunsOnlyAfterInitialization() {
        AtomicInteger cleaned = new AtomicInteger();
        LazyValue<Object> lazy = new LazyValue<>(Object::new);

        lazy.ifInitialized(value -> cleaned.incrementAndGet());
        assertEquals(0, cleaned.get());

        lazy.get();
        lazy.ifInitialized(value -> cleaned.incrementAndGet());

        assertEquals(1, cleaned.get());
    }
}
