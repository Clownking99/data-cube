# SQL Draft Offline Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore an editor's exact text and connection intent without creating a database session or fetching metadata.

**Architecture:** Keep a credential-free recovery intent separate from the existing immutable session admission. An explicit recovery factory bypasses eager construction and all passive metadata paths. Existing execution/admission performs a fresh in-memory identity lookup; normal editors keep their current behavior.

**Tech Stack:** Java 25, JavaFX 25, existing Gradle/JUnit Jupiter; no dependencies.

## Global Constraints

- User delegated routine product/design decisions and approved isolated worktree development; no further routine confirmation is required.
- Work only in `D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery`; never inspect or modify `.testagent/`.
- No live database/network calls, real connection/history/credential files, default credential constructor, global profile changes, push/tag/release or main merge in this task.
- Restore only exact text and saved ID/type/name. No same-name or global-selection fallback. Revalidate with `ConnectionManager.config` before first explicit admission; after admission preserve immutable pinned config.
- The normal public constructor and both existing immediately-owned `openEditorSession(editorConnection)` call sites remain intact. No third session creation site.
- Guard constructor, global-selection following, `prewarm`, `membersFor`, `loadColumnsAsync` and Ctrl-click. Restoration and text completion must not resolve a provider or create a session.
- Draft capture keeps raw SQL/schema and missing connection intent. Do not reuse history normalization or store credentials in the intent.
- RichTextFX displays normalized LF paragraphs. Keep a bounded original-SQL override for a recovered draft until its SQL is actually edited; schema/connection-only edits must preserve original CRLF/CR bytes. Once SQL is edited, the new control text is authoritative. Highlight the normalized control text, not a differently sized input string.
- Closing retains the completed autosave/transaction lifecycle; no changes to close decisions or result-export status.
- This independently testable factory is not the application recovery manager. Managed-tab duplicate restore, chooser UI, restart and desktop acceptance remain explicit following tasks before P1 merge.

---

### Task 1: Explicit offline recovery factory and identity gate

**Files:**
- Create: `src/com/datacube/fx/SqlDraftRecoveryIntent.java`
- Modify: `src/com/datacube/fx/SqlEditorPane.java`
- Create: `test/com/datacube/config/DraftTestCipher.java`
- Create: `test/com/datacube/service/DraftConnectionProbe.java`
- Create: `test/com/datacube/fx/SqlEditorDraftRecoveryTest.java`

**Interfaces:**
- Consumes existing `SqlDraft`, nullable in-memory `ConnectionManager.config(String)`, `SqlEditorConnectionAdmission`, `SqlDraftCoordinator`, `FxUiTestSupport.call(Callable<T>)`.
- Produces package-private `SqlEditorPane.recoverDraft(SessionContext, ConnectionManager, ObjectTreeService, AppSettings, BiConsumer<String,TableRef>, SqlDraft, SqlHistoryStore, ShortcutSettings, FxTaskRunner)` and `boolean chooseRecoveryConnection(ConnConfig)` for the following managed recovery UI.
- `chooseRecoveryConnection` changes intent only, returning false when normal/pinned/closing/invalid. It does not resolve a provider or open a session. The following UI supplies a choice from an in-memory snapshot, not a credentials-file reload.

- [x] **Step 1: Add the complete isolated fixture and recovery tests.**

`test/com/datacube/config/DraftTestCipher.java`:

```java
package com.datacube.config;

/** Test-only cipher with no profile, platform credential service or filesystem access. */
public final class DraftTestCipher {
    private DraftTestCipher() {}

    public static CredentialCipher create() {
        CredentialProtector inert = new CredentialProtector() {
            public String scheme() { return "synthetic"; }
            public String protect(String plain) { throw new AssertionError("No credentials in recovery fixture"); }
            public String unprotect(String payload) { throw new AssertionError("No credentials in recovery fixture"); }
        };
        return new CredentialCipher(inert, inert, inert);
    }
}
```

`test/com/datacube/service/DraftConnectionProbe.java`:

