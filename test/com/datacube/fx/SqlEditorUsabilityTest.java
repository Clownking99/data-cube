package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.RecentSqlFiles;
import com.datacube.config.ShortcutSettings;
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
                assertEquals(9, actions.size(), "all file, execution, editing and result actions remain present");
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

    private final class Fixture implements AutoCloseable {
        final FxTaskRunner runner = new FxTaskRunner();
        final SqlEditorPane pane;

        Fixture(SqlScriptFileStore.Loaded loaded) throws Exception {
            try {
                pane = FxUiTestSupport.call(() -> {
                    var editor = new SqlEditorPane(new SessionContext(), null, null,
                            new AppSettings(directory.resolve("settings.properties")),
                            (id, table) -> fail("must not open a designer"), null, null,
                            new SqlHistoryStore(directory.resolve("history.txt")),
                            new ShortcutSettings(directory.resolve("shortcuts.properties")), runner);
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
