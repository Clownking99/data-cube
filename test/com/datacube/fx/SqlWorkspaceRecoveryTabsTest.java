package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.DbType;
import com.datacube.spi.model.ConnConfig;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javafx.scene.Scene;
import javafx.scene.control.*;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlWorkspaceRecoveryTabsTest {
    @TempDir Path directory;

    @Test void orderedPartialRestoreClampsNewControlAndPreservesEditedReuseAndUnrelatedSlots() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft a = f.seed("select '😀';\r\n-- alpha"), b = f.seed("saved B"), bad = f.seed("failed");
            UUID missing = UUID.randomUUID();
            SqlWorkspace saved = new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(a.id(), 999, 1),
                    new SqlWorkspace.Entry(bad.id(), 3, 1), new SqlWorkspace.Entry(missing, 0, 0),
                    new SqlWorkspace.Entry(b.id(), 1, 5)), a.id());
            f.save(saved);
            f.fx(() -> {
                Tab otherA = f.tabs.openTab("other A", new Label("A"));
                assertTrue(f.single.restore(b));
                Tab existing = f.tab(b.id());
                CodeArea editor = f.editor(b.id());
                editor.replaceText("unsaved current B"); editor.selectRange(7, 2);
                f.schema(b.id()).setText("edited schema");
                Tab otherB = f.tabs.openTab("other B", new Label("B"));
                Tab otherC = f.tabs.openTab("other C", new Label("C"));
                f.failIds.add(bad.id());
                var result = f.batch.restore(SqlWorkspaceRecovery.resolve(saved, List.of(a, b, bad)));
                Tab restored = f.tab(a.id());
                assertEquals(new SqlWorkspaceRecoveryTabs.Result(1, 1, 1, 1), result);
                assertEquals(List.of(otherA, restored, otherB, otherC, existing), List.copyOf(f.tabPane().getTabs()));
                assertSame(restored, f.tabPane().getSelectionModel().getSelectedItem());
                assertEquals("unsaved current B", editor.getText());
                assertEquals("edited schema", f.schema(b.id()).getText());
                assertEquals(7, editor.getAnchor()); assertEquals(2, editor.getCaretPosition());
                assertEquals("select '😀';\n-- alpha", f.editor(a.id()).getText());
                assertEquals(f.editor(a.id()).getLength(), f.editor(a.id()).getAnchor());
                assertEquals(1, f.editor(a.id()).getCaretPosition());
                int factories = f.created.size();
                f.failIds.clear();
                var again = f.batch.restore(SqlWorkspaceRecovery.resolve(
                        new SqlWorkspace(2, List.of(saved.entries().getFirst(), saved.entries().getLast()), b.id()), List.of(a, b)));
                assertEquals(new SqlWorkspaceRecoveryTabs.Result(0, 2, 0, 0), again);
                assertEquals(factories, f.created.size());
                assertSame(existing, f.tabPane().getSelectionModel().getSelectedItem());
                assertNotNull(existing.getOnCloseRequest(), "managed close guard retained by permutation");
                assertNull(f.context.getActiveConnection());
            });
            f.offline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "failed", "null-existing", "null-empty"})
    void selectedFallbackUsesFirstSuccessExceptNullPreservesPriorTab(String selection) throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft a = f.seed("alpha"), b = f.seed("");
            UUID missing = UUID.randomUUID();
            UUID selected = selection.startsWith("null") ? null : selection.equals("missing") ? missing : b.id();
            SqlWorkspace saved = new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(b.id(), 20, 9),
                    new SqlWorkspace.Entry(a.id(), 4, 1), new SqlWorkspace.Entry(missing, 0, 0)), selected);
            f.save(saved);
            f.fx(() -> {
                Tab previous = selection.equals("null-empty") ? null : f.tabs.openTab("previous", new Label("previous"));
                if (selection.equals("failed")) f.failIds.add(b.id());
                var result = f.batch.restore(SqlWorkspaceRecovery.resolve(saved, List.of(a, b)));
                assertEquals(new SqlWorkspaceRecoveryTabs.Result(selection.equals("failed") ? 1 : 2, 0, 1,
                        selection.equals("failed") ? 1 : 0), result);
                Tab expected = selection.equals("null-existing") ? previous : f.tab(selection.equals("failed") ? a.id() : b.id());
                assertSame(expected, f.tabPane().getSelectionModel().getSelectedItem());
                assertEquals("alpha", f.editor(a.id()).getText());
                assertEquals(4, f.editor(a.id()).getAnchor()); assertEquals(1, f.editor(a.id()).getCaretPosition());
                if (!selection.equals("failed")) {
                    assertEquals("", f.editor(b.id()).getText());
                    assertEquals(0, f.editor(b.id()).getAnchor()); assertEquals(0, f.editor(b.id()).getCaretPosition());
                }
            });
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void zeroSuccessKeepsSelectionOldManifestAndInactiveSessionForRetry(boolean allFailed) throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft a = f.seed("alpha");
            SqlWorkspace saved = new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(a.id(), 4, 2)), a.id());
            f.save(saved);
            f.fx(() -> {
                Tab prior = f.tabs.openTab("prior", new Label("prior"));
                f.failIds.add(a.id());
                var result = f.batch.restore(SqlWorkspaceRecovery.resolve(saved, allFailed ? List.of(a) : List.of()));
                assertEquals(new SqlWorkspaceRecoveryTabs.Result(0, 0, allFailed ? 0 : 1, allFailed ? 1 : 0), result);
                assertEquals(List.of(prior), List.copyOf(f.tabPane().getTabs()));
                assertSame(prior, f.tabPane().getSelectionModel().getSelectedItem());
                assertEquals(SqlWorkspaceActivity.Status.IDLE, f.workspace.owner().status());
                f.now = 10000; f.workspace.pulse();
            });
            assertEquals(saved, f.snapshot().workspace());
            f.fx(() -> {
                f.failIds.clear();
                assertEquals(new SqlWorkspaceRecoveryTabs.Result(1, 0, 0, 0), f.batch.restore(SqlWorkspaceRecovery.resolve(saved, List.of(a))));
                assertEquals("alpha", f.editor(a.id()).getText());
            });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"renamed", "missing", "same-name", "changed-type", "schema-missing"})
    void offlineRestorePreservesCheckpointIntentWithoutResolvingProvider(String scenario) throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft draft = f.seed("saved-id", DbType.POSTGRESQL, "Saved", " unavailable schema ", "select a;\r\n-- raw");
            if (!scenario.equals("missing")) f.probe.manager.register(new ConnConfig(
                    scenario.equals("same-name") ? "different-id" : "saved-id",
                    scenario.equals("renamed") ? "Renamed" : "Saved",
                    scenario.equals("changed-type") ? DbType.ORACLE : DbType.POSTGRESQL,
                    "example.invalid", 1, "synthetic", "synthetic", "", Map.of()));
            f.fx(() -> {
                var resolution = SqlWorkspaceRecovery.resolve(new SqlWorkspace(1,
                        List.of(new SqlWorkspace.Entry(draft.id(), 8, 2)), draft.id()), List.of(draft));
                assertEquals(new SqlWorkspaceRecoveryTabs.Result(1, 0, 0, 0), f.batch.restore(resolution));
                assertEquals("select a;\n-- raw", f.editor(draft.id()).getText());
                assertEquals(draft.schema(), f.schema(draft.id()).getText());
                var intent = (SqlDraftRecoveryIntent) get(f.created.getFirst(), "recoveryIntent");
                assertEquals(draft.connectionId(), intent.connectionId());
                assertEquals(draft.connectionType(), intent.connectionType());
                assertNull(f.context.getActiveConnection());
                assertNull(((SqlEditorConnectionAdmission) get(f.created.getFirst(), "admission")).pinned());
            });
            assertEquals(draft.sql(), f.call(() -> f.owner.runtime().refresh()).get(5, TimeUnit.SECONDS).snapshot().drafts().getFirst().sql());
            f.offline();
        }
    }

    @Test void involvedSlotPermutationPreservesUnrelatedSqlAndAllManagedCloseGuards() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft a = f.seed("alpha"), b = f.seed("beta"), other = f.seed("unrelated SQL");
            f.fx(() -> {
                Tab otherA = f.tabs.openTab("other A", new Label("A"));
                assertTrue(f.single.restore(b));
                assertTrue(f.single.restore(other)); Tab unrelatedSql = f.tab(other.id());
                assertTrue(f.single.restore(a)); Tab otherC = f.tabs.openTab("other C", new Label("C"));
                var result = f.batch.restore(SqlWorkspaceRecovery.resolve(new SqlWorkspace(1,
                        List.of(new SqlWorkspace.Entry(a.id(), 4, 1), new SqlWorkspace.Entry(b.id(), 3, 1)), a.id()), List.of(a, b, other)));
                assertEquals(new SqlWorkspaceRecoveryTabs.Result(0, 2, 0, 0), result);
                assertEquals(List.of(otherA, f.tab(a.id()), unrelatedSql, f.tab(b.id()), otherC), List.copyOf(f.tabPane().getTabs()));
                assertEquals("unrelated SQL", f.editor(other.id()).getText());
                assertEquals(3, f.created.size());
            });
            assertEquals(TabCloseOutcome.COMPLETED, f.tabs.closeAllManagedTabsMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS));
            f.fx(() -> {
                assertNull(f.owner.installedContent(a.id())); assertNull(f.owner.installedContent(b.id()));
                assertNull(f.owner.installedContent(other.id()));
                assertEquals(List.of("other A", "other C"), f.tabPane().getTabs().stream().map(Tab::getText).toList());
                for (var pane : f.created) {
                    assertTrue(((java.util.concurrent.atomic.AtomicBoolean) get(pane, "resourcesClosed")).get());
                    assertTrue(((java.util.concurrent.atomic.AtomicBoolean) get(pane, "uiFinalized")).get());
                }
            });
        }
    }

    @Test void recoverySuppressesIntermediateCaptureAndRejectsNestedBatch() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft a = f.seed("alpha"), b = f.seed("beta");
            var resolution = SqlWorkspaceRecovery.resolve(new SqlWorkspace(1,
                    List.of(new SqlWorkspace.Entry(a.id(), 4, 1), new SqlWorkspace.Entry(b.id(), 3, 1)), a.id()), List.of(a, b));
            f.fx(() -> {
                assertSame(f.workspace, f.owner.workspace());
                assertTrue(f.workspace.beginRecovery());
                assertEquals(new SqlWorkspaceRecoveryTabs.Result(0, 0, 0, 2), f.batch.restore(resolution));
                assertEquals(0, f.created.size());
                f.workspace.endRecovery(false);
                assertEquals(SqlWorkspaceActivity.Status.IDLE, f.workspace.owner().status());
                SqlDraftRecoveryTabs single = new SqlDraftRecoveryTabs(f.tabs, f.owner, f::create, ignored -> {
                    f.workspace.activity(); f.workspace.pulse();
                    assertEquals(SqlWorkspaceActivity.Status.IDLE, f.workspace.owner().status(), "no partial layout activation");
                });
                var batch = new SqlWorkspaceRecoveryTabs(f.tabs, f.owner, single);
                assertEquals(new SqlWorkspaceRecoveryTabs.Result(2, 0, 0, 0), batch.restore(resolution));
                assertEquals(SqlWorkspaceActivity.Status.PENDING, f.workspace.owner().status());
                assertEquals(List.of(new SqlWorkspace.Entry(a.id(), 4, 1), new SqlWorkspace.Entry(b.id(), 3, 1)), f.workspace.capture().entries());
                assertEquals(a.id(), f.workspace.capture().selectedDraftId());
                f.workspace.close();
                assertEquals(new SqlWorkspaceRecoveryTabs.Result(0, 0, 0, 2), batch.restore(resolution));
                assertEquals(2, f.created.size());
            });
        }
    }

    static final class Fixture implements AutoCloseable {
        final Path root;
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final FxTaskRunner runner = new FxTaskRunner();
        final SessionContext context = new SessionContext();
        final List<SqlEditorPane> created = new ArrayList<>();
        final Set<UUID> failIds = new HashSet<>();
        final Map<SqlEditorPane, UUID> paneIds = new IdentityHashMap<>();
        final AtomicInteger decisions = new AtomicInteger();
        final AppSettings settings;
        final SqlHistoryStore history;
        final ShortcutSettings shortcuts;
        final ContentTabPane tabs;
        final SqlDraftUi owner;
        final SqlWorkspaceUi workspace;
        final SqlDraftRecoveryTabs single;
        final SqlWorkspaceRecoveryTabs batch;
        long now;

        Fixture(Path directory) throws Exception {
            root = directory.resolve(UUID.randomUUID().toString());
            settings = new AppSettings(root.resolve("settings"));
            history = new SqlHistoryStore(root.resolve("history"));
            shortcuts = new ShortcutSettings(root.resolve("shortcuts"));
            tabs = call(ContentTabPane::new);
            owner = call(() -> new SqlDraftUi(root.resolve("drafts")));
            await(() -> !owner.runtime().managementPending());
            workspace = call(() -> {
                new Scene(tabPane(), 1000, 700);
                return owner.attachWorkspace(tabs, () -> now, () -> {
                    decisions.incrementAndGet();
                    return CompletableFuture.completedFuture(SqlWorkspaceUi.Decision.CANCEL);
                });
            });
            single = call(() -> new SqlDraftRecoveryTabs(tabs, owner, this::create, pane -> {
                if (failIds.contains(paneIds.get(pane)))
                    throw new IllegalStateException("synthetic initializer failure");
            }));
            batch = call(() -> new SqlWorkspaceRecoveryTabs(tabs, owner, single));
        }
        SqlEditorPane create(SqlDraft draft) {
            SqlEditorPane pane = SqlEditorPane.recoverDraft(context, probe.manager,
                    new ObjectTreeService(probe.manager), settings, null, draft, history, shortcuts, runner);
            created.add(pane); paneIds.put(pane, draft.id()); return pane;
        }
        SqlDraft seed(String sql) throws Exception { return seed("missing", DbType.POSTGRESQL, "Saved", " schema ", sql); }
        SqlDraft seed(String connection, DbType type, String name, String schema, String sql) throws Exception {
            UUID id = UUID.randomUUID();
            var handle = call(() -> owner.runtime().attach(id, sql.isEmpty() ? 1L : null, new SqlDraftCoordinator.Source() {
                public boolean hasText() { return !sql.isEmpty(); }
                public SqlDraft capture(UUID key, long at) { return new SqlDraft(key, at, connection, type, name, schema, sql); }
            }));
            if (sql.isEmpty()) fx(handle::edited);
            call(handle::flush).get(5, TimeUnit.SECONDS);
            fx(handle::detach);
            var records = call(() -> owner.runtime().refresh()).get(5, TimeUnit.SECONDS).snapshot().drafts();
            await(() -> !owner.runtime().managementPending());
            return records.stream().filter(draft -> draft.id().equals(id)).findFirst().orElseThrow();
        }
        void save(SqlWorkspace saved) throws Exception { call(() -> owner.runtime().saveWorkspace(saved)).get(5, TimeUnit.SECONDS); }
        SqlWorkspaceStore.Snapshot snapshot() throws Exception { return call(() -> owner.runtime().workspaceSnapshot()).get(5, TimeUnit.SECONDS); }
        TabPane tabPane() { return (TabPane) tabs.getNode(); }
        Tab tab(UUID id) { return tabPane().getTabs().stream().filter(t -> t.getContent() == owner.installedContent(id)).findFirst().orElseThrow(); }
        CodeArea editor(UUID id) { return (CodeArea) get(owner.installedBinding(tab(id).getContent()), "editor"); }
        TextField schema(UUID id) { return (TextField) get(owner.installedBinding(tab(id).getContent()), "schema"); }
        void await(BooleanSupplier condition) throws Exception {
            CountDownLatch done = new CountDownLatch(1);
            AutoCloseable listener = call(() -> {
                Runnable check = () -> { if (condition.getAsBoolean()) done.countDown(); };
                AutoCloseable subscription = owner.observe(check); check.run(); return subscription;
            });
            try { assertTrue(done.await(5, TimeUnit.SECONDS), "FX condition did not settle"); }
            finally { call(() -> { listener.close(); return null; }); }
        }
        <T> T call(Callable<T> action) throws Exception { return FxUiTestSupport.call(action); }
        void fx(Runnable action) throws Exception { call(() -> { action.run(); return null; }); }
        void offline() {
            assertEquals(0, probe.providers.get()); assertEquals(0, probe.sessions.get());
            assertEquals(0, probe.metadata.get()); assertEquals(0, probe.network.get());
        }
        public void close() throws Exception {
            try {
                tabs.closeAllManagedTabsMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS);
                for (SqlEditorPane pane : created) { pane.closeResources(); fx(pane::finalizeCloseOnFx); }
            }
            finally { try { owner.closeFromBackground(); } finally { runner.close(); probe.manager.closeAll(); } }
            offline();
        }
    }
    static Object get(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
