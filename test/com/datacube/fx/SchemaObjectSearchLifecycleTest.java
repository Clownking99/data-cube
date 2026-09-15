package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.spi.model.TableInfo;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SchemaObjectSearchLifecycleTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void actualShownDialogLoadsOffFxAndClosingCancelsOnlyItsOwnScope(boolean cancel) throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1),
                finished = new CountDownLatch(1), published = new CountDownLatch(1);
        AtomicBoolean workerOnFx = new AtomicBoolean(true), interrupted = new AtomicBoolean();
        try (var runner = new FxTaskRunner()) {
            var picker = FxUiTestSupport.call(() -> {
                var result = SchemaObjectSearchDialog.create("synthetic", "s", null, () -> {
                    workerOnFx.set(Platform.isFxApplicationThread()); started.countDown();
                    try {
                        release.await();
                        return List.of(new TableInfo("s", "report", TableInfo.Kind.VIEW, null),
                                new TableInfo("s", "other", TableInfo.Kind.TABLE, null));
                    } catch (InterruptedException e) { interrupted.set(true); throw e; }
                    finally { finished.countDown(); }
                }, runner, () -> true);
                list(result).getItems().addListener((ListChangeListener<TableInfo>) change -> {
                    if (!list(result).getItems().isEmpty()) published.countDown();
                });
                result.dialog().show();
                ((TextField) result.dialog().getDialogPane().lookup("#schema-object-query")).setText("REPORT");
                return result;
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS)); assertFalse(workerOnFx.get());
                if (cancel) FxUiTestSupport.call(() -> { picker.dialog().close(); return null; });
                else release.countDown();
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                if (!cancel) assertTrue(published.await(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    assertEquals(cancel ? List.of() : List.of(new TableInfo("s", "report", TableInfo.Kind.VIEW, null)),
                            List.copyOf(list(picker).getItems()));
                    assertNull(list(picker).getSelectionModel().getSelectedItem());
                    assertNull(picker.dialog().getResult());
                    return null;
                });
                assertEquals(cancel, interrupted.get());
                runner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                FxUiTestSupport.call(() -> { picker.dialog().close(); picker.close(); return null; });
            }
        }
    }
    @SuppressWarnings("unchecked") private static ListView<TableInfo> list(SchemaObjectSearchDialog picker) {
        return (ListView<TableInfo>) picker.dialog().getDialogPane().lookup("#schema-object-list");
    }
}
