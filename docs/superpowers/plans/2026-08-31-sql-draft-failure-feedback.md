# SQL Draft Failure Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve sanitized save failure categories and make retained sensitive files visible in both draft surfaces.

**Architecture:** The coordinator retains a typed per-handle failure for the accepted save ticket and a process-wide sticky CLEANUP classification. The existing editor bridge owns shared user-facing failure copy; the manager uses it for structural unavailability. Editor management completion inspects the real snapshot's problems, not only action success.

**Tech Stack:** Java25, JavaFX25, existing JUnit; no dependencies.

## Global Constraints

- Work only in `D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery`, `codex/sql-draft-recovery`. Never access `.testagent/` contents, real profiles, credentials, history or databases. No merge/push/tag/install/release.
- No change to raw SQL, storage limits, queue ordering, close/rollback guards, database admission or atomic publication. No new automatic retry, disk deletion or network access.
- Preserve structural fail-closed behavior immediately on the writer path, even for a stale display ticket. No FX I/O or future joins. Do not retain raw exception causes/messages in public status or UI.
- Under the user's routine-design waiver, reconcile prior wording in favor of repair plus application restart for CLEANUP/lock/identity/scan structural faults. Ordinary write/capacity/invalid-input failures may still be explicitly retried; no automatic repair/re-enable.
- Desktop acceptance is separately blocked by Computer Use capture/access errors; no attempt to bypass that restriction. Test synthetic controls programmatically as automated evidence, not desktop evidence.

---

### Task 1: Fix final review failure/privacy feedback

**Files:**
- Modify `src/com/datacube/config/SqlDraftCoordinator.java`.
- Modify `src/com/datacube/fx/SqlDraftEditorBinding.java`.
- Modify `src/com/datacube/fx/SqlDraftManagerPane.java`.
- Modify `test/com/datacube/config/DraftManagementProbe.java`.
- Modify `test/com/datacube/config/SqlDraftCoordinatorTest.java`.
- Modify `test/com/datacube/fx/SqlEditorDraftIntegrationTest.java`.
- Modify `test/com/datacube/fx/SqlDraftManagerTest.java` only its actual-dialog response cleanup helper, as amended below after RED evidence.
- Create `test/com/datacube/fx/SqlDraftFailureFeedbackTest.java` using the complete code below.
- Append report `.superpowers/sdd/draft-p1-feedback-fix-report.md`; controller owns all tracked docs and remaining Minor decisions.

**Interfaces:** Extend FailureReason with CAPACITY, INVALID_DRAFT, CLEANUP. Extend immutable Status with nullable `FailureReason failureReason`. Add owner-checked `public FailureReason unavailableReason()` (generic UNAVAILABLE or sticky CLEANUP). Existing accessors and methods remain. Existing `state.succeeded/failed(ticket)` return whether the ticket was current; use those returns before changing the per-handle reason.

- [ ] **Step 1: Tests and compiling shape only.** Add enum entries, Status field and unavailableReason stub returning UNAVAILABLE, adjusting the existing Status construction to pass null. No behavior changes yet. Add these controlled probe seams (test-only):

```java
public enum SaveFault { NONE, WRITE, CAPACITY, INVALID_DRAFT, CLEANUP }
public SaveFault saveFault = SaveFault.NONE;
```

Replace probe.save with:

```java
public void save(SqlDraft draft) throws IOException {
    switch (saveFault) {
        case WRITE -> throw new IOException("synthetic private SQL and path");
        case CAPACITY -> throw new SqlDraftStore.Failure(SqlDraftStore.FailureCode.CAPACITY);
        case INVALID_DRAFT -> throw new SqlDraftStore.Failure(SqlDraftStore.FailureCode.INVALID_DRAFT);
        case CLEANUP -> throw new SqlDraftDirectory.Failure(SqlDraftDirectory.Stage.CLEANUP);
        case NONE -> { }
    }
    records.removeIf(item -> item.id().equals(draft.id()));
    records.add(draft);
}
```

Add to the existing coordinator `structuralFailureCancelsOtherWritesBeforeUiProcessesAnyCallbacks` immediately after disk() and existing UNAVAILABLE assertion (before ui callbacks):

```java
assertEquals(SqlDraftCoordinator.FailureReason.CLEANUP, f.runtime.unavailableReason());
assertEquals(SqlDraftCoordinator.FailureReason.CLEANUP, one.status().failureReason());
```

