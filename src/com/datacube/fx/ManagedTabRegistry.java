package com.datacube.fx;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe exactly-once lifecycle registry used by closable content tabs. */
final class ManagedTabRegistry<K> {

    private final Map<K, Once> entries = new IdentityHashMap<>();
    private boolean closed;

    Runnable register(K key, Runnable disposer) {
        Objects.requireNonNull(key, "key");
        Once once = new Once(Objects.requireNonNull(disposer, "disposer"));
        Once previous;
        boolean disposeImmediately;
        synchronized (this) {
            disposeImmediately = closed;
            previous = disposeImmediately ? null : entries.put(key, once);
        }

        if (previous != null) previous.run();
        if (disposeImmediately) once.run();
        return () -> dispose(key, once);
    }

    void disposeAll() {
        List<Once> remaining;
        synchronized (this) {
            if (closed) return;
            closed = true;
            remaining = new ArrayList<>(entries.values());
            entries.clear();
        }

        Throwable first = null;
        for (Once entry : remaining) {
            try {
                entry.run();
            } catch (Throwable error) {
                if (first == null) first = error;
                else first.addSuppressed(error);
            }
        }
        rethrow(first);
    }

    private void dispose(K key, Once expected) {
        synchronized (this) {
            if (entries.get(key) == expected) entries.remove(key);
        }
        expected.run();
    }

    private static void rethrow(Throwable error) {
        if (error == null) return;
        if (error instanceof RuntimeException runtime) throw runtime;
        if (error instanceof Error fatal) throw fatal;
        throw new IllegalStateException(error);
    }

    private static final class Once implements Runnable {
        private final Runnable action;
        private final AtomicBoolean invoked = new AtomicBoolean();

        private Once(Runnable action) {
            this.action = action;
        }

        @Override
        public void run() {
            if (invoked.compareAndSet(false, true)) action.run();
        }
    }
}
