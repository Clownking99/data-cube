package com.datacube.migration;

import org.junit.jupiter.api.Test;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationCancellationTest {

    @Test
    void cancelClosesOwnedResourcesOnceAndRejectsLateRegistration() {
        MigrationCancellation cancellation = new MigrationCancellation();
        AtomicInteger firstClosed = new AtomicInteger();
        AtomicInteger lateClosed = new AtomicInteger();

        cancellation.register(firstClosed::incrementAndGet);
        cancellation.cancel();
        cancellation.cancel();

        assertTrue(cancellation.isCancelled());
        assertEquals(1, firstClosed.get());
        assertThrows(CancellationException.class,
                () -> cancellation.register(lateClosed::incrementAndGet));
        assertEquals(1, lateClosed.get());
    }

    @Test
    void releaseClosesResourceWithoutCancellingOperation() {
        MigrationCancellation cancellation = new MigrationCancellation();
        AtomicInteger closed = new AtomicInteger();
        AutoCloseable resource = closed::incrementAndGet;

        cancellation.register(resource);
        cancellation.release(resource);

        assertEquals(1, closed.get());
        assertFalse(cancellation.isCancelled());
    }

    @Test
    void registrationLosingCancelRaceClosesAndRejectsResource() throws Exception {
        BlockingAddSet resources = new BlockingAddSet();
        MigrationCancellation cancellation = new MigrationCancellation(resources);
        AtomicInteger closed = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread registrar = Thread.ofVirtual().start(() -> {
            try {
                cancellation.register(closed::incrementAndGet);
            } catch (Throwable error) {
                failure.set(error);
            }
        });

        assertTrue(resources.added.await(2, TimeUnit.SECONDS));
        cancellation.cancel();
        resources.allowAddToReturn.countDown();
        registrar.join(TimeUnit.SECONDS.toMillis(2));

        assertFalse(registrar.isAlive());
        assertInstanceOf(CancellationException.class, failure.get());
        assertEquals(1, closed.get());
    }

    @Test
    void asynchronousCancelDefersResourceClosureToExecutor() {
        MigrationCancellation cancellation = new MigrationCancellation();
        AtomicInteger closed = new AtomicInteger();
        List<Runnable> cleanup = new ArrayList<>();
        cancellation.register(closed::incrementAndGet);

        CompletableFuture<Void> completion = cancellation.cancelAsync(cleanup::add);

        assertTrue(cancellation.isCancelled());
        assertEquals(0, closed.get());
        assertEquals(1, cleanup.size());
        assertFalse(completion.isDone());
        cleanup.forEach(Runnable::run);
        assertEquals(1, closed.get());
        assertTrue(completion.isDone());
    }

    @Test
    void awaitCleanupBlocksUntilAsynchronousResourceCloseCompletes() throws Exception {
        MigrationCancellation cancellation = new MigrationCancellation();
        List<Runnable> cleanup = new ArrayList<>();
        cancellation.register(() -> { });
        cancellation.cancelAsync(cleanup::add);
        CountDownLatch returned = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                cancellation.awaitCleanup();
                returned.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        assertFalse(returned.await(100, TimeUnit.MILLISECONDS));
        cleanup.forEach(Runnable::run);

        assertTrue(returned.await(2, TimeUnit.SECONDS));
        waiter.join(TimeUnit.SECONDS.toMillis(2));
    }

    private static final class BlockingAddSet extends AbstractSet<AutoCloseable> {
        private final Set<AutoCloseable> delegate = ConcurrentHashMap.newKeySet();
        private final CountDownLatch added = new CountDownLatch(1);
        private final CountDownLatch allowAddToReturn = new CountDownLatch(1);

        @Override
        public boolean add(AutoCloseable resource) {
            boolean result = delegate.add(resource);
            added.countDown();
            try {
                allowAddToReturn.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return result;
        }

        @Override
        public boolean remove(Object resource) {
            return delegate.remove(resource);
        }

        @Override
        public Iterator<AutoCloseable> iterator() {
            return delegate.iterator();
        }

        @Override
        public int size() {
            return delegate.size();
        }
    }
}
