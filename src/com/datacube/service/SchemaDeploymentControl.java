package com.datacube.service;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Caller-owned cancellation control shared by schema comparison and deployment. */
public final class SchemaDeploymentControl {
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private final Set<CancellationTarget> targets = ConcurrentHashMap.newKeySet();
    private final Object terminalLock = new Object();
    private final String confirmationToken;

    public SchemaDeploymentControl() {
        this(null);
    }

    public SchemaDeploymentControl(String confirmationToken) {
        this.confirmationToken = confirmationToken;
    }

    /** Idempotently requests cancellation. */
    public boolean cancel() {
        boolean firstRequest;
        synchronized (terminalLock) {
            firstRequest = cancellationRequested.compareAndSet(false, true);
        }
        targets.forEach(SchemaDeploymentControl::cancelQuietly);
        return firstRequest;
    }

    public boolean cancellationRequested() {
        return cancellationRequested.get();
    }

    String confirmationToken() {
        return confirmationToken;
    }

    Registration register(CancellationTarget target) {
        targets.add(target);
        if (cancellationRequested()) cancelQuietly(target);
        return () -> targets.remove(target);
    }

    <T> boolean settle(
            CompletableFuture<T> settlement, T value, T cancellationValue) {
        synchronized (terminalLock) {
            return settlement.complete(cancellationRequested() ? cancellationValue : value);
        }
    }

    <T> boolean settleExceptionally(
            CompletableFuture<T> settlement, Throwable failure, T cancellationValue) {
        synchronized (terminalLock) {
            return cancellationRequested()
                    ? settlement.complete(cancellationValue)
                    : settlement.completeExceptionally(failure);
        }
    }

    private static void cancelQuietly(CancellationTarget target) {
        try {
            target.cancel();
        } catch (Exception ignored) {
            // Each operation still runs its own strict cleanup path.
        }
    }

    @FunctionalInterface
    interface CancellationTarget {
        void cancel() throws Exception;
    }

    @FunctionalInterface
    interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    @Override
    public String toString() {
        return "SchemaDeploymentControl[cancellationRequested=" + cancellationRequested() + "]";
    }
}