Add this actual-file case to SqlEditorDraftIntegrationTest:

```java
@Test
void successfulEditorClearWarnsAboutProtectedCorruptFiles() throws Exception {
  try (Fixture f = new Fixture("select 'synthetic';", null, true)) {
    f.tick(1000);
    assertEquals(1, f.snapshot().drafts().size());
    Path corrupt = directory.resolve("drafts").resolve(UUID.randomUUID() + ".draft");
    byte[] retained = new byte[] { 1, 2, 3, 4 };
    java.nio.file.Files.write(corrupt, retained);
    f.fx(() -> SqlDraftManagerTest.respondToDialog(
        () -> ((Button) f.pane.getNode().lookup("#sql-draft-clear")).fire(),
        dialog -> {
          ButtonType accept = dialog.getButtonTypes().stream()
              .filter(type -> type != ButtonType.CANCEL).findFirst().orElseThrow();
          ((Button) dialog.lookupButton(accept)).fire();
        }));
    f.drain();
    f.fx(() -> {
      var result = f.runtime.lastManagementResult();
      assertTrue(result.succeeded());
      assertTrue(result.snapshot().drafts().isEmpty());
      assertFalse(result.snapshot().problems().isEmpty());
      assertTrue(f.label("notice").getText().contains("仍保留"));
      assertTrue(f.label("notice").getText().contains("SQL"));
      assertTrue(f.label("notice").isVisible());
      assertEquals("select 'synthetic';", f.editor().getText());
    });
    assertArrayEquals(retained, java.nio.file.Files.readAllBytes(corrupt));
  }
}
```

Complete new FX test:

