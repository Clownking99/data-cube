package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.RecentSqlFiles;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.ShortcutAction;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.JdbcEditorSession.TransactionMode;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javafx.geometry.Bounds;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SqlEditorUsabilityTest {
    @TempDir Path directory;

    @Test
    void visibleScopeMatchesTheExecutionTextWithoutChangingFileState() throws Exception {
        String sql = "select 1;\n\t select 2;";
        Path file = Files.writeString(directory.resolve("scope.sql"), sql);
        try (var fixture = new Fixture(new SqlScriptFileStore().load(file))) {
            FxUiTestSupport.call(() -> {
                Parent root = (Parent) fixture.pane.getNode();
                new Scene(root, 880, 800);
                root.applyCss(); root.layout();
                var editor = (org.fxmisc.richtext.CodeArea) root.lookup("#sql-editor");
                var button = (Button) root.lookup("#sql-execute");
                var scope = (Labeled) root.lookup("#sql-execution-scope");
                var consume = SqlEditorPane.class.getDeclaredMethod("selectedOrAllSql");
                consume.setAccessible(true);
                assertEquals(sql, consume.invoke(fixture.pane));
                assertEquals("执行全部 (F5)", button.getText());
                editor.selectRange(sql.length(), 12);
                assertEquals("select 2;", consume.invoke(fixture.pane));
                assertEquals("执行选中 (F5)", button.getText());
                assertEquals("执行范围：选中内容", scope.getText());
                editor.selectRange(9, 12);
                assertEquals(sql, consume.invoke(fixture.pane));
                assertEquals("执行全部 (F5)", button.getText());
                assertTrue(scope.getText().contains("选区仅含空白"));
                fixture.shortcuts.apply(java.util.Map.of(ShortcutAction.SQL_EXECUTE, KeyCombination.keyCombination("F6")));
                assertEquals("执行全部 (F6)", button.getText());
                assertTrue(button.isDisabled(), "scope must not admit an unbound execution");
                assertEquals(sql, editor.getText());
                var fileField = SqlEditorPane.class.getDeclaredField("fileController");
                fileField.setAccessible(true);
                var documentField = SqlScriptFileController.class.getDeclaredField("document");
                documentField.setAccessible(true);
                assertFalse(((com.datacube.sqleditor.SqlScriptDocument)
                        documentField.get(fileField.get(fixture.pane))).dirty());
                return null;
            });
        }
        assertEquals(sql, Files.readString(file), "scope presentation must not write the SQL file");
    }

    @ParameterizedTest
    @ValueSource(strings = {"toolbar", "find", "replace", "goto-button", "goto-key"})
    void findAndReplaceDismissCompletionBeforeTakingFocus(String entry) throws Exception {
        try (var fixture = new Fixture(null)) {
            FxUiTestSupport.call(() -> {
                Parent root = (Parent) fixture.pane.getNode();
                var stage = new javafx.stage.Stage();
                stage.setScene(new Scene(root, 880, 800));
                root.applyCss(); root.layout();
                var editor = (org.fxmisc.richtext.CodeArea) root.lookup("#sql-editor");
                try {
                    stage.show();
                    root.applyCss(); root.layout();
                    editor.replaceText("sel");
                    editor.moveTo(3);
                    editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE,
                            false, true, false, false));
                    var completionField = SqlEditorPane.class.getDeclaredField("autoComplete");
                    completionField.setAccessible(true);
                    var popupField = SqlAutoComplete.class.getDeclaredField("popup");
                    popupField.setAccessible(true);
                    var popup = (javafx.stage.Popup) popupField.get(completionField.get(fixture.pane));
                    assertTrue(popup.isShowing(), "start with actual SQL completion candidates");
                    boolean goTo = entry.startsWith("goto");
                    if (entry.equals("goto-button")) ((Button) root.lookup("#sql-go-to-line")).fire();
                    else if (entry.equals("toolbar")) ((Button) root.lookup("#sql-find")).fire();
                    else editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                            goTo ? KeyCode.G : entry.equals("find") ? KeyCode.F : KeyCode.H, false, true, false, false));
                    assertTrue(root.lookup(goTo ? "#sql-go-to-line-bar" : "#sql-find-bar").isVisible());
                    assertFalse(popup.isShowing(), "completion must not cover or intercept the find/replace controls");
                    if (entry.equals("replace")) assertTrue(root.lookup("#sql-replace-pane").isManaged());
                    assertEquals("sel", editor.getText(), "opening find must not accept a completion");
                    assertTrue(root.lookup("#sql-execute").isDisabled());
                } finally {
                    editor.clear();
                    stage.hide();
                }
                return null;
            });
        }
    }

    @Test
    void findEntryAndReboundShortcutStayInsideTheUnboundEditor() throws Exception {
        String sql = "select '查找';";
        Path file = Files.writeString(directory.resolve("find.sql"), sql);
        try (var fixture = new Fixture(new SqlScriptFileStore().load(file))) {
            FxUiTestSupport.call(() -> {
                Parent root = (Parent) fixture.pane.getNode();
                new Scene(root, 480, 800);
                root.resize(480, 800);
                root.applyCss();
                root.layout();
                var editor = (org.fxmisc.richtext.CodeArea) root.lookup("#sql-editor");
                editor.selectRange(0, 6);
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, true, false, false));
                assertTrue(root.lookup("#sql-find-bar").isVisible());
                assertEquals("select", ((TextField) root.lookup("#sql-find-query")).getText());
                ((Button) root.lookup("#sql-find-close")).fire();
                assertFalse(root.lookup("#sql-find-bar").isManaged());
                fixture.shortcuts.apply(java.util.Map.of(ShortcutAction.SQL_FIND, KeyCombination.keyCombination("Ctrl+G")));
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, true, false, false));
                assertFalse(root.lookup("#sql-find-bar").isVisible(), "old binding must stop opening find immediately");
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.G, false, true, false, false));
                assertTrue(root.lookup("#sql-find-bar").isVisible());
                ((Button) root.lookup("#sql-find-close")).fire();
                ((Button) root.lookup("#sql-find")).fire();
                assertTrue(root.lookup("#sql-find-bar").isVisible(), "mouse entry remains available after rebinding");
                assertEquals(sql, editor.getText());
                assertTrue(root.lookup("#sql-execute").isDisabled(), "find must not admit a database execution");
                return null;
            });
        }
        assertEquals(sql, Files.readString(file), "finding must not write the file");
    }

    @Test
    void replaceEntryAndReboundShortcutRemainSeparateFromHistoryAndExecution() throws Exception {
        try (var fixture = new Fixture(null)) {
            FxUiTestSupport.call(() -> {
                Parent root = (Parent) fixture.pane.getNode();
                new Scene(root, 480, 800);
                root.applyCss(); root.layout();
                var editor = (org.fxmisc.richtext.CodeArea) root.lookup("#sql-editor");
                editor.replaceText("select 'keep';");
                editor.getUndoManager().forgetHistory();
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.H, false, true, false, false));
                assertTrue(root.lookup("#sql-replace-pane").isManaged());
                ((Button) root.lookup("#sql-find-close")).fire();
                fixture.shortcuts.apply(java.util.Map.of(ShortcutAction.SQL_REPLACE, KeyCombination.keyCombination("Ctrl+R")));
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.H, false, true, false, false));
                assertFalse(root.lookup("#sql-find-bar").isVisible());
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.R, false, true, false, false));
                assertTrue(root.lookup("#sql-replace-pane").isVisible());
                ((Button) root.lookup("#sql-find-replace-toggle")).fire();
                assertFalse(root.lookup("#sql-replace-pane").isManaged());
                ((Button) root.lookup("#sql-find-replace-toggle")).fire();
                assertTrue(root.lookup("#sql-replace-pane").isManaged());
                assertEquals("select 'keep';", editor.getText());
                assertFalse(editor.isUndoAvailable(), "opening replacement must not edit the script");
                assertTrue(root.lookup("#sql-execute").isDisabled());
                assertEquals(KeyCombination.keyCombination("Ctrl+Shift+H"), fixture.shortcuts.get(ShortcutAction.SQL_HISTORY));
                editor.clear(); // Return to the clean baseline for fixture close.
                return null;
            });
        }
    }

    @Test
    void saveChooserStartsAtTheCurrentFileIncludingUnicodeAndSpaces() throws Exception {
        Path file = Files.writeString(directory.resolve("月度 查询.sql"), "select 1;");
        var loaded = new SqlScriptFileStore().load(file);
        try (var fixture = new Fixture(loaded)) {
            FxUiTestSupport.call(() -> {
                var chooser = fixture.pane.createSqlSaveChooser();
                assertEquals(file.getParent().toRealPath().toFile(), chooser.getInitialDirectory());
                assertEquals("月度 查询.sql", chooser.getInitialFileName());
                assertEquals(List.of("*.sql"), chooser.getExtensionFilters().getFirst().getExtensions());
                assertEquals("select 1;", Files.readString(file), "choosing defaults must not write SQL");
                return null;
            });
        }
    }

    @ParameterizedTest
    @CsvSource({"880, dark", "640, dark", "480, dark", "880, light", "640, light", "480, light"})
    void openFindBarWrapsWithoutClippingControlsOrCoveringTheEditor(double width, String theme) throws Exception {
        try (var fixture = new Fixture(null)) {
            FxUiTestSupport.call(() -> {
                Parent root = (Parent) fixture.pane.getNode();
                Scene scene = new Scene(root, width, 800);
                scene.getStylesheets().addAll(
                        ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                root.resize(width, 800);
                root.applyCss();
                root.layout();
                ((Button) root.lookup("#sql-find")).fire();
                ((Button) root.lookup("#sql-find-replace-toggle")).fire();
                ((TextField) root.lookup("#sql-find-query")).setText("x".repeat(1025));
                root.applyCss();
                root.layout();
                Region bar = (Region) root.lookup("#sql-find-bar");
                Bounds area = bar.localToScene(bar.getLayoutBounds());
                assertTrue(area.getWidth() <= width, "find bar must not expand its host");
                for (String id : List.of("query", "match-case", "whole-word", "previous", "next", "close", "status")) {
                    Region control = (Region) bar.lookup("#sql-find-" + id);
                    Bounds bounds = control.localToScene(control.getLayoutBounds());
                    assertTrue(control.isVisible() && control.isManaged(), id);
                    assertTrue(bounds.getMinX() >= area.getMinX() - 1
                                    && bounds.getMaxX() <= area.getMaxX() + 1
                                    && bounds.getMinY() >= area.getMinY() - 1
                                    && bounds.getMaxY() <= area.getMaxY() + 1,
                            id + " must stay inside the find bar at width " + width + ": " + bounds + " in " + area);
                    if (!id.equals("status")) assertTrue(control.getWidth() + 1 >= control.prefWidth(-1), id);
                }
                for (String id : List.of("text", "current", "all", "status")) {
                    Region control = (Region) bar.lookup("#sql-replace-" + id);
                    Bounds bounds = control.localToScene(control.getLayoutBounds());
                    assertTrue(control.isVisible() && control.isManaged(), id);
                    assertTrue(bounds.getMinX() >= area.getMinX() - 1 && bounds.getMaxX() <= area.getMaxX() + 1
                                    && bounds.getMaxY() <= area.getMaxY() + 1,
                            "replacement " + id + " must fit at width " + width);
                }
                var editor = root.lookup("#sql-editor");
                Bounds editorBounds = editor.localToScene(editor.getLayoutBounds());
                assertTrue(editorBounds.getMinY() >= area.getMaxY() - 1, "find must not overlay SQL");
                assertTrue(editorBounds.getHeight() > 40, "SQL must retain usable vertical space");
                Region scopeBar = (Region) root.lookup("#sql-editor-scope-bar");
                Bounds scopeBounds = scopeBar.localToScene(scopeBar.getLayoutBounds());
                assertTrue(scopeBounds.getMinY() >= editorBounds.getMaxY() - 1,
                        "scope information belongs below the editable SQL, not over it");
                assertTrue(scopeBounds.getMaxX() <= width + 1, "scope bar must fit the editor width");
                for (String id : List.of("sql-editor-position", "sql-execution-scope", "sql-editor-wrap")) {
                    Region part = (Region) root.lookup("#" + id);
                    Bounds bounds = part.localToScene(part.getLayoutBounds());
                    assertTrue(bounds.getMaxX() <= scopeBounds.getMaxX() + 1
                                    && bounds.getMaxY() <= scopeBounds.getMaxY() + 1,
                            id + " must fit the wrapped context bar");
                    if (id.equals("sql-editor-wrap")) {
                        assertTrue(part.getWidth() + 1 >= part.prefWidth(-1), "wrapping label must not be truncated");
                        var wrap = (javafx.scene.control.CheckBox) part;
                        assertTrue(wrap.isFocusTraversable());
                        wrap.fire();
                        assertTrue(((org.fxmisc.richtext.CodeArea) editor).isWrapText());
                    }
                }
                ((Button) root.lookup("#sql-find-close")).fire();
                root.layout();
                assertFalse(bar.isManaged());
                assertTrue(editor.localToScene(editor.getLayoutBounds()).getMinY() < editorBounds.getMinY(),
                        "closing find must release its layout space");
                return null;
            });
        }
    }

    @Test
    void unboundSqlGetsADefaultNameWithoutChoosingADirectory() throws Exception {
        try (var fixture = new Fixture(null)) {
            FxUiTestSupport.call(() -> {
                var chooser = fixture.pane.createSqlSaveChooser();
                assertEquals("query.sql", chooser.getInitialFileName());
                assertNull(chooser.getInitialDirectory());
                return null;
            });
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unavailableParentFallsBackWithoutRecreatingIt(boolean replacedByFile) throws Exception {
        Path parent = Files.createDirectory(directory.resolve("removed"));
        Path file = Files.writeString(parent.resolve("keep-name.sql"), "select 1;");
        var loaded = new SqlScriptFileStore().load(file);
        Files.delete(file);
        Files.delete(parent);
        if (replacedByFile) Files.writeString(parent, "unrelated");
        try (var fixture = new Fixture(loaded)) {
            FxUiTestSupport.call(() -> {
                var chooser = fixture.pane.createSqlSaveChooser();
                assertEquals("keep-name.sql", chooser.getInitialFileName());
                assertNull(chooser.getInitialDirectory());
                assertFalse(Files.isDirectory(parent));
                if (replacedByFile) assertEquals("unrelated", Files.readString(parent));
                return null;
            });
        }
    }

    @ParameterizedTest
    @CsvSource({"880, dark", "640, dark", "480, dark", "880, light", "640, light", "480, light"})
    void primaryActionsRemainReadableAndInsideTheEditor(double width, String theme) throws Exception {
        try (var fixture = new Fixture(null)) {
            FxUiTestSupport.call(() -> {
                Parent root = (Parent) fixture.pane.getNode();
                Scene scene = new Scene(root, width, 800);
                scene.getStylesheets().addAll(
                        ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                root.resize(width, 800);
                root.applyCss();
                root.layout();
                Region toolbar = (Region) root.lookup("#sql-primary-toolbar");
                var actions = new java.util.HashSet<javafx.scene.Node>();
                for (String selector : List.of(".button", ".menu-button", ".check-box")) {
                    actions.addAll(toolbar.lookupAll(selector));
                }
                assertEquals(14, actions.size(), "all file, execution, editing and result actions remain present");
                assertTrue(actions.contains(root.lookup("#sql-line-comment")), "line comment is an explicit editing action");
                assertTrue(actions.contains(root.lookup("#sql-duplicate-lines")), "duplicate lines is an explicit editing action");
                Bounds area = toolbar.localToScene(toolbar.getLayoutBounds());
                for (var node : actions) {
                    Labeled action = (Labeled) node;
                    assertTrue(action.isVisible() && action.isManaged(), action.getText());
                    assertTrue(action.getWidth() + 1 >= action.prefWidth(-1),
                            action.getText() + " must retain its full label at width " + width);
                    Bounds bounds = action.localToScene(action.getLayoutBounds());
                    assertTrue(bounds.getMinX() >= area.getMinX() - 1
                                    && bounds.getMaxX() <= area.getMaxX() + 1
                                    && bounds.getMinY() >= area.getMinY() - 1
                                    && bounds.getMaxY() <= area.getMaxY() + 1,
                            action.getText() + " must remain inside the toolbar");
                }
                assertTrue(toolbar.getHeight() > 40, "the toolbar must wrap instead of squeezing labels");
                assertFalse(root.lookup("#sql-format").isDisabled());
                assertTrue(root.lookup("#sql-execute").isDisabled(), "layout must not admit an unbound execution");
                @SuppressWarnings("unchecked")
                ComboBox<TransactionMode> mode = (ComboBox<TransactionMode>) root.lookup("#sql-transaction-mode");
                assertNotNull(mode);
                assertFalse(mode.isEditable(), "display labels must not become free-form mode input");
                assertEquals(List.of(TransactionMode.AUTO_COMMIT, TransactionMode.MANUAL), mode.getItems());
                assertEquals(TransactionMode.AUTO_COMMIT, mode.getValue());
                assertTrue(mode.isDisabled(), "an unbound editor must not change transactions");
                assertEquals("自动提交", mode.getConverter().toString(TransactionMode.AUTO_COMMIT));
                assertEquals("手动提交", mode.getConverter().toString(TransactionMode.MANUAL));
                assertEquals("", mode.getConverter().toString(null));
                ListCell<?> displayed = (ListCell<?>) ((ComboBoxListViewSkin<?>) mode.getSkin()).getDisplayNode();
                assertEquals("自动提交", displayed.getText());
                assertTrue(displayed.getWidth() + 1 >= displayed.prefWidth(-1), "transaction label must fit");
                return null;
            });
        }
    }

    @Test
    void goToLineRebindingAndFindSwitchingPreserveFileAndEditorIsolation() throws Exception {
        String sql = "select 1;\nselect 2;\n";
        Path file = Files.writeString(directory.resolve("navigate.sql"), sql);
        try (var first = new Fixture(new SqlScriptFileStore().load(file)); var second = new Fixture(null)) {
            FxUiTestSupport.call(() -> {
                new Scene((Parent) second.pane.getNode(), 880, 800);
                second.pane.getNode().applyCss();
                Parent root = (Parent) first.pane.getNode();
                new Scene(root, 880, 800);
                root.applyCss(); root.layout();
                var editor = (org.fxmisc.richtext.CodeArea) root.lookup("#sql-editor");
                boolean undoBefore = editor.isUndoAvailable();
                editor.selectRange(9, 0);
                var input = (TextField) root.lookup("#sql-go-to-line-input");
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.G, false, true, false, false));
                assertTrue(root.lookup("#sql-go-to-line-bar").isVisible());
                assertEquals("1", input.getSelectedText());
                assertEquals("执行选中 (F5)", ((Button) root.lookup("#sql-execute")).getText());
                input.setText("2");
                input.fireEvent(new javafx.event.ActionEvent());
                assertEquals(10, editor.getCaretPosition());
                assertEquals(10, editor.getAnchor());
                assertEquals("行 2 · 列 1", ((Labeled) root.lookup("#sql-editor-position")).getText());
                assertEquals("执行全部 (F5)", ((Button) root.lookup("#sql-execute")).getText());
                assertFalse(root.lookup("#sql-go-to-line-bar").isManaged());
                first.shortcuts.apply(java.util.Map.of(ShortcutAction.SQL_GO_TO_LINE,
                        KeyCombination.keyCombination("Ctrl+L")));
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.G, false, true, false, false));
                assertFalse(root.lookup("#sql-go-to-line-bar").isVisible());
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.L, false, true, false, false));
                assertTrue(root.lookup("#sql-go-to-line-bar").isVisible());
                assertEquals("2", input.getText());
                // A real replacement panel must be hidden/cancelled when navigation opens, and vice versa.
                input.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.H, false, true, false, false));
                assertFalse(root.lookup("#sql-go-to-line-bar").isVisible());
                assertTrue(root.lookup("#sql-replace-pane").isVisible());
                ((Button) root.lookup("#sql-go-to-line")).fire();
                assertFalse(root.lookup("#sql-find-bar").isManaged());
                assertTrue(root.lookup("#sql-go-to-line-bar").isVisible());
                ((Button) root.lookup("#sql-find")).fire();
                assertFalse(root.lookup("#sql-go-to-line-bar").isManaged());
                assertTrue(root.lookup("#sql-find-bar").isVisible());
                assertEquals(10, editor.getCaretPosition());
                assertEquals(sql, editor.getText());
                assertEquals(undoBefore, editor.isUndoAvailable(), "navigation must preserve existing undo history");
                assertTrue(root.lookup("#sql-execute").isDisabled());
                assertFalse(second.pane.getNode().lookup("#sql-go-to-line-bar").isVisible());
                assertEquals("", ((org.fxmisc.richtext.CodeArea)
                        second.pane.getNode().lookup("#sql-editor")).getText());
                var controllerField = SqlEditorPane.class.getDeclaredField("fileController");
                controllerField.setAccessible(true);
                var documentField = SqlScriptFileController.class.getDeclaredField("document");
                documentField.setAccessible(true);
                assertFalse(((com.datacube.sqleditor.SqlScriptDocument)
                        documentField.get(controllerField.get(first.pane))).dirty());
                assertEquals(KeyCombination.keyCombination("Ctrl+L"), new ShortcutSettings(
                        directory.resolve("shortcuts.properties")).get(ShortcutAction.SQL_GO_TO_LINE));
                return null;
            });
        }
        assertEquals(sql, Files.readString(file));
    }

    @ParameterizedTest
    @CsvSource({"880, dark", "640, dark", "480, dark", "880, light", "640, light", "480, light"})
    void goToLineWrapsWithoutClippingOrCoveringSql(double width, String theme) throws Exception {
        try (var fixture = new Fixture(null)) {
            FxUiTestSupport.call(() -> {
                Parent root = (Parent) fixture.pane.getNode();
                Scene scene = new Scene(root, width, 850);
                scene.getStylesheets().addAll(
                        ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                root.resize(width, 850);
                root.applyCss(); root.layout();
                ((Button) root.lookup("#sql-go-to-line")).fire();
                ((TextField) root.lookup("#sql-go-to-line-input")).setText("999999999999999999999");
                root.applyCss(); root.layout();
                Region panel = (Region) root.lookup("#sql-go-to-line-bar");
                Bounds area = panel.localToScene(panel.getLayoutBounds());
                for (String id : List.of("input", "submit", "cancel", "status")) {
                    Region part = (Region) root.lookup("#sql-go-to-line-" + id);
                    Bounds bounds = part.localToScene(part.getLayoutBounds());
                    assertTrue(bounds.getMinX() >= area.getMinX() - 1 && bounds.getMaxX() <= area.getMaxX() + 1
                            && bounds.getMinY() >= area.getMinY() - 1 && bounds.getMaxY() <= area.getMaxY() + 1, id);
                }
                Region editor = (Region) root.lookup("#sql-editor");
                Bounds editable = editor.localToScene(editor.getLayoutBounds());
                assertTrue(editable.getMinY() >= area.getMaxY() - 1);
                assertTrue(editable.getHeight() >= 40);
                Region scope = (Region) root.lookup("#sql-editor-scope-bar");
                Bounds scopeBounds = scope.localToScene(scope.getLayoutBounds());
                assertTrue(scopeBounds.getMinY() >= editable.getMaxY() - 1);
                Bounds launcher = root.lookup("#sql-go-to-line").localToScene(
                        root.lookup("#sql-go-to-line").getLayoutBounds());
                assertTrue(launcher.getMaxX() <= scopeBounds.getMaxX() + 1
                        && launcher.getMaxY() <= scopeBounds.getMaxY() + 1);
                assertTrue(root.lookup("#sql-go-to-line-submit").isDisabled());
                return null;
            });
        }
    }

    private final class Fixture implements AutoCloseable {
        final FxTaskRunner runner = new FxTaskRunner();
        final ShortcutSettings shortcuts = new ShortcutSettings(directory.resolve("shortcuts.properties"));
        final SqlEditorPane pane;

        Fixture(SqlScriptFileStore.Loaded loaded) throws Exception {
            try {
                pane = FxUiTestSupport.call(() -> {
                    var editor = new SqlEditorPane(new SessionContext(), null, null,
                            new AppSettings(directory.resolve("settings.properties")),
                            (id, table) -> fail("must not open a designer"), null, null,
                            new SqlHistoryStore(directory.resolve("history.txt")),
                            shortcuts, runner);
                    editor.installSqlScriptFileController(loaded, new SqlScriptFileStore(),
                            new RecentSqlFiles(directory.resolve("recent.txt")), ignored -> { }, "SQL");
                    return editor;
                });
            } catch (Throwable failure) {
                runner.close();
                throw failure;
            }
        }

        @Override public void close() throws Exception {
            try {
                var closed = FxUiTestSupport.call(pane::requestClose);
                assertEquals(CloseGuardOutcome.APPROVED, closed.toCompletableFuture().get(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
            } finally {
                runner.close();
            }
        }
    }
}
