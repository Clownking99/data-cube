# SQL draft editor integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire local SQL draft protection into real editors, expose status and privacy controls, and refuse destructive closure when latest text is not stored.

**Architecture:** One lazy AppShell-owned writer/timer wraps the completed coordinator. Each pane binds after initialization, maintains a separate status row, and gates its existing transaction/cleanup sequence through a draft flush. This task does not implement recovery-manager or connection-free restoration; those are the next integrated P1 task and remain mandatory before main merge.

**Tech Stack:** Java, JavaFX, RichTextFX, JUnit 5, existing Gradle wrapper. No new dependency.

**Review refinement:** [Review amendment 1](2026-08-30-sql-draft-editor-review-amendment.md) supersedes the initial close ordering, restores omitted UI details and adds callback/dialog regressions. Initial implementation f41e1d5 passed regression but did not pass the task review.

## Global Constraints

- Work only in `D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery` on `codex/sql-draft-recovery`; do not read/modify/stage/delete `.testagent/` contents anywhere.
- No real user credentials/history/database access, no push/tag/release, no main merge before complete P1 acceptance.
- Java25 / JavaFX25 / JUnit Jupiter5.11.3；不增加第三方依赖，不改 JDBC、历史文件或导出语义。Production drafts live in `.datacube/sql-drafts/`; tests use explicit temporary directories.
- SQL 草稿仅保存于本机，可能含敏感文本；保留7天，可关闭或清空。关闭草稿不停止原有 SQL 历史记录。
- Separate draft status from existing result `statusLabel` and `resultStatusRevision`.
- Capture exact raw SQL/schema plus stable ID/type/name, no credentials. Bind after initial SQL/schema. Restored savedAt starts clean.
- Coordinator methods and editor reads run on FX. Disk work uses an application-owned executor, not pane tasks. One 250ms timer drives existing idle1s/maxdirty10s scheduling.
- Freeze text/schema/format/comment actions before final snapshot; restore exact prior flags on rejection. Rejected close retains autosave subscriptions.
- Flush before transaction resolution and destructive cleanup. Mandatory draft failure returns REJECTED, not FAILED_PARTIAL. Preserve mandatory non-interactive rollback and existing session ownership contracts.
- Explicit discard applies only to this attempt; it neither deletes a checkpoint nor disables protection. Clear followed by unedited close must not recreate text.
- Preserve `pane -> binding.bind(pane::closeResources)`. Construction-abort cleanup dispatches/awaits detach safely; no FX join.
- Shutdown stops timer and invokes runtime shutdown on FX, awaits from background, then shuts writer executor after drain/lock release.
- Plan code below is an implementation starting point, not permission to retain defects. Report concrete contradictions before expanding scope. Keep actual RED/GREEN evidence and full regression.

---

### Task 1: Visible autosave and safe editor closure

**Files:**
- Create: `src/com/datacube/fx/SqlDraftEditorBinding.java`
- Create: `src/com/datacube/fx/SqlDraftUi.java`
- Modify: `src/com/datacube/fx/SqlEditorPane.java`
- Modify: `src/com/datacube/fx/AppShell.java`
- Modify: `src/com/datacube/fx/SqlAutoComplete.java`
- Test: `test/com/datacube/fx/SqlEditorDraftIntegrationTest.java`
- Test: `test/com/datacube/fx/SqlDraftUiTest.java`

**Interfaces:**
- Consumes public `SqlDraftCoordinator(Path, Executor, Executor, BooleanSupplier, LongSupplier, LongSupplier)`, `attach(UUID, Long, Source)`, `pulse()`, `clear()`, `setEnabled(boolean)`, `refresh()`, `shutdown()`; Handle `edited/retry/status/flush/detach`.
- Produces `SqlEditorPane.bindDraft(SqlDraftCoordinator, UUID, Long, Consumer<SqlDraftEditorBinding>)`, a package-only application integration point; `SqlDraftUi.bind(SqlEditorPane)` and `closeFromBackground()`.

- [x] **Step 1: Add real FX tests first.** Both files deliberately use reflection for new integration seams so the old implementation compiles and fails on missing behavior/API. All storage/settings/history use @TempDir; no default AppShell construction.

