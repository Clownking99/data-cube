package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxSerialTaskQueue;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.*;
import com.datacube.sqleditor.SqlScriptDocument;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ListView;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlHistoryTabsTest {
    @TempDir Path directory;
    private static final SqlHistoryStore.Entry HISTORY = new SqlHistoryStore.Entry(1000, "same-name", " SALES ", "select '中😀';\nselect 2;");

    @Test void historyTabOffersAnExplicitConnectionEntry() throws Exception {
        try (Fixture f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.register(config("pg", DbType.POSTGRESQL), config("oracle", DbType.ORACLE));
                assertTrue(f.open(HISTORY));
                var pane = f.created.getFirst();
                assertNotNull(pane.getNode().lookup("#sql-file-connection"),
                        "isolated history tabs need an explicit connection entry to continue working");
                assertEquals(HISTORY.sql(), area(pane).getText()); assertEquals("SALES", schema(pane).getText());
                assertNull(intent(pane).resolve(f.probe.manager::config)); assertNull(admission(pane).pinned());
                assertNull(field(pane, "jdbcSession"));
                assertTrue(pane.getNode().lookup("#sql-execute").isDisabled());
                assertTrue(guidance(pane).contains("选择脚本连接"));
                assertNull(document(pane).target()); assertFalse(document(pane).dirty());
                assertEquals("SQL - 历史 - same-name", ((TabPane) f.tabs.getNode()).getTabs().getFirst().getText());
                ((SessionContext) field(pane, "session")).setActiveConnection(f.choices.getFirst());
                assertNull(intent(pane).resolve(f.probe.manager::config));
                return null;
            });
            f.metadataBarrier(); f.assertOffline();
        }
    }

    @ParameterizedTest @EnumSource(value=DbType.class, names={"POSTGRESQL", "ORACLE"})
    void explicitChoiceUsesStableIdentityAndStaysOfflineWithoutChangingHistory(DbType type) throws Exception {
        try (Fixture f = new Fixture()) {
            var selected = new ConnectionSafetyOptions(ConnectionEnvironment.PRODUCTION, true, 12).applyTo(config("selected", type));
            var other = config("other", type);
            f.history.recordStrict(HISTORY.connName(), HISTORY.schema(), HISTORY.sql());
            byte[] bytes = Files.readAllBytes(directory.resolve("history")); var before = f.history.recent();
            FxUiTestSupport.call(() -> {
                f.register(selected, other, config("redis", DbType.REDIS)); assertTrue(f.open(HISTORY));
                var pane = f.created.getFirst();
                SqlDraftManagerTest.respondToDialog(() -> chooser(pane).fire(), dialog -> {
                    var combo = (ListView<?>) dialog.lookup("#sql-connection-list");
                    assertEquals(2, combo.getItems().size()); assertNull(combo.getSelectionModel().getSelectedItem());
                    assertTrue(dialog.lookupButton(ButtonType.OK).isDisabled());
                    assertNotEquals(combo.getItems().get(0).toString(), combo.getItems().get(1).toString());
                    ((TextField) dialog.lookup("#sql-connection-query")).setText("selected");
                    assertEquals(1, combo.getItems().size()); assertNull(combo.getSelectionModel().getSelectedItem());
                    combo.getSelectionModel().selectFirst(); ((Button) dialog.lookupButton(ButtonType.OK)).fire();
                });
                assertSame(selected, intent(pane).resolve(f.probe.manager::config));
                assertEquals("更换脚本连接", chooser(pane).getText()); assertTrue(guidance(pane).contains("尚未连接"));
                assertEquals("环境: 生产", ((Label) field(pane, "environmentBadge")).getText());
                assertEquals("只读", ((Label) field(pane, "readOnlyBadge")).getText());
                assertFalse(pane.getNode().lookup("#sql-execute").isDisabled());
                ((SessionContext) field(pane, "session")).setActiveConnection(other);
                assertSame(selected, intent(pane).resolve(f.probe.manager::config));
                assertNull(admission(pane).pinned()); assertNull(field(pane, "jdbcSession"));
                assertEquals(HISTORY.sql(), area(pane).getText()); assertEquals("SALES", schema(pane).getText());
                assertNull(document(pane).target()); assertFalse(document(pane).dirty());
                assertEquals(before, f.history.recent());
                return null;
            });
            f.metadataBarrier(); f.assertOffline();
            assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("history")));
            assertFalse(Files.exists(directory.resolve("recent")));
        }
    }

    @Test void emptyChoicesAndCancelledReplacementKeepTextSchemaAndTarget() throws Exception {
        try (Fixture f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                assertTrue(f.open(HISTORY)); var pane = f.created.getFirst();
                SqlDraftManagerTest.respondToDialog(() -> chooser(pane).fire(), dialog -> {
                    assertTrue(dialog.getHeaderText().contains("没有可用连接"));
                    assertTrue(dialog.lookupButton(ButtonType.OK).isDisabled());
                    ((Button) dialog.lookupButton(ButtonType.CANCEL)).fire();
                });
                assertNull(intent(pane).resolve(f.probe.manager::config));
                var first = config("first", DbType.POSTGRESQL); var second = config("second", DbType.ORACLE);
                f.register(first, second); assertTrue(pane.chooseFileConnection(first));
                SqlDraftManagerTest.respondToDialog(() -> chooser(pane).fire(), dialog -> {
                    ((ListView<?>) dialog.lookup("#sql-connection-list")).getSelectionModel().selectLast();
                    ((Button) dialog.lookupButton(ButtonType.CANCEL)).fire();
                });
                assertSame(first, intent(pane).resolve(f.probe.manager::config));
                assertEquals(HISTORY.sql(), area(pane).getText()); assertEquals("SALES", schema(pane).getText());
                assertFalse(document(pane).dirty()); assertNull(document(pane).target());
                return null;
            });
            f.metadataBarrier(); f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void missingOrTypeChangedTargetNeverFallsBackToHistoryNameOrGlobalSelection(boolean changedType) throws Exception {
        try (Fixture f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                var first = config("first", DbType.POSTGRESQL); var other = config("other", DbType.POSTGRESQL);
                f.register(first, other); assertTrue(f.open(HISTORY)); var pane = f.created.getFirst();
                assertTrue(pane.chooseFileConnection(first));
                if (changedType) f.probe.manager.register(config("first", DbType.ORACLE));
                else f.probe.manager.unregister("first");
                ((SessionContext) field(pane, "session")).setActiveConnection(other);
                assertFalse(pane.chooseFileConnection(first)); assertNull(intent(pane).resolve(f.probe.manager::config));
                assertNull(admission(pane).pinned()); assertNull(field(pane, "jdbcSession"));
                assertEquals(HISTORY.sql(), area(pane).getText());
                assertTrue(pane.chooseFileConnection(other), "only a fresh explicit choice can replace the missing target");
                assertSame(other, intent(pane).resolve(f.probe.manager::config));
                return null;
            });
            f.metadataBarrier(); f.assertOffline();
        }
    }

    @Test void closingDuringSelectionRejectsTheLateConfirmedTarget() throws Exception {
        try (Fixture f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.register(config("target", DbType.POSTGRESQL)); assertTrue(f.open(HISTORY)); var pane = f.created.getFirst();
                SqlDraftManagerTest.respondToDialog(() -> chooser(pane).fire(), dialog -> {
                    ((ListView<?>) dialog.lookup("#sql-connection-list")).getSelectionModel().selectFirst();
                    pane.closeResources(); ((Button) dialog.lookupButton(ButtonType.OK)).fire();
                });
                assertNull(intent(pane).resolve(f.probe.manager::config)); assertNull(admission(pane).pinned());
                assertEquals(HISTORY.sql(), area(pane).getText());
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void editingAndChoiceReachDraftWithoutBindingAFileOrOverwritingAnotherHistoryTab() throws Exception {
        try (Fixture f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                var selected = config("chosen", DbType.ORACLE); f.register(selected);
                assertTrue(f.open(HISTORY)); var pane = f.created.getFirst();
                assertTrue(pane.chooseFileConnection(selected)); area(pane).appendText("\n-- changed"); schema(pane).setText("NEW_SCHEMA");
                assertTrue(document(pane).dirty()); assertNull(document(pane).target());
                assertTrue(((TabPane) f.tabs.getNode()).getTabs().getFirst().getText().contains("*"));
                assertTrue(f.open(new SqlHistoryStore.Entry(2000, null, null, "select 99;")));
                assertEquals(HISTORY.sql() + "\n-- changed", area(pane).getText());
                var second = f.created.getLast();
                assertEquals("SQL - 历史", ((TabPane) f.tabs.getNode()).getTabs().getLast().getText());
                assertNull(intent(second).resolve(f.probe.manager::config)); assertEquals("", schema(second).getText());
                assertEquals("select 99;", area(second).getText()); assertFalse(document(second).dirty());
                return null;
            });
            f.awaitDraftReady();
            var handle = FxUiTestSupport.call(() -> (SqlDraftCoordinator.Handle) field(field(f.created.getFirst(), "draftBinding"), "handle"));
            FxUiTestSupport.call(handle::flush).get(5, TimeUnit.SECONDS);
            var snapshot = FxUiTestSupport.call(() -> f.drafts.runtime().refresh()).get(5, TimeUnit.SECONDS).snapshot();
            var draft = snapshot.drafts().stream().filter(d -> d.sql().endsWith("-- changed")).findFirst().orElseThrow();
            assertEquals(HISTORY.sql() + "\n-- changed", draft.sql()); assertEquals("NEW_SCHEMA", draft.schema());
            assertEquals("chosen", draft.connectionId()); assertEquals(DbType.ORACLE, draft.connectionType());
            assertFalse(Files.exists(directory.resolve("recent"))); assertFalse(Files.exists(directory.resolve("history")));
            f.metadataBarrier(); f.assertOffline();
        }
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"   ", "\"Mixed Case\""})
    void schemaHintKeepsExistingNormalizationWithoutChoosingAConnection(String hint) throws Exception {
        try (Fixture f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                assertTrue(f.open(new SqlHistoryStore.Entry(1000, null, hint, "select 1;")));
                var pane = f.created.getFirst(); assertEquals(hint == null ? "" : hint.trim(), schema(pane).getText());
                assertNull(intent(pane).resolve(f.probe.manager::config)); assertNull(document(pane).target());
                return null;
            });
            f.metadataBarrier(); f.assertOffline();
        }
    }

    @Test void closedTabsRejectHistoryBeforeConstructingAnEditor() throws Exception {
        try (Fixture f = new Fixture()) {
            FxUiTestSupport.call(f.tabs::closeAllManagedTabs).toCompletableFuture().get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> { assertFalse(f.open(HISTORY)); assertTrue(f.created.isEmpty()); return null; });
            f.assertOffline();
        }
    }

    @ParameterizedTest @CsvSource({"600,DARK", "900,DARK", "600,LIGHT", "900,LIGHT"})
    void historyConnectionEntryAndGuidanceFitBothThemes(double width, AppSettings.Theme mode) throws Exception {
        try (Fixture f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                var stage = new Stage(); var settings = new AppSettings(directory.resolve("theme")); settings.setTheme(mode);
                var scene = ((TabPane) f.tabs.getNode()).getScene(); new ThemeManager(settings).applyTo(scene);
                stage.setScene(scene); stage.setWidth(width); stage.setHeight(780);
                try {
                    assertTrue(f.open(HISTORY)); stage.show(); var pane = f.created.getFirst();
                    f.tabs.getNode().applyCss(); ((TabPane) f.tabs.getNode()).layout();
                    var button = chooser(pane); assertEquals("选择脚本连接", button.getText());
                    assertTrue(button.getWidth() + 1 >= button.prefWidth(-1));
                    var guidance = (Label) pane.getNode().lookup("#sql-connection-guidance");
                    assertTrue(guidance.getText().contains("选择脚本连接"));
                    assertTrue(guidance.getHeight() + 1 >= guidance.prefHeight(guidance.getWidth()));
                    assertFalse(button.isDisabled()); assertTrue(pane.getNode().lookup("#sql-execute").isDisabled());
                } finally { stage.hide(); }
                return null;
            });
            f.metadataBarrier(); f.assertOffline();
        }
    }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final FxTaskRunner runner = new FxTaskRunner();
        final ContentTabPane tabs = FxUiTestSupport.call(ContentTabPane::new);
        final SqlDraftUi drafts = FxUiTestSupport.call(() -> new SqlDraftUi(directory.resolve("drafts"), tabs));
        final SqlFileTabRegistry registry = FxUiTestSupport.call(SqlFileTabRegistry::new);
        final List<SqlEditorPane> created = new ArrayList<>();
        final List<ConnConfig> choices = new ArrayList<>();
        final SqlHistoryStore history = new SqlHistoryStore(directory.resolve("history"));
        Fixture() throws Exception { FxUiTestSupport.call(() -> { new Scene((TabPane) tabs.getNode(), 1000, 800); return null; }); }
        boolean open(SqlHistoryStore.Entry entry) {
            return SqlHistoryTabs.open(tabs, entry, schema -> {
                SqlEditorPane pane = SqlEditorPane.openSqlHistory(new SessionContext(), probe.manager,
                        new ObjectTreeService(probe.manager), new AppSettings(directory.resolve("settings")),
                        (id, ref) -> fail("no designer"), schema, history,
                        new ShortcutSettings(directory.resolve("shortcuts")), runner);
                created.add(pane); return pane;
            }, () -> List.copyOf(choices), new SqlScriptFileStore(), new RecentSqlFiles(directory.resolve("recent")),
                    new AppShell.SqlFileDraftLifecycle() {
                        @Override public void bind(SqlEditorPane pane) { drafts.bind(pane); }
                        @Override public void installed(javafx.scene.Node content) { drafts.installed(content); }
                    }, registry);
        }
        void register(ConnConfig... configs) { for (var config : configs) { probe.manager.register(config); choices.add(config); } }
        void metadataBarrier() throws Exception {
            for (SqlEditorPane pane : created) {
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
                for (SqlEditorPane pane : created) {
                    pane.closeResources();
                    FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
                }
                drafts.closeFromBackground();
            } finally { runner.close(); probe.manager.closeAll(); }
        }
    }
    private static ConnConfig config(String id, DbType type) { return new ConnConfig(id, "same-name", type, "example.invalid", 1, "synthetic", "", "", Map.of()); }
    private static Object field(Object target, String name) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static CodeArea area(SqlEditorPane p) { return (CodeArea) field(p, "editorArea"); }
    private static TextField schema(SqlEditorPane p) { return (TextField) field(p, "schemaField"); }
    private static Button chooser(SqlEditorPane p) { return (Button) p.getNode().lookup("#sql-file-connection"); }
    private static String guidance(SqlEditorPane p) { return ((Label) p.getNode().lookup("#sql-connection-guidance")).getText(); }
    private static SqlDraftRecoveryIntent intent(SqlEditorPane p) { return (SqlDraftRecoveryIntent) field(p, "recoveryIntent"); }
    private static SqlEditorConnectionAdmission admission(SqlEditorPane p) { return (SqlEditorConnectionAdmission) field(p, "admission"); }
    private static SqlScriptDocument document(SqlEditorPane p) { return (SqlScriptDocument) field(field(p, "fileController"), "document"); }
}
