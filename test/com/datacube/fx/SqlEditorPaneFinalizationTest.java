package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.RecentSqlFiles;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.scene.Scene;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlEditorPaneFinalizationTest {
    @TempDir Path directory;

    @Test
    void textUnsubscribeFailureStillFinalizesRegistryDraftListenersResultsAndAutocomplete()
            throws Exception {
        DraftConnectionProbe probe = new DraftConnectionProbe();
        FxTaskRunner runner = new FxTaskRunner();
        SessionContext session = new SessionContext();
        AppSettings settings = new AppSettings(directory.resolve("settings.properties"));
        SqlDraftUi drafts = FxUiTestSupport.call(() -> new SqlDraftUi(directory.resolve("drafts")));
        SqlFileTabRegistry registry = FxUiTestSupport.call(SqlFileTabRegistry::new);
        SqlScriptFileStore store = new SqlScriptFileStore();
        Path file = Files.writeString(directory.resolve("cleanup.sql"), "select 1");
        Path canonical = store.load(file).path();
        SqlFileTabRegistry.Owner owner = FxUiTestSupport.call(
                () -> registry.createOwner(() -> { }));
        SqlEditorPane pane = FxUiTestSupport.call(() -> {
            assertTrue(registry.install(owner, canonical));
            SqlEditorPane created = new SqlEditorPane(session, probe.manager,
                    new ObjectTreeService(probe.manager), settings, null, null, null,
                    new SqlHistoryStore(directory.resolve("history.txt")),
                    new ShortcutSettings(directory.resolve("shortcuts.properties")), runner);
            new Scene((javafx.scene.Parent) created.getNode(), 1000, 700);
            created.installSqlScriptFileController(store.load(file), store,
                    new RecentSqlFiles(directory.resolve("recent.txt")), ignored -> { }, "SQL",
                    registry, owner);
            drafts.bind(created);
            drafts.installed(created.getNode());
            SqlScriptFileController controller = (SqlScriptFileController) field(created, "fileController");
            setField(controller, "unsubscribeTextChanges", (Runnable) () -> {
                throw new IllegalStateException("synthetic reflection unsubscribe failure");
            });
            return created;
        });
        try {
            FxUiTestSupport.call(() -> {
                PartialCloseException partial = assertThrows(
                        PartialCloseException.class, pane::finalizeCloseOnFx);
                assertTrue(partial.getCause().getMessage()
                        .contains("synthetic reflection unsubscribe failure"));
                assertFalse(registry.select(canonical));
                assertNull(drafts.installedBinding(pane.getNode()));
                assertTrue(((AtomicBoolean) field(pane, "uiFinalized")).get());
                assertTrue(((SqlResultToolbar) field(pane, "resultToolbar")).getNode().isDisabled());
                settings.setCommentMode(AppSettings.CommentMode.INLINE);
                session.setActiveConnection(new ConnConfig("late", "late", DbType.POSTGRESQL,
                        "example.invalid", 5432, "db", "user", "", Map.of()));
                return null;
            });
            Thread.sleep(100);
            assertTrue(probe.providers.get() == 0 && probe.sessions.get() == 0
                    && probe.metadata.get() == 0 && probe.network.get() == 0);
        } finally {
            pane.closeResources();
            drafts.closeFromBackground();
            runner.close();
            probe.manager.closeAll();
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