```java
package com.datacube.service;

import com.datacube.config.DraftTestCipher;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.SqlRunner;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/** Provider and session-construction counters; every network path is rejected. */
public final class DraftConnectionProbe {
    public final AtomicInteger providers = new AtomicInteger();
    public final AtomicInteger sessions = new AtomicInteger();
    public final AtomicInteger metadata = new AtomicInteger();
    public final AtomicInteger network = new AtomicInteger();
    public final ConnectionManager manager;

    public DraftConnectionProbe() {
        SqlRunner runner = (SqlRunner) Proxy.newProxyInstance(SqlRunner.class.getClassLoader(),
                new Class<?>[]{SqlRunner.class}, (proxy, method, args) -> {
                    throw new AssertionError("No SQL execution in offline factory tests");
                });
        DatabaseProvider provider = (DatabaseProvider) Proxy.newProxyInstance(
                DatabaseProvider.class.getClassLoader(), new Class<?>[]{DatabaseProvider.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sqlRunner")) {
                        sessions.incrementAndGet();
                        return runner;
                    }
                    if (method.getName().equals("dialect") || method.getName().equals("metadataReader")) {
                        metadata.incrementAndGet();
                        throw new IllegalStateException("Synthetic metadata access rejected");
                    }
                    if (method.getName().equals("connectionFactory")) {
                        network.incrementAndGet();
                        throw new IllegalStateException("Synthetic network access rejected");
                    }
                    throw new AssertionError("Unexpected provider method: " + method.getName());
                });
        manager = new ConnectionManager(DraftTestCipher.create(), type -> {
            providers.incrementAndGet();
            return provider;
        });
    }
}
```

`test/com/datacube/fx/SqlEditorDraftRecoveryTest.java`:

```java
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
                FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
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
```

- [x] **Step 2: Run RED against compilation-capable stubs.**

Add factory and chooser stubs to `SqlEditorPane` so the tests compile; do not count a compiler error as RED:

```java
    static SqlEditorPane recoverDraft(SessionContext session, ConnectionManager connections,
            ObjectTreeService treeSvc, AppSettings settings,
            java.util.function.BiConsumer<String, TableRef> openDesigner, SqlDraft draft,
            SqlHistoryStore history, ShortcutSettings shortcuts, FxTaskRunner runner) {
        throw new UnsupportedOperationException("Recovery not implemented");
    }

    boolean chooseRecoveryConnection(ConnConfig choice) {
        return false;
    }
```

Run in PowerShell, preserving the prior environment:

```powershell
$draftOldOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$draftOldOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --tests com.datacube.fx.SqlEditorDraftRecoveryTest --rerun-tasks --no-daemon --console=plain
    $draftExit = $LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS = $draftOldOptions }
exit $draftExit
```

Expected: recovery cases fail due to the explicit factory stub. The normal-constructor case also probes CRLF input/highlighting against the baseline; record its actual behavior, not an assumed failure. Record actual XML and exit status.

- [x] **Step 3: Implement the recovery intent and exact pane changes.**

`src/com/datacube/fx/SqlDraftRecoveryIntent.java`:

```java
package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.util.function.Function;

/** Display/storage intent only: no credentials and no ownership of a live connection. */
record SqlDraftRecoveryIntent(String connectionId, DbType connectionType, String connectionName) {
    static SqlDraftRecoveryIntent from(ConnConfig config) {
        return config == null ? new SqlDraftRecoveryIntent(null, null, null)
                : new SqlDraftRecoveryIntent(config.id(), config.type(), config.name());
    }

    ConnConfig resolve(Function<String, ConnConfig> lookup) {
        if (connectionId == null || connectionType == null) return null;
        ConnConfig config = lookup.apply(connectionId);
        return config != null && connectionId.equals(config.id()) && connectionType == config.type()
                && config.type() != DbType.REDIS ? config : null;
    }

    @Override public String toString() { return "SqlDraftRecoveryIntent"; }
}
```

Add these two fields alongside the existing `draftBinding` field. Keep the original public constructor signature, delegating to a new private overload with trailing `SqlDraft recoveredDraft`; the original constructor body moves unchanged into this overload except for the explicitly listed changes:

```java
    private SqlDraftRecoveryIntent recoveryIntent;
    private String recoveredUneditedSql;
```

