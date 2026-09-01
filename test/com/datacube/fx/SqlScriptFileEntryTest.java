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

    @Test
    void defaultsExposeOpenSaveAndSaveAsShortcuts() {
        assertEquals("Ctrl+O", ShortcutAction.SQL_OPEN_FILE.defaultCombo().getName());
        assertEquals("Ctrl+S", ShortcutAction.SQL_SAVE_FILE.defaultCombo().getName());
        assertTrue(ShortcutAction.SQL_SAVE_AS.defaultCombo().match(new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", KeyCode.S, true, true, false, false)));
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
                AppShell.rebuildSqlFilesMenu(menu, recent, () -> { }, entry::open);
                assertNotNull(menu.getItems().stream()
                        .filter(item -> "sql-file-recent-0".equals(item.getId())).findFirst().orElse(null));
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
        @Override public void close() { closed = true; }
    }

    private static <T> T onFx(Callable<T> operation) {
        try { return FxUiTestSupport.call(operation); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
}
