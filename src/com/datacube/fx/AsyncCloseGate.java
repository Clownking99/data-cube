package com.datacube.fx;

import java.util.concurrent.atomic.AtomicBoolean;

final class AsyncCloseGate {

    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    boolean beginRequest() {
        if (closed.get()) return false;
        return pending.compareAndSet(false, true);
    }

    void complete(boolean approved, Runnable closeAction) {
        if (!pending.compareAndSet(true, false)) return;
        if (approved && closed.compareAndSet(false, true)) closeAction.run();
    }
}
