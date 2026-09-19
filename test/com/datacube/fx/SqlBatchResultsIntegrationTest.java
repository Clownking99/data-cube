package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.sqleditor.result.ResultFilterState;
import com.datacube.sqleditor.result.ResultExportScope;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlBatchResultsIntegrationTest {
    @TempDir Path directory;
    private SqlScriptDetailsIntegrationTest.Fixture fixture() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory; return owner.new Fixture();
    }

    @Test void batchQueriesExposeTheirOwnRowsSqlSchemaAndExportsWithoutReexecuting() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                var first = QueryResult.query(List.of("first"), List.of(List.of("alpha"), List.of("beta")), 2);
                var second = QueryResult.query(List.of("second"), List.of(List.of(42)), 3);
                invoke(f.pane, "showScriptResults", new Class<?>[]{List.class, long.class, String.class}, List.of(
                        new ScriptOutcome(1, "select first from sample", first),
                        new ScriptOutcome(2, "update sample set n=1", QueryResult.update(1, 7)),
                        new ScriptOutcome(3, "select second from sample", second),
                        new ScriptOutcome(4, "bad", QueryResult.error("synthetic error", 1))), 7L, "ORIGINAL_SCHEMA");
                var choice = chooser(f); assertEquals(1, choice.getSelectionModel().getSelectedIndex());
                assertEquals(List.of("alpha"), f.table().getItems().getFirst());
                assertEquals("select first from sample", f.pane.captureResultExportSnapshot().originalSql());
                assertEquals("ORIGINAL_SCHEMA", state(f).snapshot().effectiveSchema());
                state(f).setSearchText("beta"); invoke(f.pane, "renderResultFilterSnapshot", new Class<?>[]{});
                assertTrue(choice.isVisible()); assertEquals(List.of(List.of("beta")), f.pane.captureResultExportSnapshot().rows(ResultExportScope.CURRENT_FILTERED));
                choice.getSelectionModel().select(3);
                assertEquals(List.of(42), f.table().getItems().getFirst()); assertSame(second, state(f).snapshot().originalResult());
                assertEquals("", state(f).snapshot().searchText()); assertEquals("ORIGINAL_SCHEMA", state(f).snapshot().effectiveSchema());
                assertEquals("select second from sample", f.pane.captureResultExportSnapshot().originalSql());
                choice.getSelectionModel().select(2); assertNull(f.pane.captureResultExportSnapshot());
                assertTrue(f.table().getItems().getFirst().getFirst().toString().contains("7"));
                choice.getSelectionModel().select(4); assertNull(f.pane.captureResultExportSnapshot());
                assertEquals("synthetic error", f.table().getItems().getFirst().getFirst());
                choice.getSelectionModel().select(0); assertEquals(4, f.table().getItems().size()); assertTrue(f.details().getNode().isVisible());
                choice.getSelectionModel().select(1); assertEquals(2, f.table().getItems().size());
                Label summary = (Label) f.root.lookup("#sql-batch-summary"); assertTrue(summary.getText().contains("失败 1"));
                assertFalse(summary.getStyle().contains("-status-ok"));
                assertEquals(f.original.replace("\r\n", "\n"), f.editor.getText()); assertFalse(f.editor.isUndoAvailable()); assertFalse(f.document().dirty());
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void newSingleResultClearsBatchAndDetachedChoicesCannotResurrectOldData() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.batch(); var choices = chooser(f); Object old = choices.getValue();
                f.show(List.of(new ScriptOutcome(1, "select new", QueryResult.query(List.of("new"), List.of(List.of("fresh")), 1))));
                assertFalse(f.root.lookup("#sql-batch-results").isManaged()); assertTrue(choices.getItems().isEmpty());
                selectForeign(choices, old); assertEquals(List.of("fresh"), f.table().getItems().getFirst());
                f.batch(); f.pane.finalizeCloseOnFx(); assertTrue(choices.getItems().isEmpty());
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"resources", "tasks", "finalized", "disabled", "tableDisabled", "running", "admission", "queue"})
    void blockedSelectionRestoresThePreviousChoiceAndNeverChangesData(String blocker) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(List.of(new ScriptOutcome(1, "select first", QueryResult.query(List.of("one"), List.of(List.of(1)), 1)),
                        new ScriptOutcome(2, "select second", QueryResult.query(List.of("two"), List.of(List.of(2)), 1))));
                var choice = chooser(f); Object candidate = choice.getItems().get(2); Object before = choice.getValue();
                var rows = f.table().getItems();
                switch (blocker) {
                    case "resources" -> f.pane.closeResources();
                    case "tasks" -> ((com.datacube.fx.task.FxTaskScope) field(f.pane, "tasks")).close();
                    case "finalized" -> f.pane.finalizeCloseOnFx();
                    case "disabled" -> f.root.setDisable(true);
                    case "tableDisabled" -> f.table().setDisable(true);
                    case "running" -> setField(f.pane, "running", true);
                    case "admission" -> invoke(field(f.pane, "admission"), "beginClosing", new Class<?>[]{});
                    case "queue" -> invoke(field(f.pane, "sessionOperations"), "stopAcceptingAndCancelQueued", new Class<?>[]{});
                }
                selectForeign(choice, candidate); assertSame(rows, f.table().getItems());
                assertEquals(List.of(1), f.table().getItems().getFirst());
                if (blocker.equals("finalized")) assertNull(choice.getValue()); else assertSame(before, choice.getValue());
                if (blocker.equals("running")) setField(f.pane, "running", false);
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "error", "plan", "clear"})
    void replacingBatchReleasesChoicesAndOldSummary(String replacement) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.batch(); var choices = chooser(f);
                switch (replacement) {
                    case "empty" -> f.show(List.of());
                    case "error" -> invoke(f.pane, "showError", new Class<?>[]{String.class, long.class}, "new error", 1L);
                    case "plan" -> invoke(f.pane, "showPlan", new Class<?>[]{String.class, long.class, int.class}, "scan", 1L, 1);
                    case "clear" -> invoke(f.pane, "clearResultFilterState", new Class<?>[]{});
                }
                assertTrue(choices.getItems().isEmpty()); assertNull(choices.getValue());
                assertFalse(f.root.lookup("#sql-batch-results").isManaged());
                assertEquals("", ((Label) f.root.lookup("#sql-batch-summary")).getText());
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void emptyQueryKeepsItsColumnsAndUpdateWithoutCountIsNotNegativeRows() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(List.of(new ScriptOutcome(1, "ddl", QueryResult.update(1, -1)),
                        new ScriptOutcome(2, "select empty", QueryResult.query(List.of("empty_column"), List.of(), 1))));
                var choice = chooser(f); assertEquals(2, choice.getSelectionModel().getSelectedIndex());
                assertTrue(f.table().getItems().isEmpty()); assertEquals(2, f.table().getColumns().size());
                assertEquals(List.of("empty_column"), f.pane.captureResultExportSnapshot().columns());
                choice.getSelectionModel().select(1); assertEquals("影响行数未提供", f.table().getItems().getFirst().getFirst());
                assertNull(f.pane.captureResultExportSnapshot()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @CsvSource({"480,dark", "480,light", "880,dark", "880,light"})
    void resultSwitcherAndFailureSummaryStayInsideNarrowAndWidePanels(double width, String theme) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.root.getScene().setRoot(new javafx.scene.layout.VBox());
                new javafx.scene.Scene(f.root, width, 850).getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                f.batch(); f.root.resize(width, 850); f.root.applyCss(); f.root.layout();
                var bar = (javafx.scene.layout.Region) f.root.lookup("#sql-batch-results");
                for (String id : List.of("sql-batch-choice", "sql-batch-summary")) {
                    var node = (javafx.scene.layout.Region) f.root.lookup("#" + id); var bounds = node.localToScene(node.getLayoutBounds());
                    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= width + 1, id);
                    assertTrue(node.getHeight() + 1 >= node.prefHeight(node.getWidth()), id);
                }
                assertTrue(bar.isVisible()); assertTrue(f.table().getHeight() >= 100); return null;
            });
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"}) private static void selectForeign(ComboBox choices, Object old) { choices.setValue(old); }
    private static ResultFilterState state(SqlScriptDetailsIntegrationTest.Fixture f) { return (ResultFilterState) field(f.pane, "resultFilterState"); }
    private static ComboBox<?> chooser(SqlScriptDetailsIntegrationTest.Fixture f) {
        var node = f.root.lookup("#sql-batch-choice"); assertNotNull(node, "Batch needs a result switcher, not only a summary"); return (ComboBox<?>) node;
    }
}