`test/com/datacube/fx/SqlEditorDraftIntegrationTest.java`:
```java
package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.spi.model.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.event.Event;
import javafx.stage.Window;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlEditorDraftIntegrationTest {
    @TempDir Path directory;

    @Test void autoSavePreservesRawTextSchemaAndIndependentQueryStatus() throws Exception {
        try (Fixture f = new Fixture("", null, true)) {
            f.fx(() -> {
                f.resultStatus().setText("查询完成");
                f.pane.setSqlText("select '中文';\n");
                f.schema().setText("  raw_schema  ");
            });
            long revision = f.fxValue(() -> (long) field(f.pane, "resultStatusRevision"));
            f.tick(999);
            assertTrue(f.snapshot().drafts().isEmpty());
            f.tick(1000);
            var draft = f.snapshot().drafts().getFirst();
            assertEquals("select '中文';\n", draft.sql());
            assertEquals("  raw_schema  ", draft.schema());
            f.fx(() -> {
                assertEquals("查询完成", f.resultStatus().getText());
                assertEquals(revision, field(f.pane, "resultStatusRevision"));
                assertTrue(f.label("status").getText().contains("已保存"));
                assertTrue(f.label("privacy").getText().contains("关闭草稿不停止原有 SQL 历史记录"));
            });
        }
    }

    @Test void newEmptyEditorDoesNotCreateCheckpoint() throws Exception {
        try (Fixture f = new Fixture("", null, true)) {
            f.tick(20_000);
            assertTrue(f.snapshot().drafts().isEmpty());
            var close = f.beginClose(true);
            assertEquals(CloseGuardOutcome.APPROVED, close.get(5, TimeUnit.SECONDS));
            assertTrue(f.snapshot().drafts().isEmpty());
        }
    }

    @Test void historyInitializationQualifiesButRestoredCheckpointStartsClean() throws Exception {
        try (Fixture f = new Fixture("history text", null, true)) {
            f.tick(1000);
            assertEquals("history text", f.snapshot().drafts().getFirst().sql());
        }
        // Separate directory avoids treating the first editor's stored draft as this restored handle.
        try (Fixture f = new Fixture("restored text", 100_000L, true)) {
            f.tick(1000);
            assertEquals(1, f.snapshot().drafts().size());
            assertTrue(f.snapshot().drafts().stream().noneMatch(d -> d.id().equals(f.id)));
            f.fx(() -> assertTrue(f.label("status").getText().contains("已保存")));
        }
    }

    @Test void closingWaitsForLatestSnapshotAndBlocksProgrammaticEditingActions() throws Exception {
        try (Fixture f = new Fixture("select 1", null, true)) {
            var close = f.beginClose(true);
            assertFalse(close.isDone());
            f.fx(() -> {
                assertFalse(f.editor().isEditable());
                assertTrue(f.schema().isDisable());
                f.pane.setSqlText("replacement");
                invoke(f.pane, "onFormat");
                invoke(f.pane, "toggleLineComment");
                invoke(f.pane, "toggleBlockComment");
                Object completion = field(f.pane, "autoComplete");
                @SuppressWarnings("unchecked") ListView<String> choices = (ListView<String>) field(completion, "list");
                choices.getItems().setAll("changed");
                choices.getSelectionModel().selectFirst();
                invoke(completion, "applySelection");
                Event.fireEvent(f.editor(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SLASH,
                        false, true, false, false));
                assertEquals("select 1", f.editor().getText());
            });
            f.drain();
            assertEquals(CloseGuardOutcome.APPROVED, close.get(5, TimeUnit.SECONDS));
            assertEquals("select 1", f.snapshot().drafts().getFirst().sql());
        }
    }

    @Test void closingCapturesEditNewerThanAlreadyQueuedAutosave() throws Exception {
        try (Fixture f = new Fixture("old", null, true)) {
            f.clock.set(1000);
            f.fx(f.runtime::pulse);
            f.fx(() -> f.pane.setSqlText("newest"));
            var close = f.beginClose(true);
            f.drain();
            assertEquals(CloseGuardOutcome.APPROVED, close.get(5, TimeUnit.SECONDS));
            assertEquals("newest", f.snapshot().drafts().getFirst().sql());
        }
    }

    @Test void initializationRefusesMandatoryCloseAndRetainsSubscriptions() throws Exception {
        try (Fixture f = new Fixture("before init", null, false)) {
            assertEquals(CloseGuardOutcome.REJECTED, f.beginClose(true).get(5, TimeUnit.SECONDS));
            f.fx(() -> {
                assertTrue(f.editor().isEditable());
                assertFalse(f.schema().isDisable());
                assertFalse(((AtomicBoolean) field(f.pane, "resourcesClosed")).get());
                f.pane.setSqlText("after refusal");
            });
            f.drain();
            f.tick(1000);
            assertEquals("after refusal", f.snapshot().drafts().getFirst().sql());
        }
    }

    @Test void writeFailureRefusesMandatoryCloseAndRestoresExactFlagsThenSavesNewEdit() throws Exception {
        try (Fixture f = new Fixture("keep me", null, true)) {
            f.fx(() -> { f.editor().setEditable(false); f.schema().setDisable(true); f.schema().setText("s".repeat(4097)); });
            var close = f.beginClose(true);
            f.drain();
            assertEquals(CloseGuardOutcome.REJECTED, close.get(5, TimeUnit.SECONDS));
            f.fx(() -> {
                assertFalse(f.editor().isEditable());
                assertTrue(f.schema().isDisable());
                assertFalse(((AtomicBoolean) field(f.pane, "resourcesClosed")).get());
                assertTrue(f.label("status").getText().contains("失败"));
                f.editor().setEditable(true); f.schema().setDisable(false);
                f.schema().setText("valid"); f.pane.setSqlText("still editable");
            });
            f.tick(1000);
            assertEquals("still editable", f.snapshot().drafts().getFirst().sql());
        }
    }

    @Test void clearDoesNotResurrectUneditedTextOnClose() throws Exception {
        try (Fixture f = new Fixture("old", null, true)) {
            f.tick(1000);
            assertEquals(1, f.snapshot().drafts().size());
            var clear = f.fxValue(f.runtime::clear);
            f.drain(); assertTrue(clear.get(5, TimeUnit.SECONDS).succeeded());
            assertEquals(CloseGuardOutcome.APPROVED, f.beginClose(true).get(5, TimeUnit.SECONDS));
            assertTrue(f.snapshot().drafts().isEmpty());
        }
    }

    @Test void explicitDisableAllowsCloseWithoutClaimingLatestSaved() throws Exception {
        try (Fixture f = new Fixture("not saved", null, true)) {
            var disable = f.fxValue(() -> f.runtime.setEnabled(false));
            f.drain(); assertTrue(disable.get(5, TimeUnit.SECONDS).succeeded());
            f.tick(1000);
            f.fx(() -> assertEquals("草稿保护已关闭", f.label("status").getText()));
            assertEquals(CloseGuardOutcome.APPROVED, f.beginClose(true).get(5, TimeUnit.SECONDS));
            assertTrue(f.snapshot().drafts().isEmpty());
        }
    }

    @Test void constructionAbortDetachesHandleAndSubscriptions() throws Exception {
        try (Fixture f = new Fixture("before abort", null, true)) {
            f.pane.closeResources();
            f.fx(() -> {
                f.editor().replaceText("after abort");
                var replacement = f.runtime.attach(f.id, null, new SqlDraftCoordinator.Source() {
                    public boolean hasText() { return false; }
                    public SqlDraft capture(UUID id, long at) { throw new AssertionError("empty"); }
                });
                replacement.detach();
            });
            f.tick(1000);
            assertTrue(f.snapshot().drafts().isEmpty());
            assertEquals(1, f.detachments.get());
        }
    }

    @Test void explicitAdmissionUpdatesStoredStableConnectionIdentity() throws Exception {
        try (Fixture f = new Fixture("select 1", null, true)) {
            f.fx(() -> {
                var cfg = new ConnConfig("stable-id", "display name", DbType.POSTGRESQL,
                        "example.invalid", 5432, "db", "user", "", Map.of());
                @SuppressWarnings("unchecked") Set<String> warmed = (Set<String>) field(f.pane, "prewarmed");
                warmed.add(cfg.id());
                f.context.setActiveConnection(cfg);
                invoke(f.pane, "admitCurrentConnection");
                assertNull(field(f.pane, "jdbcSession"));
            });
            f.tick(1000);
            var draft = f.snapshot().drafts().getFirst();
            assertEquals("stable-id", draft.connectionId());
            assertEquals(DbType.POSTGRESQL, draft.connectionType());
            assertEquals("display name", draft.connectionName());
        }
    }

    @Test void interactiveCancelKeepsEditorAndExplicitDiscardKeepsPreviousCheckpoint() throws Exception {
        try (Fixture f = new Fixture("checkpoint", null, true)) {
            f.tick(1000);
            f.fx(() -> { f.pane.setSqlText("latest"); f.schema().setText("s".repeat(4097)); });
            var cancelled = f.beginClose(false);
            f.drain();
            f.dismiss("取消");
            assertEquals(CloseGuardOutcome.REJECTED, cancelled.get(5, TimeUnit.SECONDS));
            f.fx(() -> assertTrue(f.editor().isEditable()));
            assertEquals("checkpoint", f.snapshot().drafts().getFirst().sql());
            var discarded = f.beginClose(false);
            f.drain();
            f.dismiss("放弃本次最新修改并关闭");
            assertEquals(CloseGuardOutcome.APPROVED, discarded.get(5, TimeUnit.SECONDS));
            assertEquals("checkpoint", f.snapshot().drafts().getFirst().sql());
            assertTrue(f.snapshot().protectionEnabled());
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }

    private final class Fixture implements AutoCloseable {
        final Queue<Runnable> writer = new ConcurrentLinkedQueue<>();
        final AtomicLong clock = new AtomicLong();
        final AtomicInteger detachments = new AtomicInteger();
        final UUID id = UUID.randomUUID();
        final FxTaskRunner runner = new FxTaskRunner();
        final SessionContext context = new SessionContext();
        final SqlDraftCoordinator runtime;
        final SqlEditorPane pane;

        Fixture(String initial, Long savedAt, boolean initialized) throws Exception {
            runtime = fxValue(() -> new SqlDraftCoordinator(directory.resolve("drafts"), writer::add,
                    Platform::runLater, Platform::isFxApplicationThread, clock::get, () -> 100_000L));
            if (initialized) drain();
            pane = fxValue(() -> {
                var editor = new SqlEditorPane(context, null, null,
                        new AppSettings(directory.resolve("settings.properties")),
                        (connection, table) -> fail("No designer"), null, null,
                        new SqlHistoryStore(directory.resolve("history.txt")),
                        new ShortcutSettings(directory.resolve("shortcuts.properties")), runner);
                editor.setSqlText(initial);
                return editor;
            });
            try {
                fx(() -> {
                    Method bind = SqlEditorPane.class.getDeclaredMethod("bindDraft", SqlDraftCoordinator.class,
                            UUID.class, Long.class, Consumer.class);
                    bind.setAccessible(true);
                    bind.invoke(pane, runtime, id, savedAt, (Consumer<Object>) ignored -> detachments.incrementAndGet());
                    new Scene((Parent) pane.getNode(), 1200, 800); pane.getNode().applyCss();
                });
            } catch (Throwable failure) { close(); throw failure; }
        }

        void fx(Action action) throws Exception { fxValue(() -> { action.run(); return null; }); }
        <T> T fxValue(Callable<T> action) throws Exception { return FxUiTestSupport.call(action); }
        CodeArea editor() { return (CodeArea) pane.getNode().lookup("#sql-editor"); }
        TextField schema() throws Exception { return (TextField) field(pane, "schemaField"); }
        Label resultStatus() throws Exception { return (Label) field(pane, "statusLabel"); }
        Label label(String suffix) { return (Label) pane.getNode().lookup("#sql-draft-" + suffix); }
        void drain() throws Exception {
            assertFalse(Platform.isFxApplicationThread());
            Runnable job; while ((job = writer.poll()) != null) job.run();
            fx(() -> {});
        }
        void tick(long time) throws Exception {
            clock.set(time); fx(runtime::pulse); drain();
            fx(() -> {
                Object binding = field(pane, "draftBinding");
                invoke(binding, "refresh");
            });
        }
        SqlDraftStore.Snapshot snapshot() throws Exception {
            var future = fxValue(runtime::refresh); drain(); return future.get(5, TimeUnit.SECONDS).snapshot();
        }
        CompletableFuture<CloseGuardOutcome> beginClose(boolean mandatory) throws Exception {
            return fxValue(() -> (mandatory ? pane.requestMandatoryClose() : pane.requestClose()).toCompletableFuture());
        }
        void dismiss(String text) throws Exception {
            fx(() -> {
                Button button = Window.getWindows().stream().filter(Window::isShowing)
                        .flatMap(window -> window.getScene().getRoot().lookupAll(".button").stream())
                        .filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(candidate -> text.equals(candidate.getText())).findFirst().orElseThrow();
                button.fire();
            });
        }
        @Override public void close() throws Exception {
            try {
                pane.closeResources();
                fx(pane::finalizeCloseOnFx);
            } finally {
                var stopped = fxValue(runtime::shutdown);
                drain(); stopped.get(5, TimeUnit.SECONDS);
                runner.close();
            }
        }
    }
}
```

