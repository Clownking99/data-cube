package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.sqleditor.SqlScriptDocument;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OpenTabsSqlEditorIntegrationTest {
    @TempDir Path directory;

    @Test void selectingExistingSqlTabKeepsDirtyFileUndoAndPassiveConnectionWithoutDatabaseWork() throws Exception {
        var probe = new DraftConnectionProbe();
        var runner = new FxTaskRunner();
        Path file = Files.writeString(directory.resolve("synthetic.sql"), "select 1;\r\n");
        var loaded = new SqlScriptFileStore().load(file);
        SqlEditorPane editorPane = FxUiTestSupport.call(() -> SqlEditorPane.openSqlFile(
                new SessionContext(), probe.manager, new ObjectTreeService(probe.manager),
                new AppSettings(directory.resolve("settings")), (id, table) -> fail("unexpected designer"),
                new SqlHistoryStore(directory.resolve("history")), new ShortcutSettings(directory.resolve("shortcuts")), runner));
        try {
            FxUiTestSupport.call(() -> {
                TabPane tabs = OpenTabsPaneTest.tabs("current");
                Tab sqlTab = new Tab("SQL synthetic.sql", editorPane.getNode());
                tabs.getTabs().add(sqlTab);
                editorPane.installSqlScriptFileController(loaded, new SqlScriptFileStore(),
                        new RecentSqlFiles(directory.resolve("recent")), sqlTab::setText, "SQL");
                new Scene(tabs, 900, 700); tabs.applyCss(); tabs.layout();
                CodeArea editor = (CodeArea) editorPane.getNode().lookup("#sql-editor");
                ConnConfig connection = new ConnConfig("demo", "Demo", DbType.POSTGRESQL,
                        "example.invalid", 1, "synthetic", "", "", Map.of());
                probe.manager.register(connection); assertTrue(editorPane.chooseFileConnection(connection));
                editor.getUndoManager().forgetHistory();
                editor.appendText("-- local edit"); editor.selectRange(8, 2);
                String text = editor.getText(); String title = sqlTab.getText();
                Object intent = TableSelectSqlTabsTest.field(editorPane, "recoveryIntent");
                SqlScriptDocument document = (SqlScriptDocument) TableSelectSqlTabsTest.field(
                        TableSelectSqlTabsTest.field(editorPane, "fileController"), "document");
                try (var picker = new OpenTabsPane(tabs, () -> true)) {
                    OpenTabsPaneTest.query(picker).setText("synthetic.sql");
                    assertSame(tabs.getTabs().getFirst(), tabs.getSelectionModel().getSelectedItem());
                    assertTrue(picker.activateSelected()); assertSame(sqlTab, tabs.getSelectionModel().getSelectedItem());
                    assertEquals(2, tabs.getTabs().size()); assertEquals(text, editor.getText());
                    assertEquals(title, sqlTab.getText()); assertTrue(document.dirty());
                    assertEquals(8, editor.getAnchor()); assertEquals(2, editor.getCaretPosition());
                    assertSame(intent, TableSelectSqlTabsTest.field(editorPane, "recoveryIntent"));
                    assertNull(TableSelectSqlTabsTest.field(editorPane, "jdbcSession"));
                    assertNull(((SqlEditorConnectionAdmission) TableSelectSqlTabsTest.field(editorPane, "admission")).pinned());
                    editor.undo(); assertFalse(document.dirty()); assertEquals("select 1;\r\n", document.physicalText());
                    assertFalse(editor.isUndoAvailable());
                }
                return null;
            });
            assertEquals("select 1;\r\n", Files.readString(file));
            assertFalse(Files.exists(directory.resolve("history")));
            assertEquals(0, probe.providers.get()); assertEquals(0, probe.sessions.get());
            assertEquals(0, probe.metadata.get()); assertEquals(0, probe.network.get());
        } finally {
            try { editorPane.closeResources(); FxUiTestSupport.call(() -> { editorPane.finalizeCloseOnFx(); return null; }); }
            finally { runner.close(); probe.manager.closeAll(); }
        }
    }
}