```java
    public SqlEditorPane(SessionContext session, ConnectionManager connections, ObjectTreeService treeSvc,
                         AppSettings settings, java.util.function.BiConsumer<String, TableRef> openDesigner,
                         ConnConfig boundConn, String initialSchema, SqlHistoryStore history,
                         ShortcutSettings shortcuts, FxTaskRunner runner) {
        this(session, connections, treeSvc, settings, openDesigner, boundConn, initialSchema,
                history, shortcuts, runner, null);
    }

    static SqlEditorPane recoverDraft(SessionContext session, ConnectionManager connections,
            ObjectTreeService treeSvc, AppSettings settings,
            java.util.function.BiConsumer<String, TableRef> openDesigner, SqlDraft draft,
            SqlHistoryStore history, ShortcutSettings shortcuts, FxTaskRunner runner) {
        java.util.Objects.requireNonNull(draft, "draft");
        return new SqlEditorPane(session, connections, treeSvc, settings, openDesigner, null,
                draft.schema(), history, shortcuts, runner, draft);
    }
```

The private overload has this exact signature:

```java
    private SqlEditorPane(SessionContext session, ConnectionManager connections, ObjectTreeService treeSvc,
                         AppSettings settings, java.util.function.BiConsumer<String, TableRef> openDesigner,
                         ConnConfig boundConn, String initialSchema, SqlHistoryStore history,
                         ShortcutSettings shortcuts, FxTaskRunner runner, SqlDraft recoveredDraft)
```

After `this.shortcuts = shortcuts;` but before `ConstructionOwner`/`build`, assign:

```java
        this.recoveryIntent = recoveredDraft == null ? null : new SqlDraftRecoveryIntent(
                recoveredDraft.connectionId(), recoveredDraft.connectionType(), recoveredDraft.connectionName());
```

At the beginning of the active-connection listener, before the existing `if (admission.pinned() == null)`, insert:

```java
                if (recoveryIntent != null) return;
```

Replace only the original initial-schema block with this, still inside constructor ownership and before `construction.commit()`:

```java
            if (recoveredDraft != null) {
                schemaField.setText(initialSchema == null ? "" : initialSchema);
                setSqlText(recoveredDraft.sql());
                if (!recoveredDraft.sql().equals(editorArea.getText())) {
                    recoveredUneditedSql = recoveredDraft.sql();
                }
            } else if (initialSchema != null && !initialSchema.isBlank()) {
                schemaField.setText(initialSchema.trim());
            }
```

Inside the existing draft `Source.capture`, replace its connection lookup and return with:

```java
                        SqlDraftRecoveryIntent identity = recoveryPassive()
                                ? recoveryIntent : SqlDraftRecoveryIntent.from(currentConn());
                        return new SqlDraft(draftId, at, identity.connectionId(), identity.connectionType(),
                                identity.connectionName(), schemaField.getText(),
                                recoveredUneditedSql == null ? editorArea.getText() : recoveredUneditedSql);
```

Replace the existing one-line text-property highlighting listener in `editor()` with:

```java
        editorArea.textProperty().addListener((obs, oldText, newText) -> {
            recoveredUneditedSql = null;
            applyHighlighting(newText);
        });
```

Replace `setSqlText`'s explicit `applyHighlighting(sql)` call with `applyHighlighting(editorArea.getText())`. The constructor sets the raw override only after initial assignment finishes. No second per-keystroke SQL copy or disk action is introduced. The override is at most one validated, bounded recovered draft and becomes unreachable on actual SQL editing or pane disposal.

Replace `currentConn()` and add the two package/private methods below.

```java
    private ConnConfig currentConn() {
        ConnConfig pinned = admission.pinned();
        if (pinned != null) return pinned;
        if (recoveryIntent != null) return recoveryIntent.resolve(connections::config);
        ConnConfig candidate = session.getActiveConnection();
        return candidate == null || candidate.type() == DbType.REDIS ? null : candidate;
    }

    private boolean recoveryPassive() {
        return recoveryIntent != null && admission.pinned() == null;
    }

    boolean chooseRecoveryConnection(ConnConfig choice) {
        if (!recoveryPassive() || draftEditingBlocked() || !sessionOperations.snapshot().accepting()
                || choice == null || choice.id() == null || choice.id().isBlank()
                || (choice.type() != DbType.POSTGRESQL && choice.type() != DbType.ORACLE)) return false;
        recoveryIntent = SqlDraftRecoveryIntent.from(choice);
        renderDisconnectedCandidate(currentConn());
        draftEdited();
        return true;
    }
```