`test/com/datacube/fx/SqlDraftUiTest.java`:
```java
package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import java.nio.file.Path;
import java.lang.reflect.*;
import java.util.concurrent.*;
import javafx.scene.*;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlDraftUiTest {
    @TempDir Path directory;

    @Test void applicationTimerSavesAndBackgroundShutdownReleasesStoreLock() throws Exception {
        FxTaskRunner runner = new FxTaskRunner();
        CountDownLatch saved = new CountDownLatch(1);
        Object[] resources = FxUiTestSupport.call(() -> {
            Class<?> type = Class.forName("com.datacube.fx.SqlDraftUi");
            Constructor<?> constructor = type.getDeclaredConstructor(Path.class); constructor.setAccessible(true);
            Object owner = constructor.newInstance(directory.resolve("drafts"));
            SqlEditorPane pane = new SqlEditorPane(new SessionContext(), null, null,
                    new AppSettings(directory.resolve("settings.properties")), (id, table) -> fail("No designer"),
                    null, null, new SqlHistoryStore(directory.resolve("history.txt")),
                    new ShortcutSettings(directory.resolve("shortcuts.properties")), runner);
            Method bind = type.getDeclaredMethod("bind", SqlEditorPane.class); bind.setAccessible(true); bind.invoke(owner, pane);
            new Scene((Parent) pane.getNode(), 1200, 800);
            pane.getNode().applyCss();
            ((Label) pane.getNode().lookup("#sql-draft-status")).textProperty().addListener((observable, before, after) -> {
                if (after.contains("已保存")) saved.countDown();
            });
            pane.setSqlText("timer checkpoint");
            return new Object[]{owner, pane};
        });
        Object owner = resources[0];
        SqlEditorPane pane = (SqlEditorPane) resources[1];
        try {
            assertTrue(saved.await(8, TimeUnit.SECONDS), "real application timer must publish without a test pulse");
            var close = FxUiTestSupport.call(pane::requestMandatoryClose);
            assertEquals(CloseGuardOutcome.APPROVED, close.toCompletableFuture().get(5, TimeUnit.SECONDS));
        } finally {
            try { pane.closeResources(); FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; }); }
            finally {
                Method close = owner.getClass().getDeclaredMethod("closeFromBackground"); close.setAccessible(true);
                close.invoke(owner);
                runner.close();
            }
        }
        try (var store = SqlDraftStore.open(directory.resolve("drafts"))) {
            assertEquals("timer checkpoint", store.snapshot().drafts().getFirst().sql());
        }
    }
}
```

