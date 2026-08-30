package com.datacube.fx;

import com.datacube.config.DraftManagementProbe;
import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlDraftManagerTest {
    static SqlDraft draft(long time, String sql) {
        return new SqlDraft(UUID.randomUUID(), time, "saved", DbType.POSTGRESQL, "Saved", "  schema  ", sql);
    }

    @Test void initializingDisablesRestoreAndRefreshesOnceWhenReady() throws Exception {
        try (Fixture f = new Fixture(false, true, true)) {
            f.fx(() -> {
                assertTrue(f.button("restore").isDisabled());
                assertTrue(f.label("status").getText().contains("初始化"));
            });
            f.ready();
            assertEquals(2, f.probe.prunes, "startup prune plus one manager refresh");
            f.fx(() -> { f.pane.refreshView(); f.pane.refreshView(); });
            assertEquals(2, f.probe.prunes);
        }
    }

    @Test void rowsAreNewestFirstAndFullSqlRequiresExplicitSelection() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.fx(() -> {
                assertEquals(List.of(f.newer, f.older), f.list().getItems());
                assertNull(f.list().getSelectionModel().getSelectedItem());
                assertEquals("", f.sql().getText());
                assertFalse(f.sql().isEditable());
                f.list().getSelectionModel().select(f.newer);
                assertEquals(f.newer.sql().replace("\r\n", "\n").replace('\r', '\n'), f.sql().getText());
                assertEquals("select 1;\r\n-- raw\n", f.newer.sql(), "display normalization must not change checkpoint");
                assertFalse(f.button("restore").isDisabled());
            });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"\n", "\r", "\r\n"})
    void fullPreviewPreservesLogicalLinesWithoutChangingRawCheckpoint(String ending) throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            SqlDraft raw = draft(100_001L, "select 1;" + ending + "-- second");
            f.probe.records.clear();
            f.probe.records.add(raw);
            f.fx(() -> f.button("refresh").fire());
            f.settle();
            f.fx(() -> {
                f.list().getSelectionModel().select(raw);
                assertEquals("select 1;\n-- second", f.sql().getText());
                assertEquals("select 1;" + ending + "-- second", f.list().getSelectionModel().getSelectedItem().sql());
            });
        }
    }

    @Test void emptyDraftRestoresAndFalseRestoreKeepsManagerOpen() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.fx(() -> {
                f.list().getSelectionModel().select(f.older);
                assertEquals("", f.sql().getText());
                f.acceptRestore = false;
                f.button("restore").fire();
                assertEquals(1, f.restores.get());
                assertEquals(0, f.closed.get());
                assertTrue(f.label("notice").getText().contains("恢复失败"));
                f.acceptRestore = true;
                f.button("restore").fire();
                assertEquals(2, f.restores.get());
                assertEquals(1, f.closed.get());
            });
        }
    }

    @Test void disabledProtectionRetainsReadableRecoverableRecords() throws Exception {
        try (Fixture f = new Fixture(true, false, true)) {
            f.fx(() -> {
                assertTrue(f.label("status").getText().contains("已关闭"));
                assertEquals(2, f.list().getItems().size());
                f.list().getSelectionModel().selectFirst();
                f.button("restore").fire();
                assertEquals(1, f.closed.get());
            });
        }
    }

    @Test void unavailableStorageStillAllowsAlreadyReadRecordsToRestore() throws Exception {
        try (Fixture f = new Fixture(true, true, false)) {
            f.fx(() -> {
                assertTrue(f.label("status").getText().contains("不可用"));
                assertTrue(f.button("clear").isDisabled());
                assertTrue(f.button("toggle").isDisabled());
                f.list().getSelectionModel().selectFirst();
                assertFalse(f.button("restore").isDisabled());
                f.button("restore").fire();
                assertEquals(1, f.closed.get());
            });
        }
    }

    @Test void externalManagementBlocksRestoreAndControlsUntilSnapshotSettles() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.fx(() -> {
                f.list().getSelectionModel().select(f.newer);
                f.runtime.refresh();
                assertTrue(f.runtime.managementPending());
                // Event must be guarded even before the timer disables its button.
                f.button("restore").fire();
                assertEquals(0, f.restores.get());
                f.pane.refreshView();
                for (String id : List.of("restore", "refresh", "clear", "delete", "toggle"))
                    assertTrue(f.button(id).isDisabled(), id);
            });
            f.settle();
            f.fx(() -> {
                assertFalse(f.runtime.managementPending());
                assertFalse(f.button("restore").isDisabled());
                assertEquals(f.newer.id(), f.list().getSelectionModel().getSelectedItem().id());
            });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"delete", "clear"})
    void destructiveCancelIsDefaultAndDoesNotMutate(String action) throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.fx(() -> {
                f.list().getSelectionModel().selectFirst();
                respondToDialog(() -> f.button(action).fire(), dialog -> {
                    assertTrue(((Button) dialog.lookupButton(ButtonType.CANCEL)).isDefaultButton());
                    dialog.getButtonTypes().stream().filter(type -> type != ButtonType.CANCEL)
                            .forEach(type -> assertFalse(((Button) dialog.lookupButton(type)).isDefaultButton()));
                });
            });
            assertEquals(0, f.probe.clears);
            assertEquals(0, f.probe.deletions);
            assertEquals(2, f.probe.records.size());
        }
    }

    @Test void confirmedDeleteRemovesOnlySelectedRecord() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.fx(() -> {
                f.list().getSelectionModel().select(f.newer);
                respondToDialog(() -> f.button("delete").fire(), SqlDraftManagerTest::confirmDialog);
            });
            f.settle();
            f.fx(() -> assertEquals(List.of(f.older), f.list().getItems()));
            assertEquals(1, f.probe.deletions);
            assertEquals(0, f.probe.clears);
        }
    }

    @Test void partialClearShowsActualSurvivorAndWarning() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.probe.partialClear = true;
            f.fx(() -> respondToDialog(() -> f.button("clear").fire(), SqlDraftManagerTest::confirmDialog));
            f.settle();
            f.fx(() -> {
                assertEquals(List.of(f.newer), f.list().getItems());
                assertTrue(f.label("notice").getText().contains("部分"));
            });
            assertEquals(1, f.probe.clears);
        }
    }

    @Test void successfulClearEmptiesOnlyRecoveryList() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.fx(() -> {
                f.list().getSelectionModel().select(f.newer);
                respondToDialog(() -> f.button("clear").fire(), SqlDraftManagerTest::confirmDialog);
            });
            f.settle();
            f.fx(() -> {
                assertTrue(f.list().getItems().isEmpty());
                assertEquals("", f.sql().getText());
                assertTrue(f.button("restore").isDisabled());
                assertTrue(f.label("status").getText().contains("共 0 份"));
            });
            assertEquals(1, f.probe.clears);
            assertEquals(0, f.probe.deletions);
        }
    }

    @Test void explicitDisableThenEnableUpdatesPreferenceAndRetainsRecords() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.fx(() -> f.button("toggle").fire());
            f.settle();
            assertFalse(f.probe.enabled);
            f.fx(() -> {
                assertEquals(SqlDraftCoordinator.Mode.DISABLED, f.runtime.mode());
                assertEquals(2, f.list().getItems().size());
                f.button("toggle").fire();
            });
            f.settle();
            assertTrue(f.probe.enabled);
            f.fx(() -> {
                assertEquals(SqlDraftCoordinator.Mode.ENABLED, f.runtime.mode());
                assertEquals(2, f.list().getItems().size());
            });
        }
    }

    @Test void failedDisableSaysPausedNotPersistedDisabled() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            f.probe.failPreference = true;
            f.fx(() -> f.button("toggle").fire());
            f.settle();
            f.fx(() -> {
                assertEquals(SqlDraftCoordinator.Mode.PAUSED, f.runtime.mode());
                assertTrue(f.label("status").getText().contains("设置未保存"));
                assertFalse(f.label("status").getText().contains("已关闭"));
                assertEquals(2, f.list().getItems().size());
            });
        }
    }

    @Test void closedViewIgnoresLateManagementCompletion() throws Exception {
        try (Fixture f = new Fixture(true, true, true)) {
            String before = f.call(() -> {
                f.button("refresh").fire();
                f.pane.close();
                return f.label("status").getText();
            });
            f.probe.records.clear();
            f.settle();
            f.fx(() -> {
                assertEquals(2, f.list().getItems().size());
                assertEquals(before, f.label("status").getText());
            });
        }
    }

    @Test void previewAndChoiceLabelsAreBoundedAndDoNotRenderCredentialFields() {
        assertEquals("a b c…", SqlDraftManagerPane.preview("a\nb\tc" + "x".repeat(1_048_576), 5));
        ConnConfig pg = new ConnConfig("id", "Name", DbType.POSTGRESQL,
                "SECRET_HOST", 99, "SECRET_DB", "SECRET_USER", "SECRET_PASSWORD", Map.of("secret", "SECRET_PROP"));
        ConnConfig redis = new ConnConfig("r", "Redis", DbType.REDIS, "host", 1, "db", "u", "p", Map.of());
        var choices = SqlDraftConnectionChooser.choices(List.of(pg, redis));
        assertEquals(1, choices.size());
        assertEquals("Name · POSTGRESQL · id", choices.getFirst().toString());
        assertFalse(choices.toString().contains("SECRET"));
    }

    static void confirmDialog(DialogPane dialog) {
        ButtonType confirm = dialog.getButtonTypes().stream().filter(type -> type != ButtonType.CANCEL).findFirst().orElseThrow();
        ((Button) dialog.lookupButton(confirm)).fire();
    }

    /** FX nested event loop, with unconditional Cancel cleanup even when an assertion fails. */
    static void respondToDialog(Runnable open, Consumer<DialogPane> response) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            DialogPane dialog = null;
            try {
                dialog = Window.getWindows().stream().filter(Window::isShowing)
                        .map(window -> window.getScene().getRoot()).filter(DialogPane.class::isInstance)
                        .map(DialogPane.class::cast).findFirst().orElseThrow();
                response.accept(dialog);
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                if (dialog != null && dialog.getScene().getWindow().isShowing()) {
                    var cancel = dialog.lookupButton(ButtonType.CANCEL);
                    if (cancel == null) cancel = dialog.lookupButton(ButtonType.CLOSE);
                    if (cancel instanceof Button button) button.fire();
                    else dialog.getScene().getWindow().hide();
                }
            }
        });
        open.run();
        if (failure.get() != null) throw new AssertionError("Dialog assertion failed", failure.get());
    }

    private static final class Fixture implements AutoCloseable {
        final DraftManagementProbe probe = new DraftManagementProbe();
        final SqlDraft older = draft(90_000L, "");
        final SqlDraft newer = draft(100_000L, "select 1;\r\n-- raw\n");
        final AtomicInteger restores = new AtomicInteger(), closed = new AtomicInteger();
        boolean acceptRestore = true;
        final SqlDraftCoordinator runtime;
        final SqlDraftManagerPane pane;

        Fixture(boolean ready, boolean enabled, boolean writable) throws Exception {
            probe.enabled = enabled;
            probe.writable = writable;
            probe.records.addAll(List.of(older, newer));
            runtime = call(() -> probe.create(Platform::runLater, Platform::isFxApplicationThread));
            pane = call(() -> {
                SqlDraftManagerPane created = new SqlDraftManagerPane(runtime, draft -> {
                    restores.incrementAndGet();
                    return acceptRestore;
                }, closed::incrementAndGet);
                new Scene(created.getNode());
                created.getNode().applyCss();
                created.getNode().layout();
                return created;
            });
            try {
                if (ready) ready();
            } catch (Exception | Error failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        void ready() throws Exception { settle(); settle(); }
        void settle() throws Exception {
            probe.drain();
            fx(pane::refreshView);
            fx(() -> {});
        }
        @SuppressWarnings("unchecked") ListView<SqlDraft> list() {
            return (ListView<SqlDraft>) pane.getNode().lookup("#draft-manager-list");
        }
        Button button(String id) { return (Button) pane.getNode().lookup("#draft-manager-" + id); }
        Label label(String id) { return (Label) pane.getNode().lookup("#draft-manager-" + id); }
        TextArea sql() { return (TextArea) pane.getNode().lookup("#draft-manager-sql"); }
        <T> T call(Callable<T> work) throws Exception { return FxUiTestSupport.call(work); }
        void fx(Runnable work) throws Exception { call(() -> { work.run(); return null; }); }
        public void close() throws Exception {
            fx(pane::close);
            var close = call(runtime::shutdown);
            probe.drain();
            close.get(5, TimeUnit.SECONDS);
        }
    }
}
