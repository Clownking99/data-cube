package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.sqleditor.result.PinnedResultStore;
import com.datacube.sqleditor.result.ResultFilterState;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.control.*;
import javafx.event.ActionEvent;
import javafx.animation.PauseTransition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlPinnedResultsTest {
    @TempDir Path directory;

    @Test void pinnedQuerySurvivesNewBatchAndClearingCurrentResultsWithoutRequery() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory;
        try (var f = owner.new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.window();
                f.show(List.of(new ScriptOutcome(1, "select 'old'", QueryResult.query(List.of("v"), List.of(List.of("old")), 1))));
                Button pin = (Button) f.root.lookup("#sql-result-pin");
                assertNotNull(pin, "loaded query must offer bounded cross-batch retention");
                assertFalse(pin.isDisabled()); pin.fire();
                var old = store(f).entries().getFirst();
                f.editor.replaceText("select 'edited editor, not executed'");
                MenuButton saved = (MenuButton) f.root.lookup("#sql-pinned-results");
                assertEquals(1, saved.getItems().stream().filter(item -> item.getId() != null && item.getId().startsWith("sql-pinned-entry-")).count());
                saved.getItems().getFirst().fire(); var window = dialog(f);
                assertNotNull(window); assertEquals(javafx.stage.Modality.NONE, window.getModality());
                assertEquals("select 'old'", ((TextArea) window.getDialogPane().lookup("#sql-pinned-sql")).getText());
                assertEquals(List.of("old"), table(window).getItems().getFirst());
                f.show(List.of(new ScriptOutcome(1, "select 'new'", QueryResult.query(List.of("v"), List.of(List.of("new")), 1))));
                assertSame(window, dialog(f)); assertEquals(List.of("old"), table(window).getItems().getFirst());
                invoke(f.pane, "clearResultFilterState", new Class<?>[]{});
                assertEquals(1, saved.getItems().stream().filter(item -> item.getId() != null && item.getId().startsWith("sql-pinned-entry-")).count());
                invoke(f.pane, "showPlan", new Class<?>[]{String.class, long.class, int.class}, "synthetic scan", 1L, 1);
                assertEquals(List.of(old), store(f).entries()); assertSame(window, dialog(f));
                var stale = saved.getItems().getFirst();
                ((Button) window.getDialogPane().lookup("#sql-pinned-remove")).fire();
                assertFalse(window.isShowing()); assertNull(window.getDialogPane().getContent()); assertTrue(store(f).entries().isEmpty());
                stale.fire(); assertNull(dialog(f));
                return null;
            });
            assertEquals(0, f.probe.providers.get()); assertEquals(0, f.probe.sessions.get());
            assertEquals(0, f.probe.metadata.get()); assertEquals(0, f.probe.network.get());
        }
    }

    @Test void viewerKeepsDuplicateColumnsNullsPartialNoticeAndOriginWithoutChangingCurrentView() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory;
        try (var f = owner.new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.window();
                var result = QueryResult.queryWithMetadata(List.of(com.datacube.spi.model.ResultColumn.unknown(0, "dup"), com.datacube.spi.model.ResultColumn.unknown(1, "dup")),
                        List.of(java.util.Arrays.asList(null, "NULL"), List.of("", "first\nsecond"), List.of("a", "match")), 1, true)
                        .withRetentionNotice("部分列仅预览");
                invoke(f.pane, "showScriptResults", new Class<?>[]{List.class, long.class, String.class},
                        List.of(new ScriptOutcome(1, "select original", result)), 1L, "original_schema");
                var state = (ResultFilterState) field(f.pane, "resultFilterState");
                state.setSearchText("match"); invoke(f.pane, "renderResultFilterSnapshot", new Class<?>[]{});
                f.table().getColumns().get(2).setVisible(false);
                ((TextField) field(f.pane, "schemaField")).setText("later_schema");
                ((Button) f.root.lookup("#sql-result-pin")).fire();
                assertEquals(3, store(f).entries().getFirst().result().rows.size(), "pin retains loaded rows, independent of local filters");
                var menu = (MenuButton) f.root.lookup("#sql-pinned-results"); menu.getItems().getFirst().fire();
                var window = dialog(f); var table = table(window);
                assertEquals(3, table.getItems().size()); assertEquals(2, table.getColumns().size());
                assertTrue(((Label) window.getDialogPane().lookup("#sql-pinned-identity")).getText().contains("original_schema"));
                assertFalse(((Label) window.getDialogPane().lookup("#sql-pinned-identity")).getText().contains("later_schema"));
                assertTrue(((Label) window.getDialogPane().lookup("#sql-pinned-identity")).getText().contains("固定时间："));
                String boundary = ((Label) window.getDialogPane().lookup("#sql-pinned-boundary")).getText();
                assertTrue(boundary.contains("原结果已截断")); assertTrue(boundary.contains("部分列仅预览"));
                table.getSelectionModel().clearAndSelect(0, table.getColumns().get(0));
                var cell = (TextArea) window.getDialogPane().lookup("#sql-pinned-cell");
                assertEquals("NULL（数据库空值）", cell.getText()); assertFalse(cell.isEditable());
                table.getSelectionModel().clearAndSelect(0, table.getColumns().get(1)); assertEquals("NULL", cell.getText());
                table.getSelectionModel().clearAndSelect(1, table.getColumns().get(0)); assertEquals("空字符串", cell.getText());
                table.getSelectionModel().clearAndSelect(1, table.getColumns().get(1)); assertEquals("first\nsecond", cell.getText());
                var search = (TextField) window.getDialogPane().lookup("#sql-pinned-search"); search.setText("MATCH");
                var debounce = (PauseTransition) field(window, "debounce"); debounce.stop(); debounce.getOnFinished().handle(new ActionEvent());
                assertEquals(List.of(List.of("a", "match")), table.getItems());
                assertEquals("match", state.snapshot().searchText()); assertEquals(1, f.table().getItems().size());
                assertFalse(f.table().getColumns().get(2).isVisible());
                assertEquals(3, result.rows.size()); assertNull(result.rows.getFirst().getFirst());
                window.close(); assertNull(dialog(f)); assertNull(window.getDialogPane().getContent());
                assertTrue(table.getItems().isEmpty()); assertEquals("", cell.getText());
                assertEquals(1, store(f).entries().size(), "closing a viewer does not remove the snapshot");
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void repeatedPinningReachesExplicitLimitAndClearInvalidatesOldMenusAndReleasesViewer() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory;
        try (var f = owner.new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.show(List.of(new ScriptOutcome(1, "same", QueryResult.query(List.of("n"), List.of(List.of(1)), 1))));
                var pin = (Button) f.root.lookup("#sql-result-pin");
                pin.fire(); pin.fire(); pin.fire(); var retained = store(f).entries(); pin.fire();
                assertEquals(retained, store(f).entries()); assertEquals(3, retained.size());
                assertEquals(3, retained.stream().map(PinnedResultStore.Entry::id).distinct().count());
                assertTrue(((Label) f.root.lookup("#sql-pinned-status")).getText().contains("数量已达上限"));
                var menu = (MenuButton) f.root.lookup("#sql-pinned-results"); var oldAction = menu.getItems().getFirst();
                oldAction.fire(); var window = dialog(f);
                menu.getItems().getLast().fire();
                assertTrue(store(f).entries().isEmpty()); assertEquals(0, store(f).usage().rows());
                assertNull(dialog(f)); assertFalse(window.isShowing()); assertNull(window.getDialogPane().getContent());
                oldAction.fire(); assertNull(dialog(f));
                pin.fire(); assertEquals(1, store(f).entries().size());
                menu.getItems().getFirst().fire(); window = dialog(f);
                f.pane.finalizeCloseOnFx();
                assertTrue(store(f).entries().isEmpty()); assertEquals(0, store(f).usage().textUnits());
                assertNull(dialog(f)); assertFalse(window.isShowing()); assertTrue(menu.getItems().isEmpty());
                pin.getOnAction().handle(new ActionEvent()); oldAction.fire(); assertTrue(store(f).entries().isEmpty());
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void databaseFilteredSnapshotLabelsOriginalSqlWithoutPretendingToBeAnExecutableRequest() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory;
        try (var f = owner.new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.show(List.of(new ScriptOutcome(1, "select v from synthetic", QueryResult.query(List.of("v"), List.of(List.of(1), List.of(2)), 1))));
                var state = (ResultFilterState) field(f.pane, "resultFilterState");
                state.appendCondition(new com.datacube.sqleditor.result.FilterCondition(0,
                        com.datacube.sqleditor.result.FilterConnector.AND, com.datacube.sqleditor.result.FilterOperator.EQ, 2));
                state.setDatabaseUnavailableReason(null);
                var request = state.databaseRequest();
                assertTrue(state.databaseApplied(request.generation(), QueryResult.query(List.of("v"), List.of(List.of(2)), 1)));
                invoke(f.pane, "renderResultFilterSnapshot", new Class<?>[]{});
                ((Button) f.root.lookup("#sql-result-pin")).fire();
                var entry = store(f).entries().getFirst();
                assertTrue(entry.source().databaseFiltered()); assertEquals("select v from synthetic", entry.source().sql());
                assertEquals(List.of(List.of(2)), entry.result().rows);
                ((MenuButton) f.root.lookup("#sql-pinned-results")).getItems().getFirst().fire();
                var window = dialog(f);
                String text = ((Label) window.getDialogPane().lookup("#sql-pinned-boundary")).getText();
                assertTrue(text.contains("已应用数据库筛选")); assertTrue(text.contains("筛选参数未保留"));
                assertNull(window.getDialogPane().lookup("#sql-result-apply-database"));
                assertNull(window.getDialogPane().lookup("#sql-editor"));
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"resources", "tasks", "finalized", "disabled", "admission", "queue"})
    void lifecycleGateRejectsStalePinAndViewActions(String blocker) throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory;
        try (var f = owner.new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(List.of(new ScriptOutcome(1, "select 1", QueryResult.query(List.of("n"), List.of(List.of(1)), 1))));
                var pin = (Button) f.root.lookup("#sql-result-pin"); pin.fire();
                var item = ((MenuButton) f.root.lookup("#sql-pinned-results")).getItems().getFirst();
                switch (blocker) {
                    case "resources" -> f.pane.closeResources();
                    case "tasks" -> ((com.datacube.fx.task.FxTaskScope) field(f.pane, "tasks")).close();
                    case "finalized" -> f.pane.finalizeCloseOnFx();
                    case "disabled" -> f.root.setDisable(true);
                    case "admission" -> invoke(field(f.pane, "admission"), "beginClosing", new Class<?>[]{});
                    case "queue" -> invoke(field(f.pane, "sessionOperations"), "stopAcceptingAndCancelQueued", new Class<?>[]{});
                }
                int count = store(f).entries().size();
                pin.getOnAction().handle(new ActionEvent()); item.fire();
                assertEquals(count, store(f).entries().size()); assertNull(dialog(f)); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"dark", "light"})
    void longProvenanceHasBoundedLayoutAndLateSearchCannotRepopulateClosedViewer(String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            var store = new PinnedResultStore();
            var entry = store.pin(QueryResult.query(List.of("column"), List.of(List.of("value")), 1).withRetentionNotice("notice ".repeat(500)),
                    new PinnedResultStore.Source("target ".repeat(70), "schema ".repeat(70), "source ".repeat(2000), false));
            var window = new SqlPinnedResultDialog(null, entry, () -> fail("unexpected removal"));
            try {
                var pane = window.getDialogPane();
                pane.getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                pane.setPrefSize(540, 600); window.show(); pane.applyCss(); pane.layout();
                var metadata = (ScrollPane) pane.lookup("#sql-pinned-metadata");
                assertTrue(metadata.getHeight() >= 80 && metadata.getHeight() <= 180);
                var table = table(window); assertTrue(table.getHeight() >= 120);
                assertTrue(table.getBoundsInParent().getMaxY() <= ((javafx.scene.layout.VBox) pane.getContent()).getHeight());
                var search = (TextField) pane.lookup("#sql-pinned-search"); search.setText("value");
                var debounce = (PauseTransition) field(window, "debounce"); var late = debounce.getOnFinished();
                window.close(); late.handle(new ActionEvent());
                assertTrue(table.getItems().isEmpty()); assertNull(pane.getContent()); assertNull(field(window, "removeAction"));
            } finally { window.close(); window.dispose(); store.close(); }
            return null;
        });
    }

    private static PinnedResultStore store(SqlScriptDetailsIntegrationTest.Fixture f) {
        return (PinnedResultStore) field(field(f.pane, "pinnedResults"), "store");
    }
    private static SqlPinnedResultDialog dialog(SqlScriptDetailsIntegrationTest.Fixture f) {
        return (SqlPinnedResultDialog) field(field(f.pane, "pinnedResults"), "dialog");
    }
    @SuppressWarnings("unchecked") private static TableView<List<Object>> table(SqlPinnedResultDialog dialog) {
        return (TableView<List<Object>>) dialog.getDialogPane().lookup("#sql-pinned-table");
    }
}