- [x] **Step 2: Run RED before source implementation**, capture actual XML test/failure totals and missing bindDraft/SqlDraftUi evidence. Run in the worktree:

```powershell
$draftPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$draftPreviousJavaOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --tests com.datacube.fx.SqlEditorDraftIntegrationTest --tests com.datacube.fx.SqlDraftUiTest --rerun-tasks --no-daemon --console=plain
    $draftTestExit = $LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS = $draftPreviousJavaOptions }
exit $draftTestExit
```

Expected: 13 tests fail on missing new seam/class, not a compilation failure. Correct fixture-only issues before implementing source.

- [x] **Step 3: Add adapter and application owner; apply pane/AppShell integration patch.** Use apply_patch. The following contains all new code and exact source edits.

`src/com/datacube/fx/SqlDraftEditorBinding.java`:
```java
package com.datacube.fx;

import com.datacube.config.SqlDraftCoordinator;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.fxmisc.richtext.CodeArea;

/** FX-only subscription and close bridge; the application runtime owns disk work. */
final class SqlDraftEditorBinding implements AutoCloseable {
    static final String PRIVACY = "SQL 草稿仅保存于本机，可能含敏感文本；保留7天，可关闭或清空。关闭草稿不停止原有 SQL 历史记录。";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final SqlDraftCoordinator runtime;
    private final SqlDraftCoordinator.Handle handle;
    private final CodeArea editor;
    private final TextField schema;
    private final Consumer<SqlDraftEditorBinding> detached;
    private final Label status = new Label();
    private final Label notice = new Label();
    private final Button retry = new Button("重试保存");
    private final Button toggle = new Button();
    private final Button clear = new Button("清空草稿");
    private final VBox root;
    private final ChangeListener<String> changes = (observable, before, after) -> edited();
    private boolean closed, closing, priorEditable, priorSchemaDisabled, managementPending;
    private CompletableFuture<Boolean> closeAttempt;

    SqlDraftEditorBinding(SqlDraftCoordinator runtime, UUID id, Long savedAt,
            CodeArea editor, TextField schema, SqlDraftCoordinator.Source source,
            Consumer<SqlDraftEditorBinding> detached) {
        this.runtime = runtime; this.editor = editor; this.schema = schema; this.detached = detached;
        status.setId("sql-draft-status");
        retry.setId("sql-draft-retry"); toggle.setId("sql-draft-toggle"); clear.setId("sql-draft-clear");
        Label privacy = new Label(PRIVACY);
        privacy.setWrapText(true); privacy.setId("sql-draft-privacy");
        notice.setWrapText(true); notice.setId("sql-draft-notice");
        FlowPane controls = new FlowPane(8, 4, status, retry, toggle, clear);
        root = new VBox(3, controls, notice, privacy);
        root.setId("sql-draft-protection");
        handle = runtime.attach(id, savedAt, source);
        try {
            editor.textProperty().addListener(changes);
            schema.textProperty().addListener(changes);
            retry.setOnAction(event -> { if (!closing && !closed) { handle.retry(); runtime.pulse(); refresh(); } });
            toggle.setOnAction(event -> {
                if (closing || closed || managementPending) return;
                manage(runtime.setEnabled(runtime.mode() != SqlDraftCoordinator.Mode.ENABLED));
            });
            clear.setOnAction(event -> {
                if (closing || closed || managementPending) return;
                if (confirm("清空草稿", "清空仅删除本机可恢复草稿，不清空编辑器；之后的新修改仍会保存。是否继续？", "清空"))
                    manage(runtime.clear());
            });
            refresh();
        } catch (RuntimeException failure) { close(); throw failure; }
    }

    Node getNode() { return root; }
    boolean closing() { return closing || closed; }
    void edited() { if (!closed && !closing) { handle.edited(); refresh(); } }

    void refresh() {
        if (closed) return;
        var snapshot = handle.status();
        String text = switch (snapshot.mode()) {
            case INITIALIZING -> "草稿保护初始化中，尚未确认保存";
            case DISABLED -> "草稿保护已关闭";
            case PAUSED -> "本次已暂停，关闭设置未保存，下次启动可能恢复";
            case UNAVAILABLE -> "草稿保护不可用，请检查本地目录后重启";
            case CLOSED -> "草稿保护已停止";
            case ENABLED -> switch (snapshot.saveStatus()) {
                case EMPTY -> "草稿保护已开启";
                case WAITING -> "草稿待保存";
                case SAVING -> "正在保存草稿";
                case SAVED -> "草稿已保存于 " + TIME.format(Instant.ofEpochMilli(snapshot.savedAt()));
                case FAILED -> "草稿保存失败，最新修改尚未保存";
            };
        };
        status.setText(text);
        boolean canRetry = snapshot.mode() == SqlDraftCoordinator.Mode.ENABLED
                && snapshot.saveStatus() == SqlDraftCoordinator.SaveStatus.FAILED;
        retry.setVisible(canRetry); retry.setManaged(canRetry); retry.setDisable(closing);
        toggle.setText(snapshot.mode() == SqlDraftCoordinator.Mode.ENABLED ? "关闭草稿保护" : "开启草稿保护");
        boolean unavailable = snapshot.mode() == SqlDraftCoordinator.Mode.INITIALIZING
                || snapshot.mode() == SqlDraftCoordinator.Mode.UNAVAILABLE || snapshot.mode() == SqlDraftCoordinator.Mode.CLOSED;
        toggle.setDisable(closing || managementPending || unavailable);
        clear.setDisable(closing || managementPending || unavailable);
        notice.setVisible(!notice.getText().isEmpty()); notice.setManaged(notice.isVisible());
    }

    private void manage(CompletableFuture<SqlDraftCoordinator.ManagementResult> operation) {
        managementPending = true; notice.setText(""); refresh();
        operation.whenComplete((result, failure) -> Platform.runLater(() -> {
            if (closed) return;
            managementPending = false;
            if (failure != null || !result.succeeded()) notice.setText("草稿操作未完成，已有可恢复草稿可能仍然保留。");
            refresh();
        }));
    }

    void freeze() {
        if (closing || closed) return;
        priorEditable = editor.isEditable(); priorSchemaDisabled = schema.isDisable();
        closing = true; editor.setEditable(false); schema.setDisable(true); refresh();
    }

    CompletableFuture<Boolean> prepareClose(boolean mandatory) {
        if (closeAttempt != null) return closeAttempt.copy();
        freeze();
        CompletableFuture<Boolean> attempt = new CompletableFuture<>();
        closeAttempt = attempt;
        try {
            handle.flush().whenComplete((unused, failure) -> {
                try {
                    Platform.runLater(() -> {
                        if (closed) { attempt.complete(false); return; }
                        try {
                            refresh();
                            boolean allow = failure == null || (!mandatory && confirm("最新草稿未保存",
                                    "最新修改尚未保存。取消关闭后可重试保存；放弃仅跳过本次保存，不删除已有草稿，也不关闭草稿保护。",
                                    "放弃本次最新修改并关闭"));
                            if (!allow) reopen();
                            attempt.complete(allow);
                        } catch (Throwable decisionFailure) { reopen(); attempt.completeExceptionally(decisionFailure); }
                    });
                } catch (Throwable dispatchFailure) { attempt.completeExceptionally(dispatchFailure); }
            });
        } catch (Throwable failure) { reopen(); attempt.completeExceptionally(failure); }
        return attempt.copy();
    }

    void reopen() {
        if (!closing || closed) return;
        closing = false; closeAttempt = null;
        editor.setEditable(priorEditable); schema.setDisable(priorSchemaDisabled); refresh();
    }

    private boolean confirm(String title, String message, String acceptText) {
        ButtonType accept = new ButtonType(acceptText, ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.CANCEL, accept);
        alert.setTitle(title); alert.setHeaderText(null);
        if (editor.getScene() != null && editor.getScene().getWindow() != null) alert.initOwner(editor.getScene().getWindow());
        ((Button) alert.getDialogPane().lookupButton(accept)).setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == accept;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        editor.textProperty().removeListener(changes);
        schema.textProperty().removeListener(changes);
        handle.detach();
        detached.accept(this);
    }
}
```

