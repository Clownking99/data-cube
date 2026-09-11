package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.*;
import com.datacube.sqleditor.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlEditorWrapIntegrationTest {
    @TempDir Path directory;

    @Test void wrappingKeepsPhysicalFileTextDirtyStateConnectionAndExecutionRangeUnchanged() throws Exception {
        String raw = "select '" + "中😀 long value ".repeat(30) + "';\r\nselect 2;\r-- tail\n";
        try (var first = new Fixture("first", raw); var second = new Fixture("second", "select 3;")) {
            FxUiTestSupport.call(() -> {
                ConnConfig selected = new ConnConfig("source", "Demo", DbType.POSTGRESQL,
                        "example.invalid", 1, "synthetic", "", "", Map.of());
                first.probe.manager.register(selected);
                assertTrue(first.pane.chooseFileConnection(selected));
                var intent = TableSelectSqlTabsTest.field(first.pane, "recoveryIntent");
                first.editor.getUndoManager().forgetHistory();
                String sql = first.editor.getText();
                int lineTwo = sql.indexOf('\n') + 1;
                first.editor.selectRange(lineTwo + 9, lineTwo);
                var resolve = SqlEditorPane.class.getDeclaredMethod("selectedOrAllSql");
                resolve.setAccessible(true);
                assertEquals("select 2;", resolve.invoke(first.pane));
                String title = first.title;
                first.wrap().fire();
                assertTrue(first.editor.isWrapText()); assertFalse(second.editor.isWrapText());
                assertEquals(sql, first.editor.getText());
                assertEquals(raw, first.document().physicalText());
                assertEquals(first.file.toRealPath(), first.document().target().path());
                assertFalse(first.document().dirty()); assertEquals(title, first.title);
                assertSame(intent, TableSelectSqlTabsTest.field(first.pane, "recoveryIntent"));
                assertNull(TableSelectSqlTabsTest.field(first.pane, "jdbcSession"));
                assertNull(((SqlEditorConnectionAdmission) TableSelectSqlTabsTest.field(first.pane, "admission")).pinned());
                assertEquals(lineTwo + 9, first.editor.getAnchor()); assertEquals(lineTwo, first.editor.getCaretPosition());
                assertEquals("select 2;", resolve.invoke(first.pane));
                assertEquals("执行选中 (F5)", ((Button) first.pane.getNode().lookup("#sql-execute")).getText());
                assertFalse(first.editor.isUndoAvailable());
                first.editor.appendText("-- edit");
                assertTrue(first.document().dirty());
                first.wrap().fire();
                assertTrue(first.document().dirty()); assertTrue(first.title.endsWith("*"));
                first.editor.undo();
                assertFalse(first.document().dirty()); assertEquals(raw, first.document().physicalText());
                assertFalse(first.editor.isUndoAvailable());
                return null;
            });
            assertEquals(raw, Files.readString(first.file));
            assertFalse(Files.exists(directory.resolve("first/history")));
            first.assertOffline(); second.assertOffline();
        }
    }

    @Test void goToLineWhileWrappedUsesLogicalLinesAndDoesNotEditTheFile() throws Exception {
        String raw = "select '" + "word ".repeat(100) + "';\nselect 2;\nselect 3;";
        try (var f = new Fixture("navigate", raw)) {
            FxUiTestSupport.call(() -> {
                f.wrap().fire();
                ((Button) f.pane.getNode().lookup("#sql-go-to-line")).fire();
                var input = (TextField) f.pane.getNode().lookup("#sql-go-to-line-input");
                input.setText("2");
                input.fireEvent(new javafx.event.ActionEvent());
                assertTrue(f.editor.isWrapText());
                assertEquals(raw.indexOf('\n') + 1, f.editor.getCaretPosition());
                assertEquals(f.editor.getCaretPosition(), f.editor.getAnchor());
                assertEquals("行 2 · 列 1", ((Label) f.pane.getNode().lookup("#sql-editor-position")).getText());
                assertEquals(raw, f.document().physicalText()); assertFalse(f.document().dirty());
                return null;
            });
            assertEquals(raw, Files.readString(f.file)); f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"admission", "resources", "tasks"})
    void closingEditorRejectsOldWrapAction(String closing) throws Exception {
        try (var f = new Fixture("close", "select 1;")) {
            FxUiTestSupport.call(() -> {
                f.wrap().fire(); assertTrue(f.editor.isWrapText());
                return null;
            });
            if (closing.equals("resources")) f.pane.closeResources();
            FxUiTestSupport.call(() -> {
                if (closing.equals("admission"))
                    ((SqlEditorConnectionAdmission) TableSelectSqlTabsTest.field(f.pane, "admission")).beginClosing();
                if (closing.equals("tasks"))
                    ((com.datacube.fx.task.FxTaskScope) TableSelectSqlTabsTest.field(f.pane, "tasks")).close();
                f.wrap().fire();
                assertTrue(f.editor.isWrapText()); assertTrue(f.wrap().isSelected());
                assertEquals("select 1;", f.editor.getText()); assertFalse(f.document().dirty());
                return null;
            });
            f.assertOffline();
        }
    }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final FxTaskRunner runner = new FxTaskRunner();
        final Path file;
        final SqlEditorPane pane;
        final CodeArea editor;
        String title;
        Fixture(String name, String sql) throws Exception {
            Path profile = Files.createDirectory(directory.resolve(name));
            file = Files.writeString(profile.resolve("synthetic.sql"), sql);
            var loaded = new SqlScriptFileStore().load(file);
            pane = FxUiTestSupport.call(() -> {
                var p = SqlEditorPane.openSqlFile(new SessionContext(), probe.manager, new ObjectTreeService(probe.manager),
                        new AppSettings(profile.resolve("settings")), (id, table) -> fail("unexpected designer"),
                        new SqlHistoryStore(profile.resolve("history")), new ShortcutSettings(profile.resolve("shortcuts")), runner);
                p.installSqlScriptFileController(loaded, new SqlScriptFileStore(),
                        new RecentSqlFiles(profile.resolve("recent")), value -> title = value, "SQL");
                Parent root = (Parent) p.getNode(); new Scene(root, 880, 800); root.applyCss(); root.layout();
                return p;
            });
            editor = FxUiTestSupport.call(() -> (CodeArea) pane.getNode().lookup("#sql-editor"));
        }
        CheckBox wrap() { return (CheckBox) pane.getNode().lookup("#sql-editor-wrap"); }
        SqlScriptDocument document() {
            return (SqlScriptDocument) TableSelectSqlTabsTest.field(TableSelectSqlTabsTest.field(pane, "fileController"), "document");
        }
        void assertOffline() {
            assertEquals(0, probe.providers.get()); assertEquals(0, probe.sessions.get());
            assertEquals(0, probe.metadata.get()); assertEquals(0, probe.network.get());
        }
        @Override public void close() throws Exception {
            try { pane.closeResources(); FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; }); }
            finally { runner.close(); probe.manager.closeAll(); }
        }
    }
}
