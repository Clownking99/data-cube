package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxSerialTaskQueue;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.*;
import com.datacube.sqleditor.SqlScriptDocument;
import com.datacube.sqleditor.SqlScriptFileStore;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class TableSelectSqlTabsTest {
    @TempDir Path directory;

    @ParameterizedTest @EnumSource(value = DbType.class, names = {"POSTGRESQL", "ORACLE"})
    void generatedTabKeepsExactPassiveConnectionAndQualifiedTextThroughEditingAndDraftCheckpoint(DbType type) throws Exception {
        try (Fixture f = new Fixture()) {
            ConnConfig selected = config("selected", type, "Same name");
            ConnConfig other = config("other", type, "Same name");
            FxUiTestSupport.call(() -> {
                f.probe.manager.register(selected); f.probe.manager.register(other);
                assertTrue(f.open(selected, new TableRef(" Sales ", "Order\"Line")));
                SqlEditorPane pane = f.created.getFirst();
                assertEquals("SELECT *\nFROM \" Sales \".\"Order\"\"Line\";", area(pane).getText());
                assertEquals("", ((TextField) field(pane, "schemaField")).getText(), "no implicit schema command");
                assertEquals(selected, intent(pane).resolve(f.probe.manager::config));
                assertNull(field(pane, "jdbcSession"));
                assertNull(((SqlEditorConnectionAdmission) field(pane, "admission")).pinned());
                assertTrue(((Label) pane.getNode().lookup("#sql-connection-guidance")).getText().contains("未连接"));
                assertNotNull(pane.getNode().lookup("#sql-file-connection"));
                SqlScriptDocument document = document(pane);
                assertNull(document.target()); assertFalse(document.dirty());
                String firstText = area(pane).getText();
                ((SessionContext) field(pane, "session")).setActiveConnection(other);
                assertEquals(selected, intent(pane).resolve(f.probe.manager::config), "global selection cannot retarget template");
                assertTrue(f.open(other, new TableRef("public", "other")));
                assertEquals(firstText, area(pane).getText(), "a new query must not overwrite the previous tab");
                area(pane).appendText("\n-- reviewed");
                assertTrue(document.dirty());
                assertTrue(((TabPane) f.tabs.getNode()).getTabs().getFirst().getText().contains("*"));
                assertFalse(((Button) field(pane, "saveSqlFileBtn")).isDisabled());
                return null;
            });
            f.metadataBarrier();
            var handle = FxUiTestSupport.call(() -> (SqlDraftCoordinator.Handle)
                    field(field(f.created.getFirst(), "draftBinding"), "handle"));
            FxUiTestSupport.call(handle::flush).get(5, TimeUnit.SECONDS);
            var snapshot = FxUiTestSupport.call(() -> f.drafts.runtime().refresh()).get(5, TimeUnit.SECONDS).snapshot();
            assertTrue(snapshot.drafts().stream().anyMatch(d -> d.sql().endsWith("-- reviewed")
                    && d.connectionId().equals("selected") && d.connectionType() == type));
            assertFalse(Files.exists(directory.resolve("history")), "generation is not SQL execution history");
            assertFalse(Files.exists(directory.resolve("recent")), "no SQL file saved or opened");
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"removed", "changed-type", "changed-target", "redis", "invalid-name"})
    void invalidOrStaleTargetCannotConstructAnEditorOrInstallATab(String invalid) throws Exception {
        try (Fixture f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                ConnConfig selected = config("id", invalid.equals("redis") ? DbType.REDIS : DbType.POSTGRESQL, "name");
                if (!invalid.equals("removed")) f.probe.manager.register(selected);
                if (invalid.equals("changed-type")) f.probe.manager.register(config("id", DbType.ORACLE, "name"));
                if (invalid.equals("changed-target")) f.probe.manager.register(new ConnConfig("id", "name", DbType.POSTGRESQL,
                        "different.invalid", 1, "db", "", "", Map.of()));
                TableRef table = new TableRef("public", invalid.equals("invalid-name") ? "line\nbreak" : "t");
                assertThrows(IllegalArgumentException.class, () -> f.open(selected, table));
                assertTrue(f.created.isEmpty());
                assertTrue(((TabPane) f.tabs.getNode()).getTabs().isEmpty());
                f.assertOffline();
                return null;
            });
        }
    }

    @Test void closedTabRegistryRejectsBeforeConstructingThePassiveEditor() throws Exception {
        try (Fixture f = new Fixture()) {
            FxUiTestSupport.call(() -> f.tabs.closeAllManagedTabs()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> {
                ConnConfig selected = config("id", DbType.POSTGRESQL, "name");
                f.probe.manager.register(selected);
                assertFalse(f.open(selected, new TableRef("s", "t")));
                assertTrue(f.created.isEmpty());
                f.assertOffline();
                return null;
            });
        }
    }

    static ConnConfig config(String id, DbType type, String name) {
        return new ConnConfig(id, name, type, "example.invalid", 1, "synthetic", "", "", Map.of());
    }
    static Object field(Object target, String name) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static CodeArea area(SqlEditorPane pane) { return (CodeArea) pane.getNode().lookup("#sql-editor"); }
    private static SqlDraftRecoveryIntent intent(SqlEditorPane pane) { return (SqlDraftRecoveryIntent) field(pane, "recoveryIntent"); }
    private static SqlScriptDocument document(SqlEditorPane pane) { return (SqlScriptDocument) field(field(pane, "fileController"), "document"); }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final FxTaskRunner runner = new FxTaskRunner();
        final ContentTabPane tabs = FxUiTestSupport.call(ContentTabPane::new);
        final SqlDraftUi drafts = FxUiTestSupport.call(() -> new SqlDraftUi(directory.resolve("drafts"), tabs));
        final SqlFileTabRegistry registry = FxUiTestSupport.call(SqlFileTabRegistry::new);
        final List<SqlEditorPane> created = new ArrayList<>();
        Fixture() throws Exception { FxUiTestSupport.call(() -> { new Scene((TabPane) tabs.getNode(), 1000, 800); return null; }); }
        boolean open(ConnConfig connection, TableRef table) {
            boolean opened = TableSelectSqlTabs.open(tabs, connection, table, probe.manager, () -> {
                SqlEditorPane pane = SqlEditorPane.openSqlFile(new SessionContext(), probe.manager,
                        new ObjectTreeService(probe.manager), new AppSettings(directory.resolve("settings")),
                        (id, ref) -> fail("no designer"), new SqlHistoryStore(directory.resolve("history")),
                        new ShortcutSettings(directory.resolve("shortcuts")), runner);
                created.add(pane); return pane;
            }, () -> List.of(connection), new SqlScriptFileStore(), new RecentSqlFiles(directory.resolve("recent")),
                    new AppShell.SqlFileDraftLifecycle() {
                        @Override public void bind(SqlEditorPane pane) { drafts.bind(pane); }
                        @Override public void installed(javafx.scene.Node content) { drafts.installed(content); }
                    }, registry);
            tabs.getNode().applyCss();
            ((TabPane) tabs.getNode()).layout();
            return opened;
        }
        void metadataBarrier() throws Exception {
            for (SqlEditorPane pane : created) {
                CountDownLatch done = new CountDownLatch(1);
                FxUiTestSupport.call(() -> { ((FxSerialTaskQueue) field(pane, "metadataTasks"))
                        .submit(() -> true, ignored -> done.countDown(), ignored -> done.countDown()); return null; });
                assertTrue(done.await(5, TimeUnit.SECONDS));
            }
        }
        void assertOffline() {
            assertEquals(0, probe.providers.get()); assertEquals(0, probe.sessions.get());
            assertEquals(0, probe.metadata.get()); assertEquals(0, probe.network.get());
        }
        @Override public void close() throws Exception {
            try {
                for (SqlEditorPane pane : created) {
                    pane.closeResources();
                    FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
                }
                drafts.closeFromBackground();
            } finally { runner.close(); probe.manager.closeAll(); }
        }
    }
}
