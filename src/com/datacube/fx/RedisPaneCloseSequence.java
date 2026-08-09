package com.datacube.fx;

/** Best-effort queue/session close shared by Redis managed panes. */
final class RedisPaneCloseSequence {
    private RedisPaneCloseSequence() {}

    static void close(Runnable queueClose, Runnable sessionClose) {
        BestEffortCloseSequence.run(queueClose, sessionClose);
    }
}
