package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxSerialTaskQueue;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.JdbcEditorSession;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.*;
import com.datacube.sqleditor.SqlScriptDocument;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextField;
import javafx.scene.input.*;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SqlFileConnectionTest {
    @TempDir Path directory;

    @Test void openedFileHasAnExplicitOfflineConnectionEntry() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                assertNotNull(f.pane.getNode().lookup("#sql-file-connection"),
                        "an isolated file tab needs its own connection selection entry");
                assertTrue(f.guidance().contains("选择脚本连接"));
                assertTrue(f.pane.getNode().lookup("#sql-execute").isDisabled());
                return null;
            });
            f.assertOffline();
            assertEquals(f.sql, Files.readString(f.path));
        }
    }

    @Test void confirmedChoiceAndAllPassiveEditorPathsStayOfflineAndLeaveTheFileClean() throws Exception {
        try (var f = new Fixture()) {
            ConnConfig pg = config("pg", DbType.POSTGRESQL, "同名连接");
            ConnConfig oracle = config("oracle", DbType.ORACLE, "同名连接");
            f.register(pg, oracle, config("redis", DbType.REDIS, "Redis"));
            FxUiTestSupport.call(() -> {
                SqlDraftManagerTest.respondToDialog(() -> f.chooser().fire(), dialog -> {
                    @SuppressWarnings("unchecked")
                    var choices = (ListView<SqlDraftConnectionChooser.Choice>) dialog.lookup("#sql-connection-list");
                    assertEquals(List.of(pg, oracle), choices.getItems().stream().map(SqlDraftConnectionChooser.Choice::config).toList());
                    assertNull(choices.getSelectionModel().getSelectedItem(), "there must be no implicit target");
                    assertTrue(dialog.lookupButton(ButtonType.OK).isDisabled());
                    assertNotEquals(choices.getItems().get(0).toString(), choices.getItems().get(1).toString());
                    assertFalse(choices.getItems().toString().contains("synthetic-secret"));
                    ((TextField) dialog.lookup("#sql-connection-query")).setText("oracle");
                    assertEquals(List.of(oracle), choices.getItems().stream().map(SqlDraftConnectionChooser.Choice::config).toList());
                    assertNull(choices.getSelectionModel().getSelectedItem());
                    choices.getSelectionModel().selectFirst();
                    ((Button) dialog.lookupButton(ButtonType.OK)).fire();
                });
                assertSame(oracle, invoke(f.pane, "currentConn"));
                assertNull(f.admission().pinned());
                assertNull(field(f.pane, "jdbcSession"));
                assertFalse(f.pane.getNode().lookup("#sql-execute").isDisabled());
                assertTrue(f.guidance().contains("尚未连接"));
                assertEquals("更换脚本连接", f.chooser().getText());
                f.context.setActiveConnection(pg);
                assertSame(oracle, invoke(f.pane, "currentConn"), "global/context selection must not replace explicit intent");
                f.schema().setText("  chosen_schema  ");
                f.area().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE, false, true, false, false));
                f.area().fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 10, 10, 10, 10,
                        MouseButton.PRIMARY, 1, false, true, false, false,
                        true, false, false, false, false, true, null));
                assertEquals(List.of(), invoke(f.pane, "membersFor", new Class<?>[]{String.class}, "a"));
                invoke(f.pane, "prewarm", new Class<?>[]{ConnConfig.class}, oracle);
                invoke(f.pane, "installMetadataPrewarm");
                invoke(f.pane, "loadColumnsAsync", new Class<?>[]{String.class, String.class, String.class, String.class},
                        "oracle", "chosen_schema", "synthetic", "chosen_schema.synthetic");
                assertEquals(f.sql, f.area().getText());
                assertFalse(f.document().dirty());
                assertEquals(f.path.toRealPath(), f.document().target().path());
                return null;
            });
            f.metadataBarrier();
            f.assertOffline();
            assertEquals(f.sql, Files.readString(f.path));
        }
    }

    @Test void emptyChoiceAndCancellationNeverSelectOrChangeTheExistingTarget() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                SqlDraftManagerTest.respondToDialog(() -> f.chooser().fire(), dialog -> {
                    assertTrue(dialog.getHeaderText().contains("没有可用连接"));
                    assertTrue(dialog.lookupButton(ButtonType.OK).isDisabled());
                    ((Button) dialog.lookupButton(ButtonType.CANCEL)).fire();
                });
                assertNull(invoke(f.pane, "currentConn"));
                return null;
            });
            ConnConfig first = config("first", DbType.POSTGRESQL, "first");
            ConnConfig second = config("second", DbType.ORACLE, "second");
            f.register(first, second);
            FxUiTestSupport.call(() -> {
                assertTrue(f.pane.chooseFileConnection(first));
                f.schema().setText("keep_schema");
                SqlDraftManagerTest.respondToDialog(() -> f.chooser().fire(), dialog -> {
                    ((ListView<?>) dialog.lookup("#sql-connection-list")).getSelectionModel().selectLast();
                    ((Button) dialog.lookupButton(ButtonType.CANCEL)).fire();
                });
                assertSame(first, invoke(f.pane, "currentConn"));
                assertEquals("keep_schema", f.schema().getText());
                assertEquals(f.sql, f.area().getText());
                assertFalse(f.document().dirty());
                assertTrue(f.pane.chooseFileConnection(second), "before admission an explicit replacement is allowed");
                assertSame(second, invoke(f.pane, "currentConn"));
                assertEquals("keep_schema", f.schema().getText());
                assertNull(f.admission().pinned());
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void deletedOrTypeChangedIntentCannotFallBackToSameNameOrGlobalConnection(boolean changedType) throws Exception {
        try (var f = new Fixture()) {
            ConnConfig chosen = config("chosen", DbType.POSTGRESQL, "same-name");
            ConnConfig other = config("other", DbType.POSTGRESQL, "same-name");
            f.register(chosen, other);
            FxUiTestSupport.call(() -> { assertTrue(f.pane.chooseFileConnection(chosen)); return null; });
            if (changedType) f.probe.manager.register(config("chosen", DbType.ORACLE, "same-name"));
            else f.probe.manager.unregister("chosen");
            FxUiTestSupport.call(() -> {
                f.context.setActiveConnection(other);
                assertFalse(f.pane.chooseFileConnection(chosen), "stale dialog choice must be revalidated");
                assertNull(invoke(f.pane, "currentConn"));
                assertThrows(IllegalStateException.class, () -> invoke(f.pane, "admitCurrentConnection"));
                assertNull(f.admission().pinned());
                ((Button) f.pane.getNode().lookup("#sql-execute")).fire();
                assertTrue(f.pane.getNode().lookup("#sql-execute").isDisabled());
                assertTrue(f.guidance().contains("选择脚本连接"));
                assertEquals(f.sql, f.area().getText());
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void removedTargetDuringTransactionModeSelectionPointsBackToScriptPicker() throws Exception {
        try (var f = new Fixture()) {
            ConnConfig chosen = config("chosen", DbType.POSTGRESQL, "chosen");
            f.register(chosen);
            FxUiTestSupport.call(() -> {
                assertTrue(f.pane.chooseFileConnection(chosen));
                f.probe.manager.unregister("chosen");
                @SuppressWarnings("unchecked")
                var modes = (ComboBox<JdbcEditorSession.TransactionMode>) field(f.pane, "transactionModeBox");
                assertFalse(modes.isDisabled());
                SqlDraftManagerTest.respondToDialog(
                        () -> modes.setValue(JdbcEditorSession.TransactionMode.MANUAL), dialog -> {
                            assertTrue(dialog.getContentText().contains("选择脚本连接"));
                            assertFalse(dialog.getContentText().contains("左侧"));
                            ((Button) dialog.lookupButton(ButtonType.OK)).fire();
                        });
                assertTrue(modes.isDisabled());
                assertTrue(f.guidance().contains("选择脚本连接"));
                assertNull(f.admission().pinned());
                assertNull(field(f.pane, "jdbcSession"));
                assertEquals(f.sql, f.area().getText());
                assertFalse(f.document().dirty());
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void freshMatchingConfigIsUsedAtAdmissionAndThePinnedTargetCannotBeSwitched() throws Exception {
        try (var f = new Fixture()) {
            ConnConfig chosen = config("chosen", DbType.POSTGRESQL, "before");
            f.register(chosen);
            ConnConfig updated = new ConnectionSafetyOptions(ConnectionEnvironment.PRODUCTION, true, 12)
                    .applyTo(config("chosen", DbType.POSTGRESQL, "renamed"));
            f.probe.manager.register(updated);
            FxUiTestSupport.call(() -> {
                assertTrue(f.pane.chooseFileConnection(chosen), "same stable ID/type should resolve fresh configuration");
                assertSame(updated, invoke(f.pane, "currentConn"));
                assertTrue(((Label) field(f.pane, "connectionBadge")).getText().contains("renamed"));
                assertEquals("环境: 生产", ((Label) field(f.pane, "environmentBadge")).getText());
                assertEquals("只读", ((Label) field(f.pane, "readOnlyBadge")).getText());
                return null;
            });
            f.assertOffline();
            FxUiTestSupport.call(() -> {
                assertSame(updated, invoke(f.pane, "admitCurrentConnection"));
                assertSame(updated, f.admission().pinned());
                assertTrue(f.chooser().isDisabled());
                assertEquals("脚本连接已锁定", f.chooser().getText());
                f.probe.manager.unregister("chosen");
                assertFalse(f.pane.chooseFileConnection(chosen));
                assertFalse(f.pane.chooseRecoveryConnection(chosen));
                assertSame(updated, invoke(f.pane, "currentConn"));
                JdbcEditorSession session = (JdbcEditorSession) invoke(f.pane, "ensureEditorSession");
                assertEquals("chosen", session.snapshot().connectionId());
                assertEquals(ConnectionSafetyOptions.from(updated), session.snapshot().safety());
                return null;
            });
            f.metadataBarrier();
            assertEquals(1, f.probe.sessions.get());
            assertEquals(0, f.probe.network.get());
        }
    }

    @Test void closedEditorRejectsAChoiceAlreadyOpenInTheDialog() throws Exception {
        try (var f = new Fixture()) {
            ConnConfig chosen = config("chosen", DbType.POSTGRESQL, "chosen");
            f.register(chosen);
            FxUiTestSupport.call(() -> {
                SqlDraftManagerTest.respondToDialog(() -> f.chooser().fire(), dialog -> {
                    ((ListView<?>) dialog.lookup("#sql-connection-list")).getSelectionModel().selectFirst();
                    f.pane.closeResources();
                    ((Button) dialog.lookupButton(ButtonType.OK)).fire();
                });
                assertNull(invoke(f.pane, "currentConn"));
                assertFalse(f.pane.chooseFileConnection(chosen));
                assertNull(f.admission().pinned());
                assertEquals(f.sql, f.area().getText());
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void invalidAndRedisChoicesAreRejectedWithoutCreatingAnIntent() throws Exception {
        try (var f = new Fixture()) {
            ConnConfig redis = config("redis", DbType.REDIS, "redis");
            f.register(redis);
            FxUiTestSupport.call(() -> {
                assertFalse(f.pane.chooseFileConnection(null));
                assertFalse(f.pane.chooseFileConnection(redis));
                assertFalse(f.pane.chooseFileConnection(config("missing", DbType.ORACLE, "missing")));
                assertNull(invoke(f.pane, "currentConn"));
                assertTrue(f.pane.getNode().lookup("#sql-execute").isDisabled());
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void selectedIdentitySurvivesInDraftCheckpointWithoutAddingFilePathsOrCredentials() throws Exception {
        try (var f = new Fixture()) {
            Queue<Runnable> writes = new ConcurrentLinkedQueue<>();
            var runtime = FxUiTestSupport.call(() -> new SqlDraftCoordinator(directory.resolve("drafts"),
                    writes::add, Platform::runLater, Platform::isFxApplicationThread, () -> 0L, () -> 100_000L));
            try {
                drain(writes);
                ConnConfig chosen = config("chosen", DbType.POSTGRESQL, "chosen");
                f.register(chosen);
                var binding = FxUiTestSupport.call(() -> f.pane.bindDraft(runtime, UUID.randomUUID(), null, ignored -> { }));
                FxUiTestSupport.call(() -> {
                    assertTrue(f.pane.chooseFileConnection(chosen));
                    f.probe.manager.unregister("chosen");
                    f.schema().setText("  raw_schema  ");
                    return null;
                });
                var saved = FxUiTestSupport.call(() -> ((SqlDraftCoordinator.Handle) field(binding, "handle")).flush());
                drain(writes);
                saved.get(5, TimeUnit.SECONDS);
                var refresh = FxUiTestSupport.call(runtime::refresh);
                drain(writes);
                SqlDraft draft = refresh.get(5, TimeUnit.SECONDS).snapshot().drafts().getFirst();
                assertEquals("chosen", draft.connectionId());
                assertEquals(DbType.POSTGRESQL, draft.connectionType());
                assertEquals("chosen", draft.connectionName());
                assertEquals(f.sql, draft.sql());
                assertEquals("  raw_schema  ", draft.schema());
                assertFalse(FxUiTestSupport.call(() -> f.document().dirty()));
                assertEquals(f.sql, Files.readString(f.path));
                f.assertOffline();
            } finally {
                f.pane.closeResources();
                var shutdown = FxUiTestSupport.call(runtime::shutdown);
                drain(writes);
                shutdown.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final FxTaskRunner runner = new FxTaskRunner();
        final SessionContext context = new SessionContext();
        final List<ConnConfig> choices = new ArrayList<>();
        final String sql = "select a. from synthetic a;\n";
        final Path path = directory.resolve("脚本.sql");
        final SqlEditorPane pane;
        Fixture() throws Exception {
            var loaded = new SqlScriptFileStore().load(Files.writeString(path, sql));
            AtomicReference<SqlEditorPane> created = new AtomicReference<>();
            FxUiTestSupport.call(() -> {
                context.setActiveConnection(config("global", DbType.POSTGRESQL, "must-not-bind"));
                ContentTabPane tabs = new ContentTabPane();
                assertTrue(AppShell.openLoadedSqlFile(tabs, loaded, context, probe.manager,
                        new ObjectTreeService(probe.manager), new AppSettings(directory.resolve("settings")),
                        (id, table) -> fail("must not open a designer"), new SqlHistoryStore(directory.resolve("history")),
                        new ShortcutSettings(directory.resolve("shortcuts")), runner, new SqlScriptFileStore(),
                        new RecentSqlFiles(directory.resolve("recent")), new AppShell.SqlFileDraftLifecycle() {
                            @Override public void bind(SqlEditorPane editor) { created.set(editor); }
                            @Override public void installed(javafx.scene.Node content) { }
                        }, new SqlFileTabRegistry(), () -> List.copyOf(choices)));
                new Scene((Parent) tabs.getNode(), 1000, 800);
                tabs.getNode().applyCss();
                ((Parent) tabs.getNode()).layout();
                return null;
            });
            pane = created.get();
        }
        String guidance() { return ((Label) pane.getNode().lookup("#sql-connection-guidance")).getText(); }
        Button chooser() { return (Button) pane.getNode().lookup("#sql-file-connection"); }
        CodeArea area() { return (CodeArea) pane.getNode().lookup("#sql-editor"); }
        TextField schema() { return (TextField) field(pane, "schemaField"); }
        SqlEditorConnectionAdmission admission() { return (SqlEditorConnectionAdmission) field(pane, "admission"); }
        SqlScriptDocument document() { return (SqlScriptDocument) field(field(pane, "fileController"), "document"); }
        void register(ConnConfig... configs) { choices.addAll(List.of(configs)); for (var config : configs) probe.manager.register(config); }
        void metadataBarrier() throws Exception {
            CountDownLatch done = new CountDownLatch(1);
            FxUiTestSupport.call(() -> {
                ((FxSerialTaskQueue) field(pane, "metadataTasks"))
                        .submit(() -> true, ignored -> done.countDown(), ignored -> done.countDown());
                return null;
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
        }
        void assertOffline() {
            assertEquals(0, probe.providers.get(), "provider resolution");
            assertEquals(0, probe.sessions.get(), "session construction");
            assertEquals(0, probe.metadata.get(), "metadata access");
            assertEquals(0, probe.network.get(), "network access");
        }
        @Override public void close() throws Exception {
            try {
                pane.closeResources();
                FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
            } finally { runner.close(); probe.manager.closeAll(); }
        }
    }

    private static ConnConfig config(String id, DbType type, String name) {
        return new ConnConfig(id, name, type, "synthetic.invalid", 1, "synthetic", "synthetic-user",
                "synthetic-secret", Map.of());
    }
    private static void drain(Queue<Runnable> writes) throws Exception {
        Runnable task;
        while ((task = writes.poll()) != null) task.run();
        FxUiTestSupport.call(() -> null);
    }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Object invoke(Object target, String name) throws Exception { return invoke(target, name, new Class<?>[0]); }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception failure) throw failure;
            if (wrapped.getCause() instanceof Error failure) throw failure;
            throw wrapped;
        }
    }
}
