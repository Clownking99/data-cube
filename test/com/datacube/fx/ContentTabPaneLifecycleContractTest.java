package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.RecentSqlFiles;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.sqleditor.SqlScriptDocument;
import com.datacube.sqleditor.SqlScriptFileStore;
import javafx.scene.Group;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Label;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentTabPaneLifecycleContractTest {

    @TempDir Path directory;

    @Test
    void transactionalFactoryFailureNeverPublishesThePrecreatedTabAndAbortsOnce() throws Exception {
        ContentTabPane tabs = FxUiTestSupport.call(ContentTabPane::new);
        AtomicInteger cleanup = new AtomicInteger();
        FxUiTestSupport.call(() -> {
            tabs.addPermanentTab("existing", new Group());
            TabPane pane = (TabPane) tabs.getNode();
            Tab selected = pane.getSelectionModel().getSelectedItem();
            assertEquals(1, pane.getTabs().size());

            assertEquals(null, tabs.openManagedTab("unpublished", (tab, abort) -> {
                assertEquals("unpublished", tab.getText());
                abort.bind(cleanup::incrementAndGet);
                throw new IllegalStateException("synthetic install failure");
            }));
            assertEquals(1, pane.getTabs().size());
            assertSame(selected, pane.getSelectionModel().getSelectedItem());
            return null;
        });
        for (int i = 0; cleanup.get() == 0 && i < 50; i++) Thread.sleep(10);
        assertEquals(1, cleanup.get());
    }

    @Test
    void appShellFileTabTransactionFailureAfterDraftInstallUnwindsRealFxListenersAndResources()
            throws Exception {
        FxTaskRunner runner = new FxTaskRunner();
        AtomicReference<SqlEditorPane> created = new AtomicReference<>();
        ContentTabPane tabs = FxUiTestSupport.call(ContentTabPane::new);
        SessionContext session = new SessionContext();
        AppSettings settings = new AppSettings(directory.resolve("settings.properties"));
        SqlScriptFileStore store = new SqlScriptFileStore();
        Path file = Files.writeString(directory.resolve("failed.sql"), "select 1\r\n");
        SqlScriptFileStore.Loaded loaded = store.load(file);
        RecentSqlFiles recent = new RecentSqlFiles(directory.resolve("recent.txt"));
        SqlDraftUi drafts = FxUiTestSupport.call(() -> new SqlDraftUi(directory.resolve("drafts"), tabs));
        try {
            FxUiTestSupport.call(() -> {
                tabs.addPermanentTab("existing", new Group());
                TabPane pane = (TabPane) tabs.getNode();
                Tab selected = pane.getSelectionModel().getSelectedItem();

                assertFalse(AppShell.openLoadedSqlFile(tabs, loaded, session, null, null, settings,
                        (id, table) -> { }, new SqlHistoryStore(directory.resolve("history.txt")),
                        new ShortcutSettings(directory.resolve("shortcuts.properties")), runner, store, recent,
                        new AppShell.SqlFileDraftLifecycle() {
                            @Override public void bind(SqlEditorPane editor) {
                                created.set(editor);
                                drafts.bind(editor);
                            }

                            @Override public void installed(javafx.scene.Node content) {
                                drafts.installed(content);
                                throw new IllegalStateException("synthetic installed failure");
                            }
                        }));

                assertEquals(1, pane.getTabs().size());
                assertSame(selected, pane.getSelectionModel().getSelectedItem());
                assertNull(drafts.installedBinding(created.get().getNode()));
                return null;
            });
            waitForResourcesClosed(created.get());
            FxUiTestSupport.call(() -> {
                CodeArea editor = editorArea(created.get());
                Label connection = connectionBadge(created.get());
                String originalConnection = connection.getText();
                editor.replaceText("later edit must not reach detached file controller");
                session.setActiveConnection(new com.datacube.spi.model.ConnConfig("id", "active",
                        com.datacube.spi.model.DbType.POSTGRESQL, "example.invalid", 5432,
                        "db", "user", "", java.util.Map.of()));
                settings.setCommentMode(AppSettings.CommentMode.INLINE);

                assertEquals(originalConnection, connection.getText());
                assertEquals("select 1\n", fileDocument(created.get()).normalizedText());
                assertNull(drafts.installedBinding(created.get().getNode()));
                return null;
            });
        } finally {
            Thread closer = Thread.ofVirtual().start(drafts::closeFromBackground);
            closer.join();
            if (created.get() != null) created.get().closeResources();
            runner.close();
        }
    }

    @Test
    void appShellCanonicalDuplicateReusesTheDirtyManagedTabAndReleasesRegistryOnFinalize()
            throws Exception {
        Path file = Files.writeString(directory.resolve("single.sql"), "select 1\r\n");
        Path aliasDirectory = directory.resolve("alias");
        try {
            Files.createSymbolicLink(aliasDirectory, directory);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "symbolic links unavailable for this account");
        }
        SqlScriptFileStore store = new SqlScriptFileStore();
        SqlScriptFileStore.Loaded loaded = store.load(file);
        SqlScriptFileStore.Loaded alias = store.load(aliasDirectory.resolve("single.sql"));
        FxTaskRunner runner = new FxTaskRunner();
        ContentTabPane tabs = FxUiTestSupport.call(ContentTabPane::new);
        SqlFileTabRegistry registry = FxUiTestSupport.call(SqlFileTabRegistry::new);
        AtomicInteger draftBindings = new AtomicInteger();
        AtomicInteger draftInstalls = new AtomicInteger();
        AtomicReference<SqlEditorPane> created = new AtomicReference<>();
        AppShell.SqlFileDraftLifecycle drafts = new AppShell.SqlFileDraftLifecycle() {
            @Override public void bind(SqlEditorPane pane) {
                draftBindings.incrementAndGet();
                created.set(pane);
            }
            @Override public void installed(javafx.scene.Node content) {
                draftInstalls.incrementAndGet();
            }
        };
        AppSettings settings = new AppSettings(directory.resolve("settings.properties"));
        RecentSqlFiles recent = new RecentSqlFiles(directory.resolve("recent.txt"));
        try {
            FxUiTestSupport.call(() -> {
                assertTrue(AppShell.openLoadedSqlFile(tabs, loaded, new SessionContext(), null, null,
                        settings, (id, table) -> { }, new SqlHistoryStore(directory.resolve("history.txt")),
                        new ShortcutSettings(directory.resolve("shortcuts.properties")), runner, store,
                        recent, drafts, registry));
                editorArea(created.get()).replaceText("dirty text");

                assertTrue(AppShell.openLoadedSqlFile(tabs, alias, new SessionContext(), null, null,
                        settings, (id, table) -> { }, new SqlHistoryStore(directory.resolve("history.txt")),
                        new ShortcutSettings(directory.resolve("shortcuts.properties")), runner, store,
                        recent, drafts, registry));

                TabPane pane = (TabPane) tabs.getNode();
                assertEquals(1, pane.getTabs().size());
                assertEquals("dirty text", editorArea(created.get()).getText());
                assertEquals(1, draftBindings.get());
                assertEquals(1, draftInstalls.get());
                assertTrue(registry.select(loaded.path()));
                return null;
            });
        } finally {
            if (created.get() != null) {
                created.get().closeResources();
                FxUiTestSupport.call(() -> {
                    created.get().finalizeCloseOnFx();
                    assertFalse(registry.select(loaded.path()));
                    return null;
                });
            }
            runner.close();
        }
    }

    private static void waitForResourcesClosed(SqlEditorPane pane) throws Exception {
        Field closed = SqlEditorPane.class.getDeclaredField("resourcesClosed");
        closed.setAccessible(true);
        for (int i = 0; i < 50; i++) {
            if (((java.util.concurrent.atomic.AtomicBoolean) closed.get(pane)).get()) return;
            Thread.sleep(10);
        }
        assertTrue(((java.util.concurrent.atomic.AtomicBoolean) closed.get(pane)).get());
    }

    private static SqlScriptDocument fileDocument(SqlEditorPane pane) throws Exception {
        Field controllerField = SqlEditorPane.class.getDeclaredField("fileController");
        controllerField.setAccessible(true);
        SqlScriptFileController controller = (SqlScriptFileController) controllerField.get(pane);
        Field documentField = SqlScriptFileController.class.getDeclaredField("document");
        documentField.setAccessible(true);
        return (SqlScriptDocument) documentField.get(controller);
    }

    private static CodeArea editorArea(SqlEditorPane pane) throws Exception {
        Field field = SqlEditorPane.class.getDeclaredField("editorArea");
        field.setAccessible(true);
        return (CodeArea) field.get(pane);
    }

    private static Label connectionBadge(SqlEditorPane pane) throws Exception {
        Field field = SqlEditorPane.class.getDeclaredField("connectionBadge");
        field.setAccessible(true);
        return (Label) field.get(pane);
    }

    @Test
    void legacyManagedSpecUsesTheInteractiveGuardForMandatoryClose() {
        AtomicInteger calls = new AtomicInteger();
        AsyncTabCloseGuard guard = () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED);
        };

        ContentTabPane.ManagedTabSpec spec = new ContentTabPane.ManagedTabSpec(
                new Group(), guard, () -> {}, () -> {});

        assertSame(guard, spec.guard());
        assertSame(guard, spec.mandatoryGuard());
        assertEquals(CloseGuardOutcome.APPROVED,
                spec.mandatoryGuard().requestClose().toCompletableFuture().join());
        assertEquals(1, calls.get());
    }

    @Test
    void contentPaneExposesDistinctInteractiveAndMandatoryCloseAllEntrypoints() throws Exception {
        Method interactive = ContentTabPane.class.getMethod("closeAllManagedTabs");
        Method mandatory = ContentTabPane.class.getMethod("closeAllManagedTabsMandatory");

        assertEquals(CompletionStage.class, interactive.getReturnType());
        assertEquals(CompletionStage.class, mandatory.getReturnType());
    }
}
