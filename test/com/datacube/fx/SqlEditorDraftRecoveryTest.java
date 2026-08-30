package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxSerialTaskQueue;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.*;
import com.datacube.spi.model.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlEditorDraftRecoveryTest {
    @TempDir Path directory;

    @Test
    void matchingRestoreKeepsExactTextAndAllPassivePathsOffline() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.fx(() -> {
                assertEquals("select a.\nfrom synthetic a;\n", f.area().getText());
                assertEquals("  raw_schema  ", ((TextField) field(f.pane, "schemaField")).getText());
                assertNull(field(f.pane, "jdbcSession"));
                assertNull(((SqlEditorConnectionAdmission) field(f.pane, "admission")).pinned());
                f.context.setActiveConnection(config("other", DbType.ORACLE, "saved-name"));
                f.pane.setSqlText("select a. from synthetic a");
                Event.fireEvent(f.area(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE,
                        false, true, false, false));
                Event.fireEvent(f.area(), new MouseEvent(MouseEvent.MOUSE_CLICKED, 10, 10, 10, 10,
                        MouseButton.PRIMARY, 1, false, true, false, false,
                        true, false, false, false, false, true, null));
                assertEquals(List.of(), invoke(f.pane, "membersFor", new Class<?>[]{String.class}, "a"));
                invoke(f.pane, "prewarm", new Class<?>[]{ConnConfig.class}, f.saved);
                invoke(f.pane, "installMetadataPrewarm", new Class<?>[0]);
                invoke(f.pane, "loadColumnsAsync",
                        new Class<?>[]{String.class, String.class, String.class, String.class},
                        "saved", "raw_schema", "synthetic", "raw_schema.synthetic");
            });
            f.metadataBarrier();
            f.assertOffline();
            var close = f.call(() -> f.pane.requestMandatoryClose().toCompletableFuture());
            assertEquals(CloseGuardOutcome.APPROVED, close.get(5, TimeUnit.SECONDS));
            f.assertOffline();
        }
    }

    @Test
    void deletedIntentCannotFallBackToGlobalOrSameName() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.probe.manager.unregister("saved");
            ConnConfig other = config("different-id", DbType.POSTGRESQL, "saved-name");
            f.probe.manager.register(other);
            f.fx(() -> {
                f.context.setActiveConnection(other);
                assertNull(invoke(f.pane, "currentConn", new Class<?>[0]));
                assertThrows(IllegalStateException.class,
                        () -> invoke(f.pane, "admitCurrentConnection", new Class<?>[0]));
                assertNull(((SqlEditorConnectionAdmission) field(f.pane, "admission")).pinned());
            });
            f.assertOffline();
        }
    }

    @Test
    void changedTypeIsRejectedButCurrentMatchingSnapshotIsAdmitted() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.probe.manager.register(config("saved", DbType.ORACLE, "saved-name"));
            f.fx(() -> assertThrows(IllegalStateException.class,
                    () -> invoke(f.pane, "admitCurrentConnection", new Class<?>[0])));
            ConnConfig updated = config("saved", DbType.POSTGRESQL, "renamed");
            f.probe.manager.register(updated);
            assertSame(updated, f.call(() -> invoke(f.pane, "admitCurrentConnection", new Class<?>[0])));
            f.probe.manager.unregister("saved");
            f.fx(() -> {
                f.context.setActiveConnection(config("other", DbType.ORACLE, "other"));
                assertSame(updated, invoke(f.pane, "currentConn", new Class<?>[0]));
                assertSame(updated, invoke(f.pane, "admitCurrentConnection", new Class<?>[0]));
                assertFalse(f.pane.chooseRecoveryConnection(f.saved));
            });
            f.metadataBarrier();
            assertEquals(0, f.probe.sessions.get());
            JdbcEditorSession session = (JdbcEditorSession) invoke(f.pane, "ensureEditorSession", new Class<?>[0]);
            assertNotNull(session);
            assertEquals("saved", session.snapshot().connectionId());
            assertEquals(1, f.probe.sessions.get());
            assertEquals(0, f.probe.network.get());
        }
    }

    @Test
    void explicitReplacementIsIntentOnlyAndIsRevalidated() throws Exception {
        try (Fixture f = new Fixture(false)) {
            ConnConfig replacement = config("replacement", DbType.ORACLE, "chosen");
            f.probe.manager.register(replacement);
            f.fx(() -> {
                assertFalse(f.pane.chooseRecoveryConnection(config("redis", DbType.REDIS, "redis")));
                assertTrue(f.pane.chooseRecoveryConnection(replacement));
                assertSame(replacement, invoke(f.pane, "currentConn", new Class<?>[0]));
                assertNull(((SqlEditorConnectionAdmission) field(f.pane, "admission")).pinned());
            });
            f.assertOffline();
            f.probe.manager.unregister("replacement");
            f.fx(() -> assertThrows(IllegalStateException.class,
                    () -> invoke(f.pane, "admitCurrentConnection", new Class<?>[0])));
            f.assertOffline();
        }
    }

    @Test
    void savingEditedMissingTargetRetainsOriginalIdentityAndRawSchema() throws Exception {
        try (Fixture f = new Fixture(false)) {
            SqlDraft saved = saveAfterEdit(f, () -> f.pane.setSqlText("  edited\n"));
            assertEquals(f.draft.id(), saved.id());
            assertEquals("saved", saved.connectionId());
            assertEquals(DbType.POSTGRESQL, saved.connectionType());
            assertEquals("saved-name", saved.connectionName());
            assertEquals("  raw_schema  ", saved.schema());
            assertEquals("  edited\n", saved.sql());
            f.assertOffline();
        }
    }

    @Test
    void schemaOnlyEditPreservesRecoveredOriginalLineEndings() throws Exception {
        try (Fixture f = new Fixture(false)) {
            SqlDraft saved = saveAfterEdit(f,
                    () -> ((TextField) field(f.pane, "schemaField")).setText("  next_schema  "));
            assertEquals(f.draft.sql(), saved.sql());
            assertEquals("  next_schema  ", saved.schema());
            f.assertOffline();
        }
    }

    private SqlDraft saveAfterEdit(Fixture f, Action change) throws Exception {
        Queue<Runnable> writes = new ConcurrentLinkedQueue<>();
        AtomicLong time = new AtomicLong();
        SqlDraftCoordinator runtime = f.call(() -> new SqlDraftCoordinator(directory.resolve("drafts"),
                writes::add, Platform::runLater, Platform::isFxApplicationThread, time::get, () -> 100_000L));
        try {
            drain(writes);
            f.fx(() -> {
                f.pane.bindDraft(runtime, f.draft.id(), f.draft.modifiedAt(), ignored -> {});
                change.run();
            });
            time.set(1000);
            f.fx(runtime::pulse);
            drain(writes);
            var refresh = f.call(runtime::refresh);
            drain(writes);
            return refresh.get(5, TimeUnit.SECONDS).snapshot().drafts().getFirst();
        } finally {
            f.pane.closeResources();
            var closed = f.call(runtime::shutdown);
            drain(writes);
            closed.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void normalBoundConstructorStillOwnsItsEagerSession() throws Exception {
        DraftConnectionProbe probe = new DraftConnectionProbe();
        ConnConfig saved = config("normal", DbType.POSTGRESQL, "normal");
        probe.manager.register(saved);
        try (FxTaskRunner runner = new FxTaskRunner()) {
            SqlEditorPane pane = FxUiTestSupport.call(() -> new SqlEditorPane(new SessionContext(),
                    probe.manager, new ObjectTreeService(probe.manager),
                    new AppSettings(directory.resolve("normal-settings")), null, saved, "  schema  ",
                    new SqlHistoryStore(directory.resolve("normal-history")),
                    new ShortcutSettings(directory.resolve("normal-shortcuts")), runner));
            try {
                assertNotNull(field(pane, "jdbcSession"));
                assertEquals(1, probe.sessions.get());
                assertFalse(FxUiTestSupport.call(() -> pane.chooseRecoveryConnection(saved)));
                assertEquals("schema", FxUiTestSupport.call(() -> ((TextField) field(pane, "schemaField")).getText()));
                FxUiTestSupport.call(() -> {
                    new Scene((Parent) pane.getNode(), 1000, 700);
                    pane.getNode().applyCss();
                    ((Parent) pane.getNode()).layout();
                    pane.setSqlText("select 1;\r\nselect 2;\n");
                    assertEquals("select 1;\nselect 2;\n",
                            ((CodeArea) pane.getNode().lookup("#sql-editor")).getText());
                    return null;
                });
            } finally {
                pane.closeResources();
                FxUiTestSupport.call(() -> {
                    pane.finalizeCloseOnFx();
                    return null;
                });
            }
        } finally { probe.manager.closeAll(); }
    }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final FxTaskRunner runner = new FxTaskRunner();
        final SessionContext context = new SessionContext();
        final ConnConfig saved = config("saved", DbType.POSTGRESQL, "saved-name");
        final SqlDraft draft = new SqlDraft(UUID.randomUUID(), 100_000L, "saved", DbType.POSTGRESQL,
                "saved-name", "  raw_schema  ", "select a.\r\nfrom synthetic a;\n");
        final SqlEditorPane pane;

        Fixture(boolean registered) throws Exception {
            if (registered) probe.manager.register(saved);
            java.util.concurrent.atomic.AtomicReference<SqlEditorPane> constructing =
                    new java.util.concurrent.atomic.AtomicReference<>();
            try {
                pane = call(() -> {
                    context.setActiveConnection(saved);
                    SqlEditorPane created = SqlEditorPane.recoverDraft(context, probe.manager,
                            new ObjectTreeService(probe.manager), new AppSettings(directory.resolve("settings")),
                            (id, table) -> fail("No designer during recovery"), draft,
                            new SqlHistoryStore(directory.resolve("history")),
                            new ShortcutSettings(directory.resolve("shortcuts")), runner);
                    constructing.set(created);
                    new Scene((Parent) created.getNode(), 1000, 700);
                    created.getNode().applyCss();
                    ((Parent) created.getNode()).layout();
                    return created;
                });
            } catch (Throwable failure) {
                SqlEditorPane created = constructing.get();
                if (created != null) {
                    try { created.closeResources(); }
                    catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                }
                runner.close();
                probe.manager.closeAll();
                throw failure;
            }
        }

        CodeArea area() { return (CodeArea) pane.getNode().lookup("#sql-editor"); }
        <T> T call(Callable<T> action) throws Exception { return FxUiTestSupport.call(action); }
        void fx(Action action) throws Exception { call(() -> { action.run(); return null; }); }
        void metadataBarrier() throws Exception {
            CountDownLatch delivered = new CountDownLatch(1);
            fx(() -> ((FxSerialTaskQueue) field(pane, "metadataTasks"))
                    .submit(() -> true, ignored -> delivered.countDown(), failure -> delivered.countDown()));
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        }
        void assertOffline() {
            assertEquals(0, probe.providers.get(), "provider resolution");
            assertEquals(0, probe.sessions.get(), "session construction");
            assertEquals(0, probe.metadata.get(), "metadata capability access");
            assertEquals(0, probe.network.get(), "network factory access");
        }
        public void close() throws Exception {
            try {
                pane.closeResources();
                fx(pane::finalizeCloseOnFx);
            }
            finally {
                runner.close();
                probe.manager.closeAll();
            }
        }
    }

    private static ConnConfig config(String id, DbType type, String name) {
        return new ConnConfig(id, name, type, "synthetic.invalid", 1, "synthetic", "", "", Map.of());
    }
    private static Object field(Object target, String name) throws Exception {
        Field value = target.getClass().getDeclaredField(name);
        value.setAccessible(true);
        return value.get(target);
    }
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
    private static void drain(Queue<Runnable> writes) throws Exception {
        Runnable write;
        while ((write = writes.poll()) != null) write.run();
        FxUiTestSupport.call(() -> null);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