`src/com/datacube/fx/SqlDraftUi.java`:
```java
package com.datacube.fx;

import com.datacube.config.SqlDraftCoordinator;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

/** One application timer and writer, independent of disposable editor task scopes. */
final class SqlDraftUi {
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sql-draft-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<SqlDraftEditorBinding> bindings = new LinkedHashSet<>();
    private final SqlDraftCoordinator runtime;
    private final Timeline timer;

    SqlDraftUi(Path directory) {
        long started = System.nanoTime();
        runtime = new SqlDraftCoordinator(directory, writer, Platform::runLater, Platform::isFxApplicationThread,
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), System::currentTimeMillis);
        timer = new Timeline(new KeyFrame(Duration.millis(250), event -> {
            runtime.pulse();
            List.copyOf(bindings).forEach(SqlDraftEditorBinding::refresh);
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    void bind(SqlEditorPane pane) {
        bindings.add(pane.bindDraft(runtime, UUID.randomUUID(), null, bindings::remove));
    }

    void closeFromBackground() {
        if (Platform.isFxApplicationThread()) throw new IllegalStateException("Draft shutdown must be awaited off FX");
        CompletableFuture<Void> drained = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                timer.stop();
                runtime.shutdown().whenComplete((unused, failure) -> {
                    if (failure == null) drained.complete(null); else drained.completeExceptionally(failure);
                });
            } catch (Throwable failure) { drained.completeExceptionally(failure); }
        });
        try { drained.join(); } finally { writer.shutdown(); }
    }
}
```

