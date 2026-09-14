package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.RecentSqlFiles;
import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class SqlScriptFileEntryTest {
    @TempDir Path directory;

    @Test void recentPathsHaveOneSearchableEntryInsteadOfUnboundedMenuLabels() throws Exception {
        RecentSqlFiles recent = new RecentSqlFiles(directory.resolve("recent"));
        recent.record(directory.resolve("one/same.sql")); recent.record(directory.resolve("two/same.sql"));
        FxUiTestSupport.call(() -> {
            MenuButton menu = new MenuButton();
            AppShell.rebuildSqlFilesMenu(menu, recent, () -> fail(), () -> fail(), path -> fail());
            assertEquals(java.util.List.of("sql-file-new", "sql-file-open", "sql-file-recent-search", "sql-file-recent-clear"),
                    menu.getItems().stream().map(javafx.scene.control.MenuItem::getId).toList());
            assertEquals("最近文件…", menu.getItems().get(2).getText());
            assertFalse(menu.getItems().get(2).isDisable()); return null;
        });
    }

    @Test
    void sqlFilesMenuOffersNewOfflineScriptBeforeOpen() throws Exception {
        FxUiTestSupport.call(() -> {
            MenuButton menu = new MenuButton();
            AppShell.rebuildSqlFilesMenu(menu, new RecentSqlFiles(directory.resolve("recent")),
                    () -> { }, () -> { }, path -> { });
            assertEquals("sql-file-new", menu.getItems().getFirst().getId(),
                    "starting an independent script must not require an existing SQL file");
            assertEquals("新建 SQL 脚本（离线）", menu.getItems().getFirst().getText());
            return null;
        });
    }

    @Test
    void newScriptCallbackSurvivesRecentMenuRebuildWithoutOpeningFiles() throws Exception {
        RecentSqlFiles recent = new RecentSqlFiles(directory.resolve("recent"));
        Path file = directory.resolve("synthetic.sql").toAbsolutePath();
        recent.record(file);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger opened = new AtomicInteger();
        java.util.List<Path> paths = new java.util.ArrayList<>();
        FxUiTestSupport.call(() -> {
            MenuButton menu = new MenuButton();
            AppShell.rebuildSqlFilesMenu(menu, recent, created::incrementAndGet,
                    opened::incrementAndGet, paths::add, choices -> java.util.Optional.of(choices.getFirst()));
            assertEquals(0, created.get());
            menu.getItems().getFirst().fire();
            assertEquals(1, created.get()); assertEquals(0, opened.get()); assertTrue(paths.isEmpty());
            menu.getItems().stream().filter(i -> "sql-file-recent-search".equals(i.getId())).findFirst().orElseThrow().fire();
            assertEquals(java.util.List.of(file), paths);
            menu.getItems().getLast().fire();
            assertTrue(recent.recent().isEmpty()); assertTrue(menu.getItems().getLast().isDisable());
            assertEquals(4, menu.getItems().size());
            menu.getItems().getFirst().fire(); menu.getItems().get(1).fire();
            assertEquals(2, created.get()); assertEquals(1, opened.get());
            assertEquals(java.util.List.of(file), paths);
            return null;
        });
    }

    @Test
    void defaultsExposeOpenSaveAndSaveAsShortcuts() {
        assertEquals("Ctrl+O", ShortcutAction.SQL_OPEN_FILE.defaultCombo().getName());
        assertEquals("Ctrl+S", ShortcutAction.SQL_SAVE_FILE.defaultCombo().getName());
        assertTrue(ShortcutAction.SQL_SAVE_AS.defaultCombo().match(new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", KeyCode.S, true, true, false, false)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"confirm", "cancel", "cleared", "foreign", "disabled", "preDisabled"})
    void recentPickerRevalidatesSnapshotAndCurrentIndexBeforeOpening(String scenario) throws Exception {
        Path first = directory.resolve("one/same.sql"), second = directory.resolve("two/same.sql");
        var recent = new RecentSqlFiles(directory.resolve("recent")); recent.record(first); recent.record(second);
        byte[] index = Files.readAllBytes(directory.resolve("recent"));
        var opened = new java.util.ArrayList<Path>(); var calls = new AtomicInteger();
        FxUiTestSupport.call(() -> {
            var menu = new MenuButton();
            AppShell.rebuildSqlFilesMenu(menu, recent, () -> fail(), () -> fail(), opened::add, choices -> {
                calls.incrementAndGet(); assertEquals(java.util.List.of(second, first), choices);
                if (scenario.equals("cleared")) recent.clear();
                if (scenario.equals("disabled")) menu.setDisable(true);
                return scenario.equals("cancel") ? java.util.Optional.empty()
                        : java.util.Optional.of(scenario.equals("foreign") ? directory.resolve("not-recent.sql") : first);
            });
            if (scenario.equals("preDisabled")) menu.setDisable(true);
            menu.getItems().get(2).fire();
            assertEquals(scenario.equals("preDisabled") ? 0 : 1, calls.get());
            assertEquals(scenario.equals("confirm") ? java.util.List.of(first) : java.util.List.of(), opened);
            return null;
        });
        if (!scenario.equals("cleared")) assertArrayEquals(index, Files.readAllBytes(directory.resolve("recent")));
        assertFalse(Files.exists(first)); assertFalse(Files.exists(second));
    }

    @Test
    void openUsesAnEmptyIsolatedSessionInstallsExactFileAndBuildsRecentMenu() throws Exception {
        Path file = Files.writeString(directory.resolve("opened.sql"), "select '精确文本';\n");
        RecentSqlFiles recent = new RecentSqlFiles(directory.resolve("recent.txt"));
        ControlledDispatcher dispatcher = new ControlledDispatcher();
        SessionContext global = new SessionContext();
        global.setActiveConnection(new com.datacube.spi.model.ConnConfig("active", "active",
                com.datacube.spi.model.DbType.POSTGRESQL, "example.invalid", 5432, "db", "u", "", java.util.Map.of()));
        AtomicReference<SqlEditorPane> pane = new AtomicReference<>();
        AtomicReference<Tab> tab = new AtomicReference<>();
        AtomicReference<SessionContext> fileSession = new AtomicReference<>();
        AtomicInteger feedback = new AtomicInteger();
        FxTaskRunner runner = new FxTaskRunner();
        AppShell.SqlFileEntry entry = new AppShell.SqlFileEntry(new SqlScriptFileStore(), recent, dispatcher,
                SessionContext::new, (loaded, isolated) -> onFx(() -> {
                    assertNotSame(global, isolated);
                    assertNull(isolated.getActiveConnection());
                    fileSession.set(isolated);
                    SqlEditorPane created = new SqlEditorPane(isolated, null, null,
                            new AppSettings(directory.resolve("settings.properties")), (id, table) -> fail(),
                            null, null, new SqlHistoryStore(directory.resolve("history.txt")),
                            new ShortcutSettings(directory.resolve("shortcuts.properties")), runner);
                    new javafx.scene.Scene((javafx.scene.Parent) created.getNode(), 1000, 700);
                    created.getNode().applyCss();
                    Tab opened = new Tab("SQL", created.getNode());
                    created.installSqlScriptFileController(loaded, new SqlScriptFileStore(), recent,
                            opened::setText, "SQL");
                    pane.set(created);
                    tab.set(opened);
                    return true;
                }), ignored -> feedback.incrementAndGet());
        try {
            entry.open(file);
            dispatcher.runNext();
            assertEquals("select '精确文本';\n", FxUiTestSupport.call(() ->
                    ((org.fxmisc.richtext.CodeArea) pane.get().getNode().lookup("#sql-editor")).getText()));
            assertEquals("opened.sql", FxUiTestSupport.call(() -> tab.get().getText()));
            assertNotSame(global, fileSession.get());
            assertNull(fileSession.get().getActiveConnection());
            assertEquals(0, feedback.get());
            dispatcher.runNext();
            assertEquals(java.util.List.of(file.toRealPath()), recent.recent());

            MenuButton menu = FxUiTestSupport.call(MenuButton::new);
            FxUiTestSupport.call(() -> {
                AppShell.rebuildSqlFilesMenu(menu, recent, () -> { }, () -> { }, entry::open);
                assertNotNull(menu.getItems().stream()
                        .filter(item -> "sql-file-recent-search".equals(item.getId())).findFirst().orElse(null));
                assertNotNull(menu.getItems().stream()
                        .filter(item -> "sql-file-recent-clear".equals(item.getId())).findFirst().orElse(null));
                return null;
            });
        } finally {
            entry.close();
            if (pane.get() != null) {
                pane.get().closeResources();
                FxUiTestSupport.call(() -> { pane.get().finalizeCloseOnFx(); return null; });
            }
            runner.close();
        }
    }

    @Test
    void failedAndMissingOpenKeepTabsUnchangedAndOnlyReportFixedFeedback() throws Exception {
        ControlledDispatcher dispatcher = new ControlledDispatcher();
        AtomicInteger tabs = new AtomicInteger();
        java.util.List<String> feedback = new java.util.ArrayList<>();
        AppShell.SqlFileEntry entry = new AppShell.SqlFileEntry(new SqlScriptFileStore(),
                new RecentSqlFiles(directory.resolve("recent.txt")), dispatcher, SessionContext::new,
                (loaded, isolated) -> { tabs.incrementAndGet(); return true; }, feedback::add);
        try {
            entry.open(directory.resolve("missing.sql"));
            dispatcher.runNext();
            assertEquals(0, tabs.get());
            assertEquals(java.util.List.of(AppShell.SQL_FILE_OPEN_FAILURE), feedback);
            assertFalse(feedback.getFirst().contains("missing.sql"));
        } finally { entry.close(); }
    }

    @Test
    void selectedSaveRoutingFiresOnlySelectedEnabledSqlButton() throws Exception {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        FxUiTestSupport.call(() -> {
            Button firstSave = new Button(); firstSave.setId("sql-file-save"); firstSave.setOnAction(e -> first.incrementAndGet());
            Button secondSave = new Button(); secondSave.setId("sql-file-save"); secondSave.setOnAction(e -> second.incrementAndGet());
            TabPane tabs = new TabPane(new Tab("first", firstSave), new Tab("second", secondSave));
            tabs.getSelectionModel().select(1);
            AppShell.fireSelectedSqlFileAction(tabs, "sql-file-save");
            secondSave.setDisable(true);
            AppShell.fireSelectedSqlFileAction(tabs, "sql-file-save");
            assertEquals(0, first.get());
            assertEquals(1, second.get());
            return null;
        });
    }

    @Test
    void shutdownSuppressesLateLoadCallbacksFeedbackAndRecentWrites() throws Exception {
        Path file = Files.writeString(directory.resolve("late.sql"), "select 1");
        ControlledDispatcher dispatcher = new ControlledDispatcher();
        AtomicInteger tabs = new AtomicInteger();
        AtomicInteger feedback = new AtomicInteger();
        RecentSqlFiles recent = new RecentSqlFiles(directory.resolve("recent.txt"));
        AppShell.SqlFileEntry entry = new AppShell.SqlFileEntry(new SqlScriptFileStore(), recent, dispatcher,
                SessionContext::new, (loaded, isolated) -> { tabs.incrementAndGet(); return true; },
                ignored -> feedback.incrementAndGet());
        entry.open(file);
        entry.close();
        dispatcher.runNext();
        entry.open(null);
        assertEquals(0, tabs.get());
        assertEquals(0, feedback.get());
        assertTrue(recent.recent().isEmpty());
    }

    @Test
    void clearRecentWinsAgainstAnAlreadyQueuedOpenRecord() throws Exception {
        Path file = Files.writeString(directory.resolve("queued.sql"), "select 1");
        ControlledDispatcher dispatcher = new ControlledDispatcher();
        RecentSqlFiles recent = new RecentSqlFiles(directory.resolve("recent.txt"));
        AppShell.SqlFileEntry entry = new AppShell.SqlFileEntry(new SqlScriptFileStore(), recent,
                dispatcher, SessionContext::new, (loaded, session) -> true, ignored -> fail());
        try {
            entry.open(file);
            dispatcher.runNext(); // Load and tab admission queue the record callback.
            recent.clear();

            dispatcher.runNext();

            assertTrue(recent.recent().isEmpty());
        } finally {
            entry.close();
        }
    }

    @Test
    void clearRecentDuringAdmittedLoadWinsBeforeTheLoadCallbackExists() throws Exception {
        Path file = Files.writeString(directory.resolve("in-flight-load.sql"), "select 1");
        ControlledDispatcher dispatcher = new ControlledDispatcher();
        RecentSqlFiles recent = new RecentSqlFiles(directory.resolve("recent.txt"));
        AppShell.SqlFileEntry entry = new AppShell.SqlFileEntry(new SqlScriptFileStore(), recent,
                dispatcher, SessionContext::new, (loaded, session) -> true, ignored -> fail());
        try {
            entry.open(file);
            recent.clear();

            dispatcher.runNext();
            dispatcher.runNext();

            assertTrue(recent.recent().isEmpty());
        } finally {
            entry.close();
        }
    }

    @Test
    void canonicalAliasReusesTheInstalledOwnerBeforeCreatingAnotherTabSessionOrDraft()
            throws Exception {
        Path file = Files.writeString(directory.resolve("single.sql"), "select 1");
        Path aliasDirectory = directory.resolve("alias");
        try {
            Files.createSymbolicLink(aliasDirectory, directory);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "symbolic links unavailable for this account");
        }
        ControlledDispatcher dispatcher = new ControlledDispatcher();
        RecentSqlFiles recent = new RecentSqlFiles(directory.resolve("recent.txt"));
        SqlFileTabRegistry registry = FxUiTestSupport.call(SqlFileTabRegistry::new);
        AtomicInteger sessions = new AtomicInteger();
        AtomicInteger panes = new AtomicInteger();
        AtomicInteger drafts = new AtomicInteger();
        AtomicInteger selections = new AtomicInteger();
        AtomicReference<String> editorText = new AtomicReference<>();
        AtomicReference<SqlFileTabRegistry.Owner> registryOwner = new AtomicReference<>();
        AppShell.SqlFileEntry entry = new AppShell.SqlFileEntry(new SqlScriptFileStore(), recent,
                dispatcher, () -> { sessions.incrementAndGet(); return new SessionContext(); },
                (loaded, session) -> {
                    panes.incrementAndGet();
                    drafts.incrementAndGet();
                    editorText.set(loaded.text());
                    SqlFileTabRegistry.Owner owner = registry.createOwner(selections::incrementAndGet);
                    registryOwner.set(owner);
                    assertTrue(registry.install(owner, loaded.path()));
                    return true;
                }, ignored -> fail(), registry);
        try {
            entry.open(file);
            onFx(() -> { dispatcher.runNext(); return null; });
            dispatcher.runNext();
            editorText.set("dirty text must survive duplicate open");

            entry.open(aliasDirectory.resolve("single.sql"));
            onFx(() -> { dispatcher.runNext(); return null; });

            assertEquals(1, sessions.get());
            assertEquals(1, panes.get());
            assertEquals(1, drafts.get());
            assertEquals(1, selections.get());
            assertEquals("dirty text must survive duplicate open", editorText.get());
            assertEquals(0, dispatcher.queued(), "duplicate reuse must not queue another recent callback");
            assertEquals(java.util.List.of(file.toRealPath()), recent.recent());
        } finally {
            entry.close();
            if (registryOwner.get() != null) FxUiTestSupport.call(() -> {
                registry.release(registryOwner.get());
                return null;
            });
        }
    }

    private static final class ControlledDispatcher implements AppShell.SqlFileTaskDispatcher {
        private final Queue<Runnable> work = new ArrayDeque<>();
        private boolean closed;

        @Override public <T> void submit(Callable<T> operation, java.util.function.Consumer<? super T> success,
                java.util.function.Consumer<? super Throwable> failure) {
            if (closed) throw new java.util.concurrent.RejectedExecutionException();
            work.add(() -> {
                try { T value = operation.call(); success.accept(value); }
                catch (Throwable error) { failure.accept(error); }
            });
        }
        void runNext() { work.remove().run(); }
        int queued() { return work.size(); }
        @Override public void close() { closed = true; }
    }

    private static <T> T onFx(Callable<T> operation) {
        try { return FxUiTestSupport.call(operation); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
}
