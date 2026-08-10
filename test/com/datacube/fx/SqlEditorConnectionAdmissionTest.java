package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.SerialSessionOperationQueue;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlEditorConnectionAdmissionTest {

    @Test
    void firstRelationalAdmissionPinsAndLaterActionsCannotSwitchConnection() {
        ConnConfig alpha = connection("alpha", DbType.POSTGRESQL);
        ConnConfig beta = connection("beta", DbType.ORACLE);
        SqlEditorConnectionAdmission admission = new SqlEditorConnectionAdmission(null);

        assertEquals(alpha, admission.admit(alpha));
        assertEquals(alpha, admission.admit(beta));
        assertEquals(alpha, admission.pinned());
    }

    @Test
    void closingRejectsFirstPinAndRejectionCanReopenAdmission() {
        ConnConfig alpha = connection("alpha", DbType.POSTGRESQL);
        SqlEditorConnectionAdmission admission = new SqlEditorConnectionAdmission(null);

        admission.beginClosing();
        assertThrows(IllegalStateException.class, () -> admission.admit(alpha));
        admission.reopen();

        assertEquals(alpha, admission.admit(alpha));
    }

    @Test
    void redisCanNeverBecomeThePinnedSqlConnection() {
        SqlEditorConnectionAdmission admission = new SqlEditorConnectionAdmission(null);

        assertThrows(IllegalArgumentException.class,
                () -> admission.admit(connection("redis", DbType.REDIS)));
    }

    @Test
    void closeAfterExecuteAdmissionButBeforeSessionPublicationCannotCreateSession() throws Exception {
        ConnConfig alpha = connection("alpha", DbType.POSTGRESQL);
        SqlEditorConnectionAdmission admission = new SqlEditorConnectionAdmission(null);
        admission.admit(alpha);
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        AtomicBoolean sessionCreated = new AtomicBoolean();

        try (FxTaskRunner runner = new FxTaskRunner();
             SerialSessionOperationQueue queue = new SerialSessionOperationQueue(runner)) {
            var execute = queue.submit(SerialSessionOperationQueue.OperationKind.EXECUTE, () -> {
                operationStarted.countDown();
                releasePublication.await();
                admission.requireOpenPinned();
                sessionCreated.set(true);
                return null;
            }, ignored -> {}, ignored -> {});
            assertTrue(operationStarted.await(2, TimeUnit.SECONDS));

            admission.beginClosing();
            var idle = queue.stopAcceptingAndCancelQueued();
            releasePublication.countDown();

            assertThrows(ExecutionException.class, () -> execute.get(2, TimeUnit.SECONDS));
            idle.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertFalse(sessionCreated.get());
        }
    }

    private static ConnConfig connection(String id, DbType type) {
        return new ConnConfig(id, id, type, "localhost", 1, "db", "user", "enc", Map.of());
    }
}
