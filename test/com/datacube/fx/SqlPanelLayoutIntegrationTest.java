package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.sqleditor.*;
import com.datacube.spi.model.QueryResult;
import com.datacube.fx.task.FxTaskScope;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.MenuButton;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.SqlPanelLayout.Mode.*;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlPanelLayoutIntegrationTest {
    @TempDir Path directory;

    @Test void layoutMenuOffersThreeModesOnRealSqlFileTab() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                MenuButton menu = (MenuButton) f.root.lookup("#sql-layout");
                assertNotNull(menu, "SQL tabs need an always reachable layout menu");
                assertEquals(java.util.List.of("上下分屏", "仅看 SQL", "仅看结果"),
                        menu.getItems().stream().map(javafx.scene.control.MenuItem::getText).toList());
                return null;
            });
        }
    }

    @Test void switchingPreservesFileUndoSelectionAndResultProjectionWithoutDatabaseAccess() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.editor.insertText(0, "-- draft\n"); f.editor.selectRange(9, 18);
                String sql = f.editor.getText(), physical = f.document().physicalText();
                f.showRows();
                var table = f.table(); var columns = List.copyOf(table.getColumns());
                table.getColumns().setAll(List.of(columns.get(1), columns.get(0), columns.get(2)));
                columns.get(2).setVisible(false);
                columns.get(0).setSortType(TableColumn.SortType.DESCENDING);
                table.getSortOrder().setAll(List.of(columns.get(0))); table.sort();
                table.getSelectionModel().clearAndSelect(1, columns.get(1));
                table.getFocusModel().focus(1, columns.get(1));
                var rows = List.copyOf(table.getItems());
                var items = table.getItems(); var state = field(f.pane, "resultFilterState");
                for (int i = 0; i < 3; i++) {
                    f.choose(EDITOR); f.choose(RESULTS); f.choose(SPLIT);
                }
                assertSame(f.editor, f.root.lookup("#sql-editor")); assertSame(table, f.table());
                assertEquals(sql, f.editor.getText()); assertEquals(9, f.editor.getAnchor()); assertEquals(18, f.editor.getCaretPosition());
                assertEquals(physical, f.document().physicalText()); assertTrue(f.document().dirty());
                assertSame(items, table.getItems()); assertEquals(rows, table.getItems());
                assertEquals(List.of(columns.get(1), columns.get(0), columns.get(2)), table.getColumns());
                assertFalse(columns.get(2).isVisible()); assertEquals(List.of(columns.get(0)), table.getSortOrder());
                assertEquals(TableColumn.SortType.DESCENDING, columns.get(0).getSortType());
                assertEquals(1, table.getSelectionModel().getSelectedCells().size());
                var selected = table.getSelectionModel().getSelectedCells().get(0);
                assertEquals(1, selected.getRow()); assertSame(columns.get(1), selected.getTableColumn());
                assertEquals(1, table.getFocusModel().getFocusedCell().getRow());
                assertSame(columns.get(1), table.getFocusModel().getFocusedCell().getTableColumn());
                assertSame(state, field(f.pane, "resultFilterState"));
                f.editor.undo(); assertEquals(f.original, f.document().physicalText());
                assertFalse(f.document().dirty()); assertFalse(f.editor.isUndoAvailable());
                return null;
            });
            f.assertOfflineAndUnwritten();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"find", "replace", "line", "button"})
    void explicitEditingEntryRevealsEditorAndRetainsText(String entry) throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                String sql = f.editor.getText();
                f.choose(RESULTS); assertNull(f.root.lookup("#sql-editor"));
                if (entry.equals("button")) ((Button) f.root.lookup("#sql-find")).fire();
                else f.root.fireEvent(key(switch (entry) { case "find" -> KeyCode.F; case "replace" -> KeyCode.H; default -> KeyCode.G; }));
                assertEquals(SPLIT, f.layout().mode()); assertSame(f.editor, f.root.lookup("#sql-editor"));
                assertTrue(f.root.lookup(entry.equals("line") ? "#sql-go-to-line-bar" : "#sql-find-bar").isVisible());
                if (entry.equals("replace")) assertTrue(f.root.lookup("#sql-replace-text").isVisible());
                assertEquals(sql, f.editor.getText()); assertFalse(f.editor.isUndoAvailable()); assertFalse(f.document().dirty());
                return null;
            });
            f.assertOfflineAndUnwritten();
        }
    }

    @Test void hidingEditorClosesBarsButKeepsQueryAndIncomingResultsDoNotStealLayout() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.root.fireEvent(key(KeyCode.H));
                TextField query = (TextField) f.root.lookup("#sql-find-query"); query.setText("demo");
                var find = f.root.lookup("#sql-find-bar");
                f.choose(RESULTS); assertFalse(find.isVisible());
                f.choose(EDITOR); assertEquals("demo", query.getText());
                f.showRows(); assertEquals(EDITOR, f.layout().mode());
                f.choose(RESULTS); assertEquals(3, f.table().getItems().size());
                f.root.fireEvent(key(KeyCode.G)); var go = f.root.lookup("#sql-go-to-line-bar"); assertTrue(go.isVisible());
                f.choose(RESULTS); assertFalse(go.isVisible());
                assertFalse(f.document().dirty()); assertFalse(f.editor.isUndoAvailable());
                return null;
            });
            f.assertOfflineAndUnwritten();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"resources", "tasks", "finalized", "disabled", "fileBusy", "admission"})
    void staleActionsAndEditorShortcutsCannotReopenBlockedPane(String blocker) throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.choose(RESULTS); var layout = f.layout();
                switch (blocker) {
                    case "resources" -> f.pane.closeResources();
                    case "tasks" -> ((FxTaskScope) field(f.pane, "tasks")).close();
                    case "finalized" -> f.pane.finalizeCloseOnFx();
                    case "disabled" -> f.root.setDisable(true);
                    case "fileBusy" -> setField(field(f.pane, "fileController"), "busy", true);
                    case "admission" -> {
                        Object admission = field(f.pane, "admission");
                        var method = admission.getClass().getDeclaredMethod("beginClosing"); method.setAccessible(true); method.invoke(admission);
                    }
                }
                layout.menu().getItems().get(0).fire();
                f.root.fireEvent(key(KeyCode.F));
                assertEquals(RESULTS, layout.mode()); assertNull(f.root.lookup("#sql-editor"));
                if (blocker.equals("fileBusy")) setField(field(f.pane, "fileController"), "busy", false);
                return null;
            });
        }
    }

    @Test void displayOnlySwitchIsAllowedWhileQueryIsRunning() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                setField(f.pane, "running", true);
                try { f.choose(RESULTS); assertEquals(RESULTS, f.layout().mode()); }
                finally { setField(f.pane, "running", false); }
                return null;
            });
            f.assertOfflineAndUnwritten();
        }
    }

    @Test void frozenDraftRejectsLayoutWithoutDirtyingFile() throws Exception {
        try (var f = new Fixture(); var writer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var runtime = FxUiTestSupport.call(() -> new SqlDraftCoordinator(directory.resolve("drafts"), writer,
                    javafx.application.Platform::runLater, javafx.application.Platform::isFxApplicationThread,
                    () -> java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), System::currentTimeMillis));
            try {
                FxUiTestSupport.call(() -> {
                    var binding = f.pane.bindDraft(runtime, java.util.UUID.randomUUID(), null, ignored -> { });
                    f.choose(RESULTS); binding.freeze();
                    f.layout().menu().getItems().get(0).fire();
                    assertEquals(RESULTS, f.layout().mode()); assertNull(f.root.lookup("#sql-editor"));
                    assertFalse(f.document().dirty()); assertFalse(f.editor.isUndoAvailable());
                    return null;
                });
            } finally {
                f.pane.closeResources();
                FxUiTestSupport.call(runtime::shutdown).get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            f.assertOfflineAndUnwritten();
        }
    }

    @Test void executionPlanContentIsNotReplacedByLayoutChanges() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                var plan = (TextArea) field(f.pane, "planArea");
                var resultPane = (TitledPane) field(f.pane, "resultPane");
                plan.setText("Synthetic plan\n  scan demo"); resultPane.setContent(plan);
                plan.selectRange(0, 9);
                f.choose(EDITOR); f.choose(RESULTS); f.choose(SPLIT);
                assertSame(plan, resultPane.getContent()); assertEquals("Synthetic plan\n  scan demo", plan.getText());
                assertEquals("Synthetic", plan.getSelectedText());
                return null;
            });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"menu", "find", "replace", "line"})
    void displayedWindowRestoresDividerAfterNativeLayoutPulses(String entry) throws Exception {
        try (var f = new Fixture()) {
            var stage = FxUiTestSupport.call(() -> {
                var window = new javafx.stage.Stage(); window.setScene(f.root.getScene()); window.show(); return window;
            });
            try {
                pulse(f.root.getScene());
                FxUiTestSupport.call(() -> { f.layout().node().setDividerPositions(0.61); return null; });
                pulse(f.root.getScene());
                double expected = FxUiTestSupport.call(() -> f.layout().node().getDividerPositions()[0]);
                FxUiTestSupport.call(() -> { f.layout().select(EDITOR); return null; }); pulse(f.root.getScene());
                FxUiTestSupport.call(() -> { f.layout().select(RESULTS); return null; }); pulse(f.root.getScene());
                FxUiTestSupport.call(() -> {
                    if (entry.equals("menu")) f.layout().menu().getItems().get(0).fire();
                    else f.root.fireEvent(key(switch (entry) { case "find" -> KeyCode.F; case "replace" -> KeyCode.H; default -> KeyCode.G; }));
                    return null;
                }); pulse(f.root.getScene());
                FxUiTestSupport.call(() -> {
                    assertEquals(SPLIT, f.layout().mode());
                    assertEquals(expected, f.layout().node().getDividerPositions()[0], 0.02);
                    return null;
                });
                FxUiTestSupport.call(() -> { f.layout().node().setDividerPositions(0.53); return null; }); pulse(f.root.getScene());
                FxUiTestSupport.call(() -> {
                    assertEquals(0.53, f.layout().node().getDividerPositions()[0], 0.02, "one-shot restore must not override later drags");
                    return null;
                });
            } finally { FxUiTestSupport.call(() -> { stage.close(); return null; }); }
        }
    }

    private static void pulse(Scene scene) throws Exception {
        var completed = new java.util.concurrent.CompletableFuture<Void>();
        FxUiTestSupport.call(() -> {
            scene.addPostLayoutPulseListener(new Runnable() {
                int remaining = 3;
                @Override public void run() {
                    if (--remaining == 0) { scene.removePostLayoutPulseListener(this); completed.complete(null); }
                    else javafx.application.Platform.requestNextPulse();
                }
            });
            javafx.application.Platform.requestNextPulse(); return null;
        });
        completed.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @ParameterizedTest @CsvSource({"880,dark", "640,dark", "480,dark", "880,light", "640,light", "480,light"})
    void singlePanelUsesFreedSpaceAndMenuRemainsReachable(double width, String theme) throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.root.getScene().setRoot(new javafx.scene.layout.VBox());
                new Scene(f.root, width, 850).getStylesheets().addAll(
                        ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                f.root.resize(width, 850); f.root.applyCss(); f.root.layout();
                var layout = f.layout(); var editor = layout.node().getItems().get(0); var results = layout.node().getItems().get(1);
                double editorHeight = editor.getBoundsInParent().getHeight(), resultsHeight = results.getBoundsInParent().getHeight();
                layout.node().setDividerPositions(0.46); f.root.layout();
                double position = layout.node().getDividerPositions()[0];
                f.choose(EDITOR); assertTrue(editor.getBoundsInParent().getHeight() > editorHeight + 50);
                f.choose(RESULTS); assertTrue(results.getBoundsInParent().getHeight() > resultsHeight + 50);
                assertTrue(layout.menu().isVisible() && layout.menu().isManaged());
                var bounds = layout.menu().localToScene(layout.menu().getLayoutBounds());
                assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= width);
                assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() < layout.node().getLayoutY());
                f.choose(SPLIT); assertEquals(position, layout.node().getDividerPositions()[0], 0.02);
                return null;
            });
        }
    }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final FxTaskRunner runner = new FxTaskRunner();
        final String original = "select id from demo;\r\nselect name from demo;\n";
        final Path file = directory.resolve("synthetic-layout.sql");
        final SqlEditorPane pane;
        final Parent root;
        final CodeArea editor;
        Fixture() throws Exception {
            var loaded = new SqlScriptFileStore().load(Files.writeString(file, original));
            pane = FxUiTestSupport.call(() -> {
                var p = SqlEditorPane.openSqlFile(new SessionContext(), probe.manager, new ObjectTreeService(probe.manager),
                        new AppSettings(directory.resolve("settings")), (id, table) -> fail("unexpected designer"),
                        new SqlHistoryStore(directory.resolve("history")), new ShortcutSettings(directory.resolve("shortcuts")), runner);
                p.installSqlScriptFileController(loaded, new SqlScriptFileStore(), new RecentSqlFiles(directory.resolve("recent")), ignored -> { }, "SQL");
                Parent node = (Parent) p.getNode(); new Scene(node, 880, 850); node.applyCss(); node.layout(); return p;
            });
            root = (Parent) pane.getNode();
            editor = FxUiTestSupport.call(() -> {
                var area = (CodeArea) root.lookup("#sql-editor"); area.getUndoManager().forgetHistory(); return area;
            });
        }
        SqlScriptDocument document() { return (SqlScriptDocument) field(field(pane, "fileController"), "document"); }
        SqlPanelLayout layout() { return (SqlPanelLayout) field(pane, "panelLayout"); }
        void choose(SqlPanelLayout.Mode mode) {
            layout().menu().getItems().get(mode.ordinal()).fire(); root.applyCss(); root.layout();
        }
        @SuppressWarnings("unchecked") TableView<javafx.collections.ObservableList<Object>> table() {
            return (TableView<javafx.collections.ObservableList<Object>>) field(pane, "resultTable");
        }
        void showRows() throws Exception {
            var method = SqlEditorPane.class.getDeclaredMethod("showQueryResult", QueryResult.class); method.setAccessible(true);
            method.invoke(pane, QueryResult.query(List.of("id", "name", "hidden"),
                    List.of(List.of(1, "Alpha", "a"), List.of(2, "Beta", "b"), List.of(3, "Gamma", "c")), 1));
        }
        void assertOfflineAndUnwritten() throws Exception {
            assertEquals(original, Files.readString(file));
            assertEquals(0, probe.providers.get()); assertEquals(0, probe.sessions.get());
            assertEquals(0, probe.metadata.get()); assertEquals(0, probe.network.get());
        }
        @Override public void close() throws Exception {
            pane.closeResources(); FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
            runner.close(); probe.manager.closeAll();
        }
    }
    private static Object field(Object owner, String name) {
        try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void setField(Object owner, String name, Object value) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value);
    }
    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, true, false, false);
    }
}
