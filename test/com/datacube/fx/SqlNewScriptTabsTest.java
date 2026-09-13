package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxSerialTaskQueue;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.*;
import com.datacube.sqleditor.SqlScriptDocument;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Scene;
import javafx.scene.control.*;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlNewScriptTabsTest {
    @TempDir Path directory;

    @ParameterizedTest @NullSource @EnumSource(DbType.class)
    void newScriptIsBlankCleanAndNeverUsesTheAmbientConnection(DbType type) throws Exception {
        try (Fixture f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                if (type != null) { var active = config("ambient", type); f.probe.manager.register(active); f.context.setActiveConnection(active); }
                assertTrue(f.open()); var pane = f.created.getFirst();
                assertEquals("", area(pane).getText()); assertEquals("", schema(pane).getText());
                assertEquals("SQL - 新脚本", f.tabPane().getTabs().getFirst().getText());
                assertFalse(document(pane).dirty()); assertNull(document(pane).target());
                assertEquals("query.sql", pane.createSqlSaveChooser().getInitialFileName());
                assertNull(pane.createSqlSaveChooser().getInitialDirectory());
                assertNull(intent(pane).resolve(f.probe.manager::config)); assertNull(field(pane, "jdbcSession"));
                assertTrue(pane.getNode().lookup("#sql-execute").isDisabled());
                assertEquals("选择脚本连接", chooser(pane).getText()); assertFalse(chooser(pane).isDisabled());
                assertTrue(((Label) pane.getNode().lookup("#sql-connection-guidance")).getText().contains("选择脚本连接"));
                assertEquals(0, f.choiceReads.get(), "opening must not even enumerate connection choices");
                assertEquals(1, f.bound.get()); assertEquals(1, f.installed.get());
                return null;
            });
            f.metadataBarrier(); f.assertOffline();
            assertFalse(Files.exists(directory.resolve("history"))); assertFalse(Files.exists(directory.resolve("recent")));
        }
    }

    @ParameterizedTest @EnumSource(value=DbType.class, names={"POSTGRESQL", "ORACLE"})
    void explicitSelectionAndCancelledReplacementKeepTheNewScriptOffline(DbType type) throws Exception {
        try (Fixture f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                var chosen = config("chosen", type); var other = config("other", type);
                f.register(chosen, other); assertTrue(f.open()); var pane = f.created.getFirst();
                area(pane).replaceText("select 'new 中😀';"); schema(pane).setText("EDIT_SCHEMA");
                SqlDraftManagerTest.respondToDialog(() -> chooser(pane).fire(), dialog -> {
                    var list = (ListView<?>) dialog.lookup("#sql-connection-list");
                    assertNull(list.getSelectionModel().getSelectedItem());
                    ((TextField) dialog.lookup("#sql-connection-query")).setText("chosen");
                    assertEquals(1, list.getItems().size()); assertTrue(dialog.lookupButton(ButtonType.OK).isDisabled());
                    list.getSelectionModel().selectFirst(); ((Button) dialog.lookupButton(ButtonType.OK)).fire();
                });
                assertSame(chosen, intent(pane).resolve(f.probe.manager::config));
                f.context.setActiveConnection(other);
                assertSame(chosen, intent(pane).resolve(f.probe.manager::config));
                SqlDraftManagerTest.respondToDialog(() -> chooser(pane).fire(), dialog -> {
                    ((ListView<?>) dialog.lookup("#sql-connection-list")).getSelectionModel().selectLast();
                    ((Button) dialog.lookupButton(ButtonType.CANCEL)).fire();
                });
                assertSame(chosen, intent(pane).resolve(f.probe.manager::config));
                assertEquals("select 'new 中😀';", area(pane).getText()); assertEquals("EDIT_SCHEMA", schema(pane).getText());
                assertNull(document(pane).target()); assertTrue(document(pane).dirty());
                assertFalse(pane.getNode().lookup("#sql-execute").isDisabled());
                assertNull(((SqlEditorConnectionAdmission) field(pane, "admission")).pinned());
                return null;
            });
            f.metadataBarrier(); f.assertOffline();
            assertFalse(Files.exists(directory.resolve("history"))); assertFalse(Files.exists(directory.resolve("recent")));
        }
    }

    @Test void anotherNewScriptKeepsExistingEditsAndTheirDraftIdentity() throws Exception {
        try (Fixture f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                assertTrue(f.open()); var first = f.created.getFirst();
                area(first).replaceText("select 'keep';\n-- unsaved"); schema(first).setText("FIRST_SCHEMA");
                var chosen = config("draft-target", DbType.ORACLE); f.register(chosen); assertTrue(first.chooseFileConnection(chosen));
                assertTrue(f.open()); var second = f.created.getLast();
                assertNotSame(first, second); assertEquals(2, f.tabPane().getTabs().size());
                assertEquals(1, f.tabPane().getSelectionModel().getSelectedIndex());
                assertEquals("select 'keep';\n-- unsaved", area(first).getText());
                assertTrue(f.tabPane().getTabs().getFirst().getText().contains("*"));
                assertEquals("", area(second).getText()); assertEquals("", schema(second).getText());
                assertFalse(document(second).dirty()); assertNull(intent(second).resolve(f.probe.manager::config));
                assertNotSame(field(first, "draftBinding"), field(second, "draftBinding"));
                return null;
            });
            f.awaitDraftReady();
            var handle = FxUiTestSupport.call(() -> (SqlDraftCoordinator.Handle) field(field(f.created.getFirst(), "draftBinding"), "handle"));
            FxUiTestSupport.call(handle::flush).get(5, TimeUnit.SECONDS);
            var snapshot = FxUiTestSupport.call(() -> f.drafts.runtime().refresh()).get(5, TimeUnit.SECONDS).snapshot();
            assertEquals(1, snapshot.drafts().size(), "empty new tabs must not become nonempty recovery records");
            var draft = snapshot.drafts().getFirst();
            assertEquals("select 'keep';\n-- unsaved", draft.sql()); assertEquals("FIRST_SCHEMA", draft.schema());
            assertEquals("draft-target", draft.connectionId()); assertEquals(DbType.ORACLE, draft.connectionType());
            f.metadataBarrier(); f.assertOffline();
        }
    }

    @Test void cancelCloseKeepsUnsavedTextAndAllowsFurtherEditing() throws Exception {
        try (Fixture f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                assertTrue(f.open()); var pane = f.created.getFirst(); area(pane).replaceText("select 'unsaved';");
                var close = new AtomicReference<java.util.concurrent.CompletionStage<CloseGuardOutcome>>();
                SqlDraftManagerTest.respondToDialog(() -> close.set(pane.requestClose()), dialog -> {
                    assertTrue(dialog.getContentText().contains("未保存"));
                    var cancel = dialog.getButtonTypes().stream()
                            .filter(b -> b.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
                    ((Button) dialog.lookupButton(cancel)).fire();
                });
                assertEquals(CloseGuardOutcome.REJECTED, close.get().toCompletableFuture().join());
                assertEquals(1, f.tabPane().getTabs().size()); assertTrue(document(pane).dirty()); assertNull(document(pane).target());
                area(pane).appendText("\n-- still editable");
                assertEquals("select 'unsaved';\n-- still editable", area(pane).getText());
                assertFalse(chooser(pane).isDisabled());
                return null;
            });
            f.metadataBarrier(); f.assertOffline();
        }
    }

    @Test void closedWorkspaceRejectsNewScriptBeforeConstructingOrBindingAnEditor() throws Exception {
        try (Fixture f = new Fixture()) {
            FxUiTestSupport.call(f.tabs::closeAllManagedTabs).toCompletableFuture().get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> {
                assertFalse(f.open()); assertTrue(f.created.isEmpty()); assertEquals(0, f.bound.get());
                assertEquals(0, f.installed.get()); assertTrue(f.tabPane().getTabs().isEmpty()); return null;
            });
            f.assertOffline();
        }
    }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final FxTaskRunner runner = new FxTaskRunner();
        final ContentTabPane tabs = FxUiTestSupport.call(ContentTabPane::new);
        final SqlDraftUi drafts = FxUiTestSupport.call(() -> new SqlDraftUi(directory.resolve("drafts"), tabs));
        final SqlFileTabRegistry registry = FxUiTestSupport.call(SqlFileTabRegistry::new);
        final SessionContext context = new SessionContext();
        final List<SqlEditorPane> created = new ArrayList<>(); final List<ConnConfig> choices = new ArrayList<>();
        final AtomicInteger bound = new AtomicInteger(), installed = new AtomicInteger(), choiceReads = new AtomicInteger();
        Fixture() throws Exception { FxUiTestSupport.call(() -> { new Scene(tabPane(), 1000, 800); return null; }); }
        TabPane tabPane() { return (TabPane) tabs.getNode(); }
        boolean open() {
            return SqlNewScriptTabs.open(tabs, () -> {
                var pane = SqlEditorPane.openSqlFile(context, probe.manager, new ObjectTreeService(probe.manager),
                        new AppSettings(directory.resolve("settings")), (id, ref) -> fail("no designer"),
                        new SqlHistoryStore(directory.resolve("history")), new ShortcutSettings(directory.resolve("shortcuts")), runner);
                created.add(pane); return pane;
            }, () -> { choiceReads.incrementAndGet(); return List.copyOf(choices); }, new SqlScriptFileStore(),
                    new RecentSqlFiles(directory.resolve("recent")), new AppShell.SqlFileDraftLifecycle() {
                        @Override public void bind(SqlEditorPane pane) {
                            assertNotNull(field(pane, "fileController")); assertNotNull(chooser(pane));
                            assertNull(document(pane).target()); assertFalse(document(pane).dirty());
                            drafts.bind(pane); bound.incrementAndGet();
                        }
                        @Override public void installed(javafx.scene.Node content) { drafts.installed(content); installed.incrementAndGet(); }
                    }, registry);
        }
        void register(ConnConfig... configs) { for (var c : configs) { probe.manager.register(c); choices.add(c); } }
        void metadataBarrier() throws Exception {
            for (var pane : created) {
                var done = new CountDownLatch(1);
                FxUiTestSupport.call(() -> { ((FxSerialTaskQueue) field(pane, "metadataTasks")).submit(() -> true,
                        ignored -> done.countDown(), ignored -> done.countDown()); return null; });
                assertTrue(done.await(5, TimeUnit.SECONDS));
            }
        }
        void awaitDraftReady() throws Exception {
            var ready = new CountDownLatch(1);
            AutoCloseable observer = FxUiTestSupport.call(() -> {
                Runnable check = () -> { if (!drafts.runtime().managementPending()) ready.countDown(); };
                var subscription = drafts.observe(check); check.run(); return subscription;
            });
            try { assertTrue(ready.await(5, TimeUnit.SECONDS)); }
            finally { FxUiTestSupport.call(() -> { observer.close(); return null; }); }
        }
        void assertOffline() {
            assertEquals(0, probe.providers.get()); assertEquals(0, probe.sessions.get());
            assertEquals(0, probe.metadata.get()); assertEquals(0, probe.network.get());
        }
        @Override public void close() throws Exception {
            try {
                for (var pane : created) { pane.closeResources(); FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; }); }
                drafts.closeFromBackground();
            } finally { runner.close(); probe.manager.closeAll(); }
        }
    }
    private static ConnConfig config(String id, DbType type) { return new ConnConfig(id, "同名连接", type, "example.invalid", 1, "synthetic", "", "", Map.of()); }
    private static Object field(Object target, String name) {
        try { var f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static CodeArea area(SqlEditorPane pane) { return (CodeArea) field(pane, "editorArea"); }
    private static TextField schema(SqlEditorPane pane) { return (TextField) field(pane, "schemaField"); }
    private static Button chooser(SqlEditorPane pane) { return (Button) pane.getNode().lookup("#sql-file-connection"); }
    private static SqlDraftRecoveryIntent intent(SqlEditorPane pane) { return (SqlDraftRecoveryIntent) field(pane, "recoveryIntent"); }
    private static SqlScriptDocument document(SqlEditorPane pane) { return (SqlScriptDocument) field(field(pane, "fileController"), "document"); }
}
