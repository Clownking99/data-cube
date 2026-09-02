package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.RecentSqlFiles;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.sqleditor.SqlScriptDocument;
import com.datacube.sqleditor.SqlScriptFileStore;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlDraftRecoveryTabsTest {
    @TempDir Path directory;

    @Test void recoveredDuplicateFocusesInstalledTabWithoutSecondFactoryOrConnection() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fx(() -> {
                assertTrue(f.recovery.restore(f.draft));
                Node expected = f.owner.installedContent(f.draft.id());
                f.tabs.openTab("other", new Label("other"));
                assertTrue(f.recovery.restore(f.draft));
                assertSame(expected, f.tabPane().getSelectionModel().getSelectedItem().getContent());
                assertEquals(2, f.tabPane().getTabs().size());
                assertEquals(1, f.created.size());
                assertNull(f.context.getActiveConnection());
            });
            f.offline();
        }
    }

    @Test void recoveredDraftKeepsDraftBindingAndGetsCleanUnboundFileLifecycle() throws Exception {
        try (Fixture f = new Fixture()) {
            SqlFileTabRegistry registry = f.call(SqlFileTabRegistry::new);
            SqlScriptFileStore store = new SqlScriptFileStore();
            RecentSqlFiles recent = new RecentSqlFiles(directory.resolve("recent.txt"));
            SqlDraftRecoveryTabs recovery = f.call(() -> new SqlDraftRecoveryTabs(
                    f.tabs, f.owner, f::create,
                    pane -> pane.installRecoveryConnectionChooser(() -> List.of(f.replacement)),
                    store, recent, registry));

            assertTrue(f.call(() -> recovery.restore(f.draft)));
            f.fx(() -> {
                SqlEditorPane pane = f.created.getFirst();
                SqlScriptFileController controller = (SqlScriptFileController)
                        fieldUnchecked(pane, "fileController");
                SqlScriptDocument document = (SqlScriptDocument)
                        fieldUnchecked(controller, "document");
                assertNull(document.target());
                assertFalse(document.dirty());
                assertEquals(f.draft.sql().replace("\r\n", "\n").replace('\r', '\n'),
                        document.normalizedText());
                assertNotNull(fieldUnchecked(pane, "draftBinding"));
                assertNotNull(f.owner.installedBinding(pane.getNode()));
                assertFalse(((Button) fieldUnchecked(pane, "saveSqlFileBtn")).isDisabled());
                assertFalse(((Button) fieldUnchecked(pane, "saveAsSqlFileBtn")).isDisabled());
            });
            f.offline();
        }
    }

    @Test void normallyOpenedAutosavedDraftAlsoFocusesExistingTab() throws Exception {
        try (Fixture f = new Fixture()) {
            SqlEditorPane normal = f.call(() -> {
                AtomicReference<SqlEditorPane> pane = new AtomicReference<>();
                Tab tab = f.tabs.openManagedTab("normal", abort -> ManagedTabFactorySequence.create(
                        () -> {
                            SqlEditorPane created = new SqlEditorPane(f.context, f.probe.manager,
                                    new ObjectTreeService(f.probe.manager), f.settings, null, null, null,
                                    f.history, f.shortcuts, f.runner);
                            pane.set(created);
                            f.created.add(created);
                            return created;
                        }, value -> abort.bind(value::closeResources), value -> {
                            value.setSqlText("normal checkpoint");
                            f.owner.bind(value);
                        }, value -> new ContentTabPane.ManagedTabSpec(value.getNode(), value::requestClose,
                                value::requestMandatoryClose, value::finalizeCloseOnFx, value::closeResources)));
                assertNotNull(tab);
                f.owner.installed(tab.getContent());
                return pane.get();
            });
            f.flush(normal);
            var records = f.call(() -> f.owner.runtime().refresh()).get(5, TimeUnit.SECONDS).snapshot().drafts();
            f.fx(() -> {
                assertEquals(1, records.size());
                assertEquals("normal checkpoint", records.getFirst().sql());
                assertTrue(f.recovery.restore(records.getFirst()));
                assertEquals(1, f.created.size());
                assertEquals(1, f.tabPane().getTabs().size());
                assertSame(normal.getNode(), f.tabPane().getSelectionModel().getSelectedItem().getContent());
            });
            f.offline();
        }
    }

    @Test void refusedCloseRetainsMappingAndSuccessfulFinalizationRemovesIt() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fx(() -> {
                assertTrue(f.recovery.restore(f.draft));
                ((TextField) fieldUnchecked(f.created.getFirst(), "schemaField")).setText("\ud800");
            });
            assertEquals(TabCloseOutcome.CANCELLED,
                    f.tabs.closeAllManagedTabsMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS));
            f.fx(() -> {
                assertNotNull(f.owner.installedContent(f.draft.id()));
                assertTrue(f.recovery.restore(f.draft));
                assertEquals(1, f.created.size());
                ((TextField) fieldUnchecked(f.created.getFirst(), "schemaField")).setText("valid");
            });
            assertEquals(TabCloseOutcome.COMPLETED,
                    f.tabs.closeAllManagedTabsMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS));
            f.fx(() -> {
                assertNull(f.owner.installedContent(f.draft.id()));
                assertTrue(f.tabPane().getTabs().isEmpty());
                assertEquals(0, ((Map<?, ?>) fieldUnchecked(f.owner, "boundContent")).size());
            });
            f.offline();
        }
    }

    @Test void staleMappedContentFailsWithoutUnmanagedFallbackOrSecondHandle() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fx(() -> {
                assertTrue(f.recovery.restore(f.draft));
                Tab tab = f.tabPane().getTabs().getFirst();
                Node content = tab.getContent();
                try {
                    tab.setContent(new Label("temporarily different content"));
                    assertFalse(f.recovery.restore(f.draft));
                    assertEquals(1, f.created.size());
                    assertEquals(1, f.tabPane().getTabs().size());
                    assertFalse(f.tabs.selectExistingContent(new Label("absent")));
                    assertEquals(1, f.tabPane().getTabs().size());
                } finally { tab.setContent(content); }
            });
        }
    }

    @Test void closeDuringReservedConstructionWaitsAndReleasesDraftSubscription() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicReference<CompletionStage<TabCloseOutcome>> closing = new AtomicReference<>();
            SqlDraftRecoveryTabs recovery = f.call(() -> new SqlDraftRecoveryTabs(f.tabs, f.owner, draft -> {
                SqlEditorPane pane = f.create(draft);
                closing.set(f.tabs.closeAllManagedTabsMandatory());
                return pane;
            }, ignored -> {}));
            assertTrue(f.call(() -> recovery.restore(f.draft)));
            assertEquals(TabCloseOutcome.COMPLETED, closing.get().toCompletableFuture().get(5, TimeUnit.SECONDS));
            f.fx(() -> {
                assertNull(f.owner.installedContent(f.draft.id()));
                assertTrue(f.tabPane().getTabs().isEmpty());
                assertEquals(0, ((Map<?, ?>) fieldUnchecked(f.owner, "boundContent")).size());
                assertEquals(0, ((Map<?, ?>) fieldUnchecked(f.owner.runtime(), "handles")).size());
            });
            f.offline();
        }
    }

    @Test void failedSelectionInstallationAbortsBoundPaneAndReleasesDraftSubscription() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fx(() -> f.tabPane().setSelectionModel(new javafx.scene.control.SingleSelectionModel<Tab>() {
                @Override protected Tab getModelItem(int index) { return f.tabPane().getTabs().get(index); }
                @Override protected int getItemCount() { return f.tabPane().getTabs().size(); }
                @Override public void select(Tab tab) {
                    throw new IllegalStateException("synthetic installation selection failure");
                }
            }));
            assertFalse(f.call(() -> f.recovery.restore(f.draft)));
            assertEquals(TabCloseOutcome.COMPLETED,
                    f.tabs.closeAllManagedTabsMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS));
            f.fx(() -> {
                assertNull(f.owner.installedContent(f.draft.id()));
                assertTrue(f.tabPane().getTabs().isEmpty());
                assertEquals(0, ((Map<?, ?>) fieldUnchecked(f.owner, "boundContent")).size());
                assertEquals(0, ((Map<?, ?>) fieldUnchecked(f.owner.runtime(), "handles")).size());
                assertTrue(((java.util.concurrent.atomic.AtomicBoolean)
                        fieldUnchecked(f.created.getFirst(), "resourcesClosed")).get());
                assertTrue(((java.util.concurrent.atomic.AtomicBoolean)
                        fieldUnchecked(f.created.getFirst(), "uiFinalized")).get(),
                        "abort barrier must include FX finalization before fixture cleanup");
            });
            f.offline();
        }
    }

    @Test void initializerFailureHasEarlyAbortOwnershipAndNoInstalledMapping() throws Exception {
        try (Fixture f = new Fixture()) {
            SqlDraftRecoveryTabs recovery = f.call(() -> new SqlDraftRecoveryTabs(f.tabs, f.owner,
                    f::create, ignored -> { throw new IllegalStateException("synthetic initializer failure"); }));
            assertFalse(f.call(() -> recovery.restore(f.draft)));
            assertEquals(TabCloseOutcome.COMPLETED,
                    f.tabs.closeAllManagedTabsMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS));
            f.fx(() -> {
                assertNull(f.owner.installedContent(f.draft.id()));
                assertTrue(f.tabPane().getTabs().isEmpty());
                assertTrue(((java.util.concurrent.atomic.AtomicBoolean)
                        fieldUnchecked(f.created.getFirst(), "resourcesClosed")).get());
                assertTrue(((java.util.concurrent.atomic.AtomicBoolean)
                        fieldUnchecked(f.created.getFirst(), "uiFinalized")).get(),
                        "abort barrier must include FX finalization before fixture cleanup");
            });
            f.offline();
        }
    }

    @Test void explicitChooserIsUnselectedSafeAndChangesOnlyRecoveryIntent() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fx(() -> {
                assertTrue(f.recovery.restore(f.draft));
                f.tabPane().applyCss();
                f.tabPane().layout();
                SqlEditorPane pane = f.created.getFirst();
                Button choose = (Button) pane.getNode().lookup("#sql-draft-connection");
                assertNotNull(choose);
                SqlDraftManagerTest.respondToDialog(choose::fire, dialog -> {
                    ComboBox<?> combo = (ComboBox<?>) dialog.lookup(".combo-box");
                    assertNull(combo.getSelectionModel().getSelectedItem());
                    assertTrue(dialog.lookupButton(ButtonType.OK).isDisabled());
                    assertFalse(combo.getItems().toString().contains("SECRET"));
                    combo.getSelectionModel().selectFirst();
                    ((Button) dialog.lookupButton(ButtonType.OK)).fire();
                });
                assertNull(f.context.getActiveConnection());
                SqlDraftRecoveryIntent intent = (SqlDraftRecoveryIntent) fieldUnchecked(pane, "recoveryIntent");
                assertEquals("replacement", intent.connectionId());
                assertEquals(DbType.ORACLE, intent.connectionType());
                assertNull(((SqlEditorConnectionAdmission) fieldUnchecked(pane, "admission")).pinned());
            });
            f.flush(f.created.getFirst());
            var snapshot = f.call(() -> f.owner.runtime().refresh()).get(5, TimeUnit.SECONDS).snapshot();
            assertEquals(f.draft.sql(), snapshot.drafts().getFirst().sql(), "connection-only edit preserves raw CRLF/CR");
            f.offline();
        }
    }

    @Test void dialogUnsubscribesWithoutStoppingApplicationWriter() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fx(() -> SqlDraftManagerTest.respondToDialog(
                    () -> SqlDraftManagerDialog.show(f.owner, null, null, ignored -> false), dialog -> {
                        assertEquals(1, ((java.util.Set<?>) fieldUnchecked(f.owner, "observers")).size());
                    }));
            f.fx(() -> {
                assertEquals(0, ((java.util.Set<?>) fieldUnchecked(f.owner, "observers")).size());
                assertNotEquals(SqlDraftCoordinator.Mode.CLOSED, f.owner.runtime().mode());
            });
            f.ready();
            assertTrue(f.call(() -> f.recovery.restore(f.draft)));
            f.fx(() -> f.created.getFirst().setSqlText("still writable"));
            f.flush(f.created.getFirst());
            assertEquals("still writable", f.call(() -> f.owner.runtime().refresh())
                    .get(5, TimeUnit.SECONDS).snapshot().drafts().getFirst().sql());
        }
    }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final FxTaskRunner runner = new FxTaskRunner();
        final SessionContext context = new SessionContext();
        final List<SqlEditorPane> created = new ArrayList<>();
        final AppSettings settings = new AppSettings(directory.resolve("settings"));
        final SqlHistoryStore history = new SqlHistoryStore(directory.resolve("history"));
        final ShortcutSettings shortcuts = new ShortcutSettings(directory.resolve("shortcuts"));
        final ConnConfig replacement = new ConnConfig("replacement", "Oracle", DbType.ORACLE,
                "SECRET_HOST", 1, "SECRET_DB", "SECRET_USER", "SECRET_PASSWORD", Map.of());
        final SqlDraft draft = new SqlDraft(UUID.randomUUID(), System.currentTimeMillis(), "missing",
                DbType.POSTGRESQL, "Saved", "  schema  ", "select a.\r\nfrom t a;\r-- raw");
        final ContentTabPane tabs;
        final SqlDraftUi owner;
        final SqlDraftRecoveryTabs recovery;

        Fixture() throws Exception {
            probe.manager.register(replacement);
            tabs = call(ContentTabPane::new);
            owner = call(() -> new SqlDraftUi(directory.resolve("drafts")));
            recovery = call(() -> {
                new Scene((TabPane) tabs.getNode(), 1000, 700);
                return new SqlDraftRecoveryTabs(tabs, owner, this::create,
                        pane -> pane.installRecoveryConnectionChooser(() -> List.of(replacement)));
            });
            try {
                ready();
            } catch (Exception | Error failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        SqlEditorPane create(SqlDraft record) {
            SqlEditorPane pane = SqlEditorPane.recoverDraft(context, probe.manager,
                    new ObjectTreeService(probe.manager), settings, null, record, history, shortcuts, runner);
            created.add(pane);
            return pane;
        }
        TabPane tabPane() { return (TabPane) tabs.getNode(); }
        void ready() throws Exception {
            CountDownLatch ready = new CountDownLatch(1);
            AutoCloseable observer = call(() -> {
                Runnable check = () -> {
                    if (!owner.runtime().managementPending()) ready.countDown();
                };
                var registered = owner.observe(check);
                check.run();
                return registered;
            });
            try { assertTrue(ready.await(5, TimeUnit.SECONDS)); }
            finally { call(() -> { observer.close(); return null; }); }
        }
        void flush(SqlEditorPane pane) throws Exception {
            var future = call(() -> {
                SqlDraftEditorBinding binding = (SqlDraftEditorBinding) fieldUnchecked(pane, "draftBinding");
                return ((SqlDraftCoordinator.Handle) fieldUnchecked(binding, "handle")).flush();
            });
            future.get(5, TimeUnit.SECONDS);
            fx(() -> {});
        }
        void offline() {
            assertEquals(0, probe.providers.get(), "provider resolution");
            assertEquals(0, probe.sessions.get(), "session construction");
            assertEquals(0, probe.metadata.get(), "metadata access");
            assertEquals(0, probe.network.get(), "network access");
        }
        <T> T call(Callable<T> work) throws Exception { return FxUiTestSupport.call(work); }
        void fx(Runnable work) throws Exception { call(() -> { work.run(); return null; }); }
        public void close() throws Exception {
            try {
                for (SqlEditorPane pane : created) {
                    pane.closeResources();
                    fx(pane::finalizeCloseOnFx);
                }
            } finally {
                try { owner.closeFromBackground(); }
                finally { runner.close(); probe.manager.closeAll(); }
            }
        }
    }

    private static Object fieldUnchecked(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