```java
package com.datacube.fx;

import static org.junit.jupiter.api.Assertions.*;
import com.datacube.config.DraftManagementProbe;
import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import java.lang.reflect.Field;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SqlDraftFailureFeedbackTest {
    @ParameterizedTest
    @EnumSource(value = DraftManagementProbe.SaveFault.class, names = {"WRITE", "CAPACITY", "INVALID_DRAFT", "CLEANUP"})
    void failureKindsReachRealEditorControlsWithoutPrivateDiagnostics(DraftManagementProbe.SaveFault fault) throws Exception {
        try (Fixture f = new Fixture()) {
            f.probe.saveFault = fault;
            CompletableFuture<Void> failed = f.call(f.handle::flush);
            f.probe.drain();
            ExecutionException error = assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
            assertInstanceOf(SqlDraftCoordinator.Failure.class, error.getCause());
            assertNull(error.getCause().getCause());
            f.settle();
            f.fx(() -> {
                assertEquals(SqlDraftCoordinator.FailureReason.valueOf(fault.name()), f.handle.status().failureReason());
                String text = f.label("status").getText();
                assertFalse(text.contains("synthetic private"));
                assertEquals("select 'synthetic';", f.editor.getText());
                if (fault == DraftManagementProbe.SaveFault.CAPACITY) {
                    assertTrue(text.contains("100"));
                    assertTrue(text.contains("32 MiB"));
                    assertTrue(text.contains("复制"));
                } else if (fault == DraftManagementProbe.SaveFault.INVALID_DRAFT) {
                    assertTrue(text.contains("1 MiB"));
                    assertTrue(text.contains("4096"));
                    assertTrue(text.contains("复制"));
                } else if (fault == DraftManagementProbe.SaveFault.CLEANUP) {
                    assertTrue(text.contains("临时文件"));
                    assertTrue(text.contains("SQL"));
                    assertTrue(text.contains("重启"));
                    assertFalse(f.button("retry").isVisible());
                    assertTrue(f.button("clear").isDisabled());
                    SqlDraftManagerPane manager = new SqlDraftManagerPane(f.runtime, ignored -> false, () -> {});
                    try {
                        String managerText = ((Label) manager.getNode().lookup("#draft-manager-status")).getText();
                        assertTrue(managerText.contains("临时文件"));
                        assertTrue(managerText.contains("SQL"));
                    } finally { manager.close(); }
                } else {
                    assertTrue(text.contains("尚未保存"));
                    assertTrue(f.button("retry").isVisible());
                }
            });
            assertTrue(f.probe.records.isEmpty());
        }
    }

    @Test void successfulExplicitRetryClearsThePreviousFailureClassification() throws Exception {
        try (Fixture f = new Fixture()) {
            f.probe.saveFault = DraftManagementProbe.SaveFault.CAPACITY;
            f.call(f.handle::flush);
            f.probe.drain();
            f.settle();
            f.probe.saveFault = DraftManagementProbe.SaveFault.NONE;
            f.fx(() -> f.button("retry").fire());
            f.probe.drain();
            f.settle();
            f.fx(() -> {
                assertEquals(SqlDraftCoordinator.SaveStatus.SAVED, f.handle.status().saveStatus());
                assertNull(f.handle.status().failureReason());
                assertTrue(f.label("status").getText().contains("已保存"));
            });
            assertEquals("select 'synthetic';", f.probe.records.getFirst().sql());
        }
    }

    @Test void staleFailureCannotAnnotateANewerEditorRevision() throws Exception {
        try (Fixture f = new Fixture()) {
            f.probe.saveFault = DraftManagementProbe.SaveFault.CAPACITY;
            f.call(f.handle::flush);
            f.probe.drain();
            f.fx(() -> f.editor.replaceText("select 'new revision';"));
            f.settle();
            f.fx(() -> {
                assertEquals(SqlDraftCoordinator.SaveStatus.WAITING, f.handle.status().saveStatus());
                assertNull(f.handle.status().failureReason());
                assertFalse(f.label("status").getText().contains("32 MiB"));
            });
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DraftManagementProbe probe = new DraftManagementProbe();
        final Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        final SqlDraftCoordinator runtime;
        final CodeArea editor;
        final SqlDraftEditorBinding binding;
        final SqlDraftCoordinator.Handle handle;
        Fixture() throws Exception {
            runtime = call(() -> probe.create(callbacks::add, Platform::isFxApplicationThread));
            probe.drain();
            fx(this::drainCallbacks);
            editor = call(() -> new CodeArea("select 'synthetic';"));
            binding = call(() -> {
                TextField schema = new TextField(" raw ");
                SqlDraftEditorBinding created = new SqlDraftEditorBinding(runtime, UUID.randomUUID(), null,
                        editor, schema, new SqlDraftCoordinator.Source() {
                    public boolean hasText() { return !editor.getText().isEmpty(); }
                    public SqlDraft capture(UUID id, long at) {
                        return new SqlDraft(id, at, null, null, null, schema.getText(), editor.getText());
                    }
                }, ignored -> {});
                new Scene(new VBox(editor, schema, created.getNode()));
                return created;
            });
            Field field = SqlDraftEditorBinding.class.getDeclaredField("handle");
            field.setAccessible(true);
            handle = (SqlDraftCoordinator.Handle) field.get(binding);
        }
        void drainCallbacks() { Runnable next; while ((next = callbacks.poll()) != null) next.run(); }
        void settle() throws Exception { fx(() -> { drainCallbacks(); binding.refresh(); }); fx(() -> {}); }
        Label label(String id) { return (Label) binding.getNode().lookup("#sql-draft-" + id); }
        Button button(String id) { return (Button) binding.getNode().lookup("#sql-draft-" + id); }
        <T> T call(Callable<T> work) throws Exception { return FxUiTestSupport.call(work); }
        void fx(Runnable work) throws Exception { call(() -> { work.run(); return null; }); }
        public void close() throws Exception {
            fx(binding::close);
            CompletableFuture<Void> closed = call(runtime::shutdown);
            probe.drain();
            closed.get(5, TimeUnit.SECONDS);
        }
    }
}
```

- [ ] **Step 2: Run RED before behavior edits.**

```powershell
$draftPriorOptions=$env:JAVA_TOOL_OPTIONS
try {
  $env:JAVA_TOOL_OPTIONS="$draftPriorOptions -Djava.awt.headless=false".Trim()
  .\gradlew.bat test --tests '*SqlDraftFailureFeedbackTest' --tests '*SqlDraftCoordinatorTest' --tests '*SqlEditorDraftIntegrationTest' --rerun-tasks --no-daemon --console=plain
  $draftExit=$LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS=$draftPriorOptions }
exit $draftExit
```

Expected actual behavioral failures: missing typed feedback, missing sticky CLEANUP before FX callback, and editor clear notice with retained corrupt file. Some lifecycle cases may already pass. Compilation errors are not RED. Notify controller and wait for XML acknowledgement before GREEN.