Pane/AppShell exact integration patch:
```diff
*** Begin Patch
*** Update File: D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery/src/com/datacube/fx/SqlEditorPane.java
@@
 import com.datacube.config.AppSettings;
+import com.datacube.config.SqlDraft;
+import com.datacube.config.SqlDraftCoordinator;
@@
     private String lastQuerySql;
+    private SqlDraftEditorBinding draftBinding;
+
+    SqlDraftEditorBinding bindDraft(SqlDraftCoordinator runtime, java.util.UUID id, Long savedAt,
+            java.util.function.Consumer<SqlDraftEditorBinding> detached) {
+        if (draftBinding != null) throw new IllegalStateException("Draft already bound");
+        draftBinding = new SqlDraftEditorBinding(runtime, id, savedAt, editorArea, schemaField,
+                new SqlDraftCoordinator.Source() {
+                    public boolean hasText() { return editorArea.getLength() != 0; }
+                    public SqlDraft capture(java.util.UUID draftId, long at) {
+                        ConnConfig connection = currentConn();
+                        return new SqlDraft(draftId, at, connection == null ? null : connection.id(),
+                                connection == null ? null : connection.type(),
+                                connection == null ? null : connection.name(),
+                                schemaField.getText(), editorArea.getText());
+                    }
+                }, detached);
+        try { root.getChildren().add(draftBinding.getNode()); }
+        catch (RuntimeException failure) { draftBinding.close(); throw failure; }
+        return draftBinding;
+    }
+
+    private boolean draftEditingBlocked() { return draftBinding != null && draftBinding.closing(); }
+    private void draftEdited() { if (draftBinding != null) draftBinding.edited(); }
+
+    private void detachDraftFromAnyThread() {
+        if (draftBinding == null) return;
+        if (Platform.isFxApplicationThread()) { draftBinding.close(); return; }
+        CompletableFuture<Void> detached = new CompletableFuture<>();
+        Platform.runLater(() -> {
+            try { draftBinding.close(); detached.complete(null); }
+            catch (Throwable failure) { detached.completeExceptionally(failure); }
+        });
+        detached.join();
+    }
@@
                     renderDisconnectedCandidate(connection);
+                    draftEdited();
@@
     public void setSqlText(String sql) {
+        if (draftEditingBlocked()) return;
@@
         editorConnection = pinned;
+        draftEdited();
@@
     void closeResources() {
+        detachDraftFromAnyThread();
@@
     void finalizeCloseOnFx() {
         if (!uiFinalized.compareAndSet(false, true)) return;
+        if (draftBinding != null) draftBinding.close();
@@
     private CompletionStage<CloseGuardOutcome> startCloseAttempt() {
         CompletableFuture<CloseGuardOutcome> result = new CompletableFuture<>();
+        if (draftBinding != null) draftBinding.freeze();
@@
     private CompletionStage<CloseGuardOutcome> startMandatoryCloseAttempt() {
         CompletableFuture<CloseGuardOutcome> result = new CompletableFuture<>();
         ClosePlan plan;
         try {
+            if (draftBinding != null) draftBinding.freeze();
@@
-            Thread.startVirtualThread(() -> result.complete(closeMandatoryInBackground(plan)));
+            continueAfterDraftFlush(true, result,
+                    () -> Thread.startVirtualThread(() -> result.complete(closeMandatoryInBackground(plan))));
@@
-        sessionOperations.suppressCallbacks();
-        try {
-            Thread.startVirtualThread(() -> {
-                try {
-                    closeInBackground(plan);
-                    result.complete(CloseGuardOutcome.APPROVED);
-                } catch (RetryableTransactionCloseFailure gateFailure) {
-                    finishRetryableCloseFailure(result, gateFailure.getCause());
-                } catch (Throwable partialFailure) {
-                    partialFailure.printStackTrace(System.err);
-                    result.complete(CloseGuardOutcome.FAILED_PARTIAL);
-                }
-            });
-        } catch (Throwable startupFailure) {
-            reopenAfterRejectedClose();
-            result.completeExceptionally(startupFailure);
-        }
+        continueAfterDraftFlush(false, result, () -> {
+            sessionOperations.suppressCallbacks();
+            try {
+                Thread.startVirtualThread(() -> {
+                    try {
+                        closeInBackground(plan);
+                        result.complete(CloseGuardOutcome.APPROVED);
+                    } catch (RetryableTransactionCloseFailure gateFailure) {
+                        finishRetryableCloseFailure(result, gateFailure.getCause());
+                    } catch (Throwable partialFailure) {
+                        partialFailure.printStackTrace(System.err);
+                        result.complete(CloseGuardOutcome.FAILED_PARTIAL);
+                    }
+                });
+            } catch (Throwable startupFailure) {
+                reopenAfterRejectedClose();
+                result.completeExceptionally(startupFailure);
+            }
+        });
     }
+
+    private void continueAfterDraftFlush(boolean mandatory, CompletableFuture<CloseGuardOutcome> result,
+            Runnable continuation) {
+        if (draftBinding == null) { continuation.run(); return; }
+        draftBinding.prepareClose(mandatory).whenComplete((allowed, failure) -> {
+            if (failure != null || !Boolean.TRUE.equals(allowed)) {
+                reopenAfterRejectedClose();
+                if (failure != null) result.completeExceptionally(failure);
+                else result.complete(CloseGuardOutcome.REJECTED);
+                return;
+            }
+            try { continuation.run(); }
+            catch (Throwable startupFailure) { reopenAfterRejectedClose(); result.completeExceptionally(startupFailure); }
+        });
+    }
@@
     private void reopenAfterRejectedClose() {
+        if (draftBinding != null) draftBinding.reopen();
@@
     private void onFormat() {
+        if (draftEditingBlocked()) return;
@@
     private void toggleLineComment() {
+        if (draftEditingBlocked()) return;
@@
     private void toggleBlockComment() {
+        if (draftEditingBlocked()) return;
@@
         clearBtn.setOnAction(e -> {
+            if (draftEditingBlocked()) return;
@@
         editorArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
+            if (draftEditingBlocked()) { e.consume(); return; }
*** Update File: D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery/src/com/datacube/fx/AppShell.java
@@
     private final LazyValue<MigrationPane> migrationPane = new LazyValue<>(() -> new MigrationPane(tasks));
+    private final LazyValue<SqlDraftUi> sqlDrafts = new LazyValue<>(() ->
+            new SqlDraftUi(java.nio.file.Path.of(System.getProperty("user.home"), ".datacube", "sql-drafts")));
@@
         BestEffortCloseSequence.run(
+                () -> sqlDrafts.ifInitialized(SqlDraftUi::closeFromBackground),
                 connectionTree::close,
@@
                 pane -> binding.bind(pane::closeResources),
-                initialize,
+                pane -> {
+                    initialize.accept(pane);
+                    sqlDrafts.get().bind(pane);
+                },
*** End Patch
```