Replace `admitCurrentConnection()` with the complete method below. The explicit missing-intent rejection is before pinning; the lookup is an in-memory read, not provider resolution:

```java
    private ConnConfig admitCurrentConnection() {
        ConnConfig candidate = currentConn();
        if (recoveryPassive() && candidate == null) {
            throw new IllegalStateException("草稿连接不可用，请重新选择连接");
        }
        ConnConfig pinned = admission.admit(candidate);
        editorConnection = pinned;
        if (jdbcSession == null) connectionBadge.setText("🔗 " + pinned.name() + " · 未连接");
        renderConnectionGuidance();
        draftEdited();
        if (recoveryIntent != null) prewarm(pinned);
        return pinned;
    }
```

`guidance()` must resolve the correct candidate:

```java
    private SqlConnectionGuidance guidance() {
        return SqlConnectionGuidance.from(admission.pinned(),
                recoveryIntent == null ? session.getActiveConnection() : currentConn());
    }
```

In `renderConnectionGuidance`, replace its three text/visibility assignments, retaining the environment/read-only badge assignments:

```java
        String text = recoveryPassive()
                ? (state.hasConnection() ? "草稿已恢复，尚未连接；执行时将绑定原连接。"
                    : "草稿连接不可用，请为此草稿重新选择连接后执行。")
                : state.text();
        connectionGuidance.setText(text);
        connectionGuidance.setVisible(!text.isEmpty());
        connectionGuidance.setManaged(!text.isEmpty());
```

Insert these exact early returns as the first executable line in each named method, leaving all remaining normal-path code intact:

```java
// installMetadataPrewarm(), prewarm(ConnConfig), loadColumnsAsync(...), onCtrlClick(MouseEvent):
        if (recoveryPassive()) return;
// membersFor(String):
        if (recoveryPassive()) return List.of();
```

Keep the existing nested pinned guard in the global-selection listener so source-contract tests still prove normal-editor pinning. No new broad cleanup/refactoring or session opening API is part of this step.

- [x] **Step 4: Verify focused GREEN, then full regression.**

Use Step2 environment wrapper, with this focused command:

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlEditorDraftRecoveryTest --tests com.datacube.fx.SqlEditorDraftIntegrationTest --tests com.datacube.fx.SqlDraftUiTest --tests com.datacube.fx.SqlEditorSessionContractTest --tests com.datacube.fx.SqlEditorConnectionAdmissionTest --rerun-tasks --no-daemon --console=plain
```

Expected zero failures/errors/skips in these focused tests. Full command:

```powershell
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain
```

Record actual XML totals and the three original live Redis/Oracle/PostgreSQL skipped cases separately. Reflection here drives real pane/service code and observes counters, not source-text assertions. `sessions` counts `sqlRunner` requests on the real manager session-construction path; `network` is a rejecting factory-access counter, not evidence of a real socket. The explicit admission/ensure-session test does not claim the final UI execution action has been tested.

- [x] **Step 5: Commit the exact five source/test files and report.**

```powershell
git add src/com/datacube/fx/SqlDraftRecoveryIntent.java src/com/datacube/fx/SqlEditorPane.java test/com/datacube/config/DraftTestCipher.java test/com/datacube/service/DraftConnectionProbe.java test/com/datacube/fx/SqlEditorDraftRecoveryTest.java
git commit -m "feat: restore SQL drafts without passive database access"
```

Report to `.superpowers/sdd/offline-editor-task-1-report.md`: commit, changes, commands, real RED/GREEN observations, counts/skips, concerns. Root performs independent review and final verification. Recovery manager installation/duplicate focus, visible connection chooser, exact restart recovery and whole-P1 merge remain following tasks, not evidence supplied by this factory.

Task complete at `4087945`, independent review Spec compliant / Approved, no Critical/Important findings. Root fresh full:147 suites /1331 total /1328 passed /0 failures/errors /3 original live skips,37s. First RED contained six intended stub failures plus one test-fixture lookup NPE; the latter was repaired with Scene/CSS/layout and is not product-bug evidence. Nonblocking final-P1 follow-up: lone-CR and connection-only edit combinations. Actual manager/chooser/duplicate/restart acceptance remains separate.
