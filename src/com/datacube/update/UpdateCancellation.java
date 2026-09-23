package com.datacube.update;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;

/** Linearizes cancellation against the single process handoff. */
final class UpdateCancellation implements AutoCloseable {
    private static final ScheduledExecutorService CLOCK = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "update-deadline"); t.setDaemon(true); return t;
    });
    private final ScheduledFuture<?> deadline;
    private boolean cancelled, handedOff, timedOut;
    private Thread worker;
    private Closeable stream;

    UpdateCancellation(Duration timeout) {
        deadline = CLOCK.schedule(this::expire, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    synchronized void bindWorker() { worker = Thread.currentThread(); check(); }
    synchronized void attach(Closeable value) throws IOException {
        if (cancelled) { value.close(); check(); }
        stream = value;
    }
    synchronized void detach(Closeable value) { if (stream == value) stream = null; }
    synchronized void check() {
        if (cancelled || Thread.currentThread().isInterrupted())
            throw new CancellationException(timedOut ? "更新下载超时，未应用更新" : "更新已取消，未应用更新");
    }
    synchronized boolean cancelled() { return cancelled; }
    synchronized boolean cancel() {
        if (handedOff) return false;
        cancelled = true;
        if (stream != null) try { stream.close(); } catch (IOException ignored) { }
        if (worker != null) worker.interrupt();
        return true;
    }
    synchronized void expire() { if (!handedOff) { timedOut = true; cancel(); } }
    @FunctionalInterface interface Handoff { void run() throws Exception; }
    synchronized void handoff(Handoff action) throws Exception {
        check();
        if (handedOff) throw new IllegalStateException("更新请求已交接");
        action.run();
        handedOff = true;
        deadline.cancel(false);
    }
    @Override public synchronized void close() {
        deadline.cancel(false); worker = null; stream = null;
    }
}
