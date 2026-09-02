package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.RecentSqlFiles;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import com.datacube.config.SqlHistoryStore;
import com.datacube.config.SqlWorkspace;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.sqleditor.SqlScriptDocument;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import javafx.scene.control.Button;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlTabFileLifecycleTest {
    @TempDir Path directory;

    @Test
    void ordinaryAndHistoryTabsInstallUnboundFileControllersBeforeDraftBinding() throws Exception {
        DraftConnectionProbe probe = new DraftConnectionProbe();
        FxTaskRunner runner = new FxTaskRunner();
        ContentTabPane tabs = FxUiTestSupport.call(ContentTabPane::new);
        SqlDraftUi draftUi = FxUiTestSupport.call(
                () -> new SqlDraftUi(directory.resolve("drafts"), tabs));
        SqlFileTabRegistry registry = FxUiTestSupport.call(SqlFileTabRegistry::new);
        SqlScriptFileStore store = new SqlScriptFileStore();
        RecentSqlFiles recent = new RecentSqlFiles(directory.resolve("recent.txt"));
        AppSettings settings = new AppSettings(directory.resolve("settings.properties"));
        SqlHistoryStore history = new SqlHistoryStore(directory.resolve("history.txt"));
        ShortcutSettings shortcuts = new ShortcutSettings(directory.resolve("shortcuts.properties"));
        List<SqlEditorPane> created = new ArrayList<>();
        AppShell.SqlFileDraftLifecycle drafts = new AppShell.SqlFileDraftLifecycle() {
            @Override public void bind(SqlEditorPane pane) { draftUi.bind(pane); }
            @Override public void installed(javafx.scene.Node content) { draftUi.installed(content); }
        };
        try {
            AtomicReference<SqlEditorPane> ordinary = new AtomicReference<>();
            FxUiTestSupport.call(() -> {
                assertTrue(AppShell.openSqlTab(tabs, "SQL", () -> {
                    SqlEditorPane pane = new SqlEditorPane(new SessionContext(), probe.manager,
                            new ObjectTreeService(probe.manager), settings, null, null, null,
                            history, shortcuts, runner);
                    ordinary.set(pane);
                    created.add(pane);
                    return pane;
                }, ignored -> { }, store, recent, drafts, registry));
                return null;
            });

            AtomicReference<SqlEditorPane> restoredHistory = new AtomicReference<>();
            FxUiTestSupport.call(() -> {
                assertTrue(AppShell.openSqlTab(tabs, "SQL - 历史", () -> {
                    SqlEditorPane pane = new SqlEditorPane(new SessionContext(), probe.manager,
                            new ObjectTreeService(probe.manager), settings, null, null, "public",
                            history, shortcuts, runner);
                    restoredHistory.set(pane);
                    created.add(pane);
                    return pane;
                }, pane -> pane.setSqlText("select *\r\nfrom history"), store, recent, drafts,
                        registry));
                return null;
            });

            assertUnboundCleanDraft(ordinary.get(), "");
            assertUnboundCleanDraft(restoredHistory.get(), "select *\r\nfrom history");

            Path filePath = directory.resolve("must-never-appear-in-drafts.sql").toAbsolutePath();
            SqlScriptFileStore.Loaded saved = store.save(store.capture(filePath), "file baseline");
            SqlDraftCoordinator.Handle handle = FxUiTestSupport.call(() -> {
                SqlScriptFileController controller = (SqlScriptFileController)
                        field(ordinary.get(), "fileController");
                ((SqlScriptDocument) field(controller, "document")).saved(saved);
                ((CodeArea) field(ordinary.get(), "editorArea"))
                        .replaceText("draft text after file binding");
                return (SqlDraftCoordinator.Handle) field(
                        field(ordinary.get(), "draftBinding"), "handle");
            });
            FxUiTestSupport.call(handle::flush).get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> null);
            var snapshot = FxUiTestSupport.call(() -> draftUi.runtime().refresh())
                    .get(5, TimeUnit.SECONDS).snapshot();
            assertTrue(snapshot.drafts().stream()
                    .anyMatch(draft -> "draft text after file binding".equals(draft.sql())));
            assertTrue(java.util.Arrays.stream(SqlDraft.class.getRecordComponents())
                    .noneMatch(component -> component.getType() == Path.class
                            || component.getName().toLowerCase().contains("path")));
            assertTrue(java.util.Arrays.stream(SqlWorkspace.class.getRecordComponents())
                    .noneMatch(component -> component.getType() == Path.class
                            || component.getName().toLowerCase().contains("path")));
            assertEquals(0, probe.providers.get());
            assertEquals(0, probe.sessions.get());
            assertEquals(0, probe.metadata.get());
            assertEquals(0, probe.network.get());
        } finally {
            for (SqlEditorPane pane : created) {
                pane.closeResources();
                FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
            }
            draftUi.closeFromBackground();
            runner.close();
            probe.manager.closeAll();
        }
    }

    private static void assertUnboundCleanDraft(SqlEditorPane pane, String expectedText)
            throws Exception {
        FxUiTestSupport.call(() -> {
            SqlScriptFileController controller = (SqlScriptFileController) field(pane, "fileController");
            SqlScriptDocument document = (SqlScriptDocument) field(controller, "document");
            assertNull(document.target());
            assertFalse(document.dirty());
            assertEquals(expectedText.replace("\r\n", "\n"), document.normalizedText());
            assertNotNull(field(pane, "draftBinding"));
            assertFalse(((Button) field(pane, "saveSqlFileBtn")).isDisabled());
            assertFalse(((Button) field(pane, "saveAsSqlFileBtn")).isDisabled());
            return null;
        });
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
}
