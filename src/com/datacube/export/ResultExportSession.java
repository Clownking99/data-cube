package com.datacube.export;

public final class ResultExportSession implements AutoCloseable {
    private boolean closed;
    private ResultExportOperation current;

    public synchronized ResultExportOperation begin() {
        if (closed || current != null) return null;
        current = new ResultExportOperation();
        return current;
    }

    public synchronized void finish(ResultExportOperation operation) {
        if (current == operation) current = null;
    }

    public synchronized boolean isBusy() { return current != null; }
    public synchronized boolean isClosed() { return closed; }

    @Override public synchronized void close() {
        closed = true;
        if (current != null) current.cancel();
    }
}