- [x] **Step 4: Run focused GREEN using the Step 2 command.** Initial 13/13 passed; review regressions expanded the final focused draft suites to17/17, zero skipped. See the companion amendment and verification record.

- [x] **Step 5: Run full regression once before commit.**
```powershell
$draftPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$draftPreviousJavaOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --rerun-tasks --no-daemon --console=plain
    $draftTestExit = $LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS = $draftPreviousJavaOptions }
exit $draftTestExit
```
Expected: build succeeds with only the 3 pre-existing live Redis/Oracle/PostgreSQL skips; actual totals from XML. Existing unchecked compiler note is disclosed. Do not run against real databases to remove skips.

- [x] **Step 6: Commit exact implementation/test files; report RED, GREEN, full suite, actual commits and deviations.** Seven files including the subsequent autocomplete amendment; commits `f41e1d5`, `89f6f00`, `24d5e42`. Final independent task review Approved; root fresh full1324/0fail/error/3live skips. This completes editor autosave integration, not the entire P1 recovery feature.
```powershell
git diff --check
git add -- src/com/datacube/fx/SqlDraftEditorBinding.java src/com/datacube/fx/SqlDraftUi.java src/com/datacube/fx/SqlEditorPane.java src/com/datacube/fx/AppShell.java test/com/datacube/fx/SqlEditorDraftIntegrationTest.java test/com/datacube/fx/SqlDraftUiTest.java
git commit -m "feat: wire SQL draft autosave and safe editor close"
```

