package com.datacube.fx;

import com.datacube.fx.task.SerialSessionOperationQueue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlEditorClosePolicyTest {

    @Test
    void currentCommitWaitsWithoutPromisingRollbackThenUsesFreshTransactionState() {
        var committing = new SerialSessionOperationQueue.Snapshot(
                false, SerialSessionOperationQueue.OperationKind.COMMIT, 0);
        var idle = new SerialSessionOperationQueue.Snapshot(false, null, 0);

        assertEquals(SqlEditorClosePolicy.Action.WAIT_FOR_NON_CANCELLABLE,
                SqlEditorClosePolicy.decide(committing, true));
        assertEquals(SqlEditorClosePolicy.Action.CLOSE,
                SqlEditorClosePolicy.decide(idle, false));
        assertEquals(SqlEditorClosePolicy.Action.RESOLVE_TRANSACTION,
                SqlEditorClosePolicy.decide(idle, true));
    }

    @Test
    void onlyCancellableSqlOffersCancelAndRollback() {
        for (SerialSessionOperationQueue.OperationKind kind :
                new SerialSessionOperationQueue.OperationKind[]{
                        SerialSessionOperationQueue.OperationKind.EXECUTE,
                        SerialSessionOperationQueue.OperationKind.EXPLAIN}) {
            var running = new SerialSessionOperationQueue.Snapshot(false, kind, 0);
            assertEquals(SqlEditorClosePolicy.Action.CANCEL_RUNNING_SQL,
                    SqlEditorClosePolicy.decide(running, true));
        }
    }
}
