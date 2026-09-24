package com.datacube.fx;

import com.datacube.spi.SqlScriptProgress;
import java.util.function.Consumer;

/** Coalesces immutable completed-prefix snapshots into at most one queued UI callback. */
final class SqlProgressMailbox implements AutoCloseable {
    private final Consumer<Runnable> dispatcher;
    private final Consumer<SqlScriptProgress> render;
    private SqlScriptProgress latest;
    private boolean scheduled;
    private volatile boolean closed;

    SqlProgressMailbox(Consumer<Runnable> dispatcher, Consumer<SqlScriptProgress> render) {
        this.dispatcher = dispatcher; this.render = render;
    }
    synchronized void offer(SqlScriptProgress progress) {
        if (closed) return;
        latest = progress;
        if (scheduled) return;
        scheduled = true;
        try { dispatcher.accept(this::drain); }
        catch (RuntimeException failure) { scheduled = false; latest = null; closed = true; }
    }
    private void drain() {
        SqlScriptProgress next;
        synchronized (this) { next = latest; latest = null; scheduled = false; }
        if (!closed && next != null) render.accept(next);
    }
    @Override public synchronized void close() { closed = true; latest = null; }
}