Independent task review follows before marking this task complete. Root owns plan/verification/ledger edits and final full-P1 integration decision.

#### Close-popup gap discovered during integration preparation

The unchanged completion popup owns separate mouse/key handlers, and its `applySelection` calls `replaceText` directly. A frozen CodeArea does not block that programmatic mutation. The regression above must fail before the following fix (do not substitute a missing-method RED for this behavior RED). Seven source/test files are now in scope; include `src/com/datacube/fx/SqlAutoComplete.java` in the explicit staging command.

```diff
*** Begin Patch
*** Update File: D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery/src/com/datacube/fx/SqlAutoComplete.java
@@
     private void maybeShow() {
+        if (!area.isEditable() || area.isDisabled()) { hide(); return; }
@@
     private void applySelection() {
+        if (!area.isEditable() || area.isDisabled()) { hide(); return; }
*** Update File: D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery/src/com/datacube/fx/SqlEditorPane.java
@@
     private CompletionStage<CloseGuardOutcome> startCloseAttempt() {
         CompletableFuture<CloseGuardOutcome> result = new CompletableFuture<>();
         if (draftBinding != null) draftBinding.freeze();
+        if (autoComplete != null) autoComplete.hide();
@@
         try {
             if (draftBinding != null) draftBinding.freeze();
+            if (autoComplete != null) autoComplete.hide();
             ConnConfig connection = currentConn();
*** End Patch
```
