package com.datacube.fx;

import com.datacube.fx.task.SerialSessionOperationQueue;

import java.util.Objects;

/** Pure close-admission decision based on the current operation and fresh transaction state. */
final class SqlEditorClosePolicy {
    enum Action {
        WAIT_FOR_NON_CANCELLABLE,
        CANCEL_RUNNING_SQL,
        RESOLVE_TRANSACTION,
        CLOSE
    }

    private SqlEditorClosePolicy() {
    }

    static Action decide(
            SerialSessionOperationQueue.Snapshot operationSnapshot,
            boolean hasPendingTransaction) {
        Objects.requireNonNull(operationSnapshot, "operationSnapshot");
        if (operationSnapshot.running()) {
            return operationSnapshot.currentCancellable()
                    ? Action.CANCEL_RUNNING_SQL
                    : Action.WAIT_FOR_NON_CANCELLABLE;
        }
        return hasPendingTransaction ? Action.RESOLVE_TRANSACTION : Action.CLOSE;
    }
}
