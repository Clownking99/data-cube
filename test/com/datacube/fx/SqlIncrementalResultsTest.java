package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.*;
import com.datacube.service.*;
import com.datacube.sqleditor.result.ResultFilterState;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlIncrementalResultsTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings = {"complete", "cancel", "close"})
    void firstResultIsBrowsableWhileSecondIsBlockedAndLateResultsRespectLifecycle(String action) throws Exception {
        try (var probe = new SqlProgressProbe(); var runner = new FxTaskRunner()) {
            var pane = FxUiTestSupport.call(() -> new SqlEditorPane(new SessionContext(), probe.manager,
                    new ObjectTreeService(probe.manager), new AppSettings(directory.resolve("settings")),
                    (id, table) -> fail("unexpected designer"), probe.config, null,
                    new SqlHistoryStore(directory.resolve("history")), new ShortcutSettings(directory.resolve("shortcuts")), runner));
            try {
                FxUiTestSupport.call(() -> {
                    var root = (Parent) pane.getNode(); new Scene(root, 880, 850); root.applyCss(); root.layout();
                    pane.setSqlText("select 1; select 2"); button(pane, "executeBtn").fire(); return null;
                });
                assertTrue(probe.secondStarted.await(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    assertEquals(List.of(1), table(pane).getItems().getFirst());
                    assertTrue((boolean) field(pane, "running"));
                    var chooser = (ComboBox<?>) pane.getNode().lookup("#sql-batch-choice");
                    assertFalse(chooser.isDisabled()); assertEquals(2, chooser.getItems().size());
                    assertTrue(button(pane, "executeBtn").isDisabled());
                    assertTrue(((Button) pane.getNode().lookup("#sql-result-apply-database")).isDisabled());
                    var search = (TextField) pane.getNode().lookup("#sql-result-search");
                    assertFalse(search.isDisabled()); search.setText("1"); search.fireEvent(new javafx.event.ActionEvent());
                    table(pane).getColumns().get(1).setPrefWidth(222);
                    var pin = (Button) pane.getNode().lookup("#sql-result-pin");
                    assertFalse(pin.isDisabled()); pin.fire();
                    var retained = pinned(pane).entries().getFirst();
                    assertEquals(List.of(1), retained.result().rows.getFirst());
                    assertTrue(retained.source().target().contains(probe.config.id()));
                    assertEquals("select 1", retained.source().sql().strip());
                    if (action.equals("cancel")) button(pane, "cancelBtn").fire();
                    return null;
                });
                if (action.equals("close")) {
                    pane.closeResources();
                    FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
                } else {
                    if (action.equals("complete")) probe.releaseSecond.countDown();
                    ((SerialSessionOperationQueue) field(pane, "sessionOperations")).idle().toCompletableFuture().get(5, TimeUnit.SECONDS);
                }
                FxUiTestSupport.call(() -> {
                    var chooser = (ComboBox<?>) pane.getNode().lookup("#sql-batch-choice");
                    if (action.equals("close")) {
                        assertTrue(chooser.getItems().isEmpty());
                        assertNull(((ResultFilterState) field(pane, "resultFilterState")).snapshot().activeResult());
                        assertTrue(pinned(pane).entries().isEmpty());
                    } else {
                        assertEquals(1, pinned(pane).entries().size());
                        assertEquals(List.of(1), pinned(pane).entries().getFirst().result().rows.getFirst());
                        assertEquals(List.of(1), table(pane).getItems().getFirst());
                        assertFalse((boolean) field(pane, "running"));
                        assertFalse(((Label) field(pane, "statusLabel")).getText().contains("执行中"));
                        assertFalse(((Label) pane.getNode().lookup("#sql-batch-summary")).getText().contains("执行中"));
                        if (action.equals("complete")) {
                            assertEquals(3, chooser.getItems().size()); chooser.getSelectionModel().select(2);
                            assertEquals(List.of(2), table(pane).getItems().getFirst()); chooser.getSelectionModel().select(1);
                            assertEquals("1", ((ResultFilterState) field(pane, "resultFilterState")).snapshot().searchText());
                            assertEquals(222, table(pane).getColumns().get(1).getPrefWidth());
                        } else assertEquals(2, chooser.getItems().size(), "late cancelled completion must not add a result");
                    }
                    return null;
                });
                assertEquals(2, probe.executed.get());
                if (action.equals("complete")) {
                    FxUiTestSupport.call(() -> { pane.setSqlText("select 3"); button(pane, "executeBtn").fire(); return null; });
                    ((SerialSessionOperationQueue) field(pane, "sessionOperations")).idle().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    FxUiTestSupport.call(() -> {
                        assertEquals(List.of(3), table(pane).getItems().getFirst());
                        assertEquals("", ((ResultFilterState) field(pane, "resultFilterState")).snapshot().searchText());
                        assertTrue(((java.util.Map<?, ?>) field(pane, "batchViews")).isEmpty());
                        assertEquals(List.of(1), pinned(pane).entries().getFirst().result().rows.getFirst());
                        return null;
                    });
                    assertEquals(3, probe.executed.get());
                }
            } finally {
                probe.releaseSecond.countDown(); pane.closeResources();
                FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
            }
        }
    }
    private static Button button(SqlEditorPane pane, String name) { return (Button) field(pane, name); }
    private static com.datacube.sqleditor.result.PinnedResultStore pinned(SqlEditorPane pane) {
        return (com.datacube.sqleditor.result.PinnedResultStore) field(field(pane, "pinnedResults"), "store");
    }
    @SuppressWarnings("unchecked") private static TableView<ObservableList<Object>> table(SqlEditorPane pane) {
        return (TableView<ObservableList<Object>>) field(pane, "resultTable");
    }
}