- [ ] **Step 3: Implement the coordinator classification.** Keep the shape from Step1. Add `private FailureReason saveFailure;` inside Handle and `private final java.util.concurrent.atomic.AtomicReference<FailureReason> unavailableFailure = new java.util.concurrent.atomic.AtomicReference<>(FailureReason.UNAVAILABLE);` on the runtime. Replace the accessor stub with `owner(); return unavailableFailure.get();`.

In Handle.status use:

```java
Mode currentMode = mode();
return new Status(currentMode, saved, state.savedAt().isPresent() ? state.savedAt().getAsLong() : null,
        currentMode == Mode.UNAVAILABLE ? unavailableReason() : saveFailure);
```

Clear saveFailure at the start of accepted edited(), in reset(), and when state.succeeded accepts a publication. Set CAPTURE in the capture exception branch. Replace generic FailureReason.WRITE wrapping with `new Failure(classify(failure))`. The publication UI callback becomes:

```java
if (detached) return;
if (failure == null) {
    if (state.succeeded(ticket, draft.modifiedAt())) saveFailure = null;
} else if (state.failed(ticket)) {
    changed = true;
    saveFailure = classify(failure);
}
```

Add complete helpers:

```java
private static FailureReason classify(Throwable error) {
    if (error instanceof CompletionException && error.getCause() != null) return classify(error.getCause());
    if (error instanceof SqlDraftDirectory.Failure failure && failure.stage() == SqlDraftDirectory.Stage.CLEANUP)
        return FailureReason.CLEANUP;
    if (error instanceof SqlDraftStore.Failure failure) {
        if (failure.code() == SqlDraftStore.FailureCode.CAPACITY) return FailureReason.CAPACITY;
        if (failure.code() == SqlDraftStore.FailureCode.INVALID_DRAFT) return FailureReason.INVALID_DRAFT;
    }
    return FailureReason.WRITE;
}

private void stop(Throwable failure) {
    if (classify(failure) == FailureReason.CLEANUP) unavailableFailure.set(FailureReason.CLEANUP);
    stop();
}
```

At each existing `if (structural(failure)) stop()` use `stop(failure)` instead, including write(), inspect action and snapshot exceptions, and capture publication failure. Initialization and management exceptional completion use stop(failure). UI executor rejection uses stop(rejected). Keep argument-free stop() for unusable snapshots and preserve all queue invalidation logic. The sticky classification must be set before publication/other queued writes continue; don't rely on the FX callback and don't overwrite CLEANUP with a later generic failure.

- [ ] **Step 4: Render bounded safe copy and protected leftovers.** Add this package-static helper on SqlDraftEditorBinding (manager already shares its privacy constant):

```java
static String failureMessage(SqlDraftCoordinator.FailureReason reason) {
    if (reason == null) return "草稿保存失败，最新修改尚未保存，可重试";
    return switch (reason) {
        case CLEANUP -> "草稿保护不可用：可能残留含敏感 SQL 的临时文件。请检查本机草稿目录，修复后重启；不会自动重试。";
        case CAPACITY -> "草稿容量不足（最多100条、合计32 MiB），最新修改尚未保存。请先复制文本另存，再清理不需要的草稿或重试。";
        case INVALID_DRAFT -> "草稿内容无法保存（SQL最多1 MiB UTF-8、每项元数据最多4096字节，需有效Unicode）。请先复制文本另存并检查长度和字符；原记录保留。";
        case CAPTURE -> "无法获取草稿快照，最新修改尚未保存。请先复制文本另存，再重试。";
        case UNAVAILABLE -> "草稿保护不可用，请检查本地目录后重启";
        default -> "草稿保存失败，最新修改尚未保存，可重试";
    };
}
```

Editor refresh: UNAVAILABLE -> failureMessage(snapshot.failureReason()), enabled FAILED -> same helper. Set status.wrapText(true) so actionable messages remain readable. Existing retry eligibility stays ENABLED+FAILED; CLEANUP remains unavailable, no retry/enable/clear path.

Manager status UNAVAILABLE -> `SqlDraftEditorBinding.failureMessage(runtime.unavailableReason()) + "；仍可恢复已读取的草稿"`; status.wrapText(true). Other control behavior unchanged.

Replace editor manage callback's result check with:

```java
if (failure != null || result == null || !result.succeeded() || result.snapshot() == null) {
    notice.setText("草稿操作未完成，已有可恢复草稿及其他文件可能仍然保留。");
} else if (!result.snapshot().problems().isEmpty()) {
    notice.setText("可恢复草稿操作已完成；仍保留损坏、未知或不可读取的文件，可能包含敏感 SQL。本次未删除这些文件，请检查本机草稿目录。");
}
```

Do not claim corrupt files were deleted, clear them automatically, or include their full paths/SQL in UI diagnostics.

- [ ] **Step 5: Verify, self-review, commit exact files.** Run the same focused command plus `--tests '*SqlDraftManagerTest' --tests '*SqlDraftRecoveryTabsTest'`. Then full forced nonheadless regression. Report actual XML totals/skips, commands, exit codes and RED attribution; no weakened assertions or new skips. Root will independently rerun after commit. Exact source/test commit `fix: expose safe draft failure and cleanup feedback`; do not stage root docs. Append/report at the named path and return concise status/commit/concerns. Broader process/package checks become stale after source edits and must be rerun before P1 completion.

## Self-review and Minor decisions

### Evidence-led fixture correction after initial RED

Existing close-guard test compatibility: writeFailureRefusesMandatoryCloseAndRestoresExactFlagsThenSavesNewEdit sets a 4097-character schema, so the real Store rejects it as INVALID_DRAFT. Replace only its old generic status substring assertion, retaining close outcome, resource/editability flags and later-save assertions:

```java
Object binding = field(f.pane, "draftBinding");
var handle = (SqlDraftCoordinator.Handle) field(binding, "handle");
assertEquals(SqlDraftCoordinator.FailureReason.INVALID_DRAFT, handle.status().failureReason());
String statusText = f.label("status").getText();
assertTrue(statusText.contains("草稿内容无法保存"), statusText);
```

The first attempted CAPTURE replacement was an incorrect diagnosis and failed again; it must not be described as a product regression or a passing run. Typed status plus safe UI text is the corrected evidence.

The initial RED exposed an existing asynchronous cleanup NPE in respondToDialog: closing the actual dialog can detach its Scene from its Window before finally dereferences it. Preserve the Window captured before response instead of creating another helper. Replace the existing method completely with the following; its purpose is reliable actual-dialog cleanup, not a production behavior change. A cleanup exception is now propagated to the test caller rather than escaping unnoticed from Platform.runLater. Initial six functional failures remain the valid RED; this diagnostic is separately recorded.

```java
static void respondToDialog(Runnable open, Consumer<DialogPane> response) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.runLater(() -> {
        DialogPane dialog = null;
        Window dialogWindow = null;
        try {
            dialog = Window.getWindows().stream().filter(Window::isShowing)
                    .map(window -> window.getScene().getRoot()).filter(DialogPane.class::isInstance)
                    .map(DialogPane.class::cast).findFirst().orElseThrow();
            dialogWindow = dialog.getScene().getWindow();
            response.accept(dialog);
        } catch (Throwable problem) {
            failure.set(problem);
        } finally {
            try {
                if (dialogWindow != null && dialogWindow.isShowing()) {
                    var cancel = dialog.lookupButton(ButtonType.CANCEL);
                    if (cancel == null) cancel = dialog.lookupButton(ButtonType.CLOSE);
                    if (cancel instanceof Button button) button.fire();
                    else dialogWindow.hide();
                }
            } catch (Throwable cleanup) {
                Throwable previous = failure.get();
                if (previous == null) failure.set(cleanup);
                else if (previous != cleanup) previous.addSuppressed(cleanup);
            }
        }
    });
    open.run();
    if (failure.get() != null) throw new AssertionError("Dialog assertion failed", failure.get());
}
```

Two Important findings have direct runtime/UI tests. Protected-file clear uses a real owned corrupt draft file and checks its bytes survive. Fault-classification tests use real coordinator/binding controls with a controlled backend and FX callback queue; these supplement, not replace, existing filesystem fault injection. Stale-ticket and successful-retry cases prevent sticky ordinary error labels; CLEANUP alone remains application-sticky. Failure text is a fixed enum mapping, never raw exception content.

The manager-row missing/type-changed hint is a nonblocking Minor: the restored editor already validates and displays it without DB activity. Defer row enrichment to visual acceptance rather than expanding this safety fix's connection-resolver API. Store deletion-loop and inline queue coverage suggestions remain nonblocking, explicitly retained in final review. Controller updates stale opening verification/checklist summaries now; existing compiler/CSS/JDK notices remain disclosed.
