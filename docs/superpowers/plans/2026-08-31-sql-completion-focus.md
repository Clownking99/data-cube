# SQL Completion Focus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent passive workspace restoration from opening SQL completion popups.

**Architecture:** Gate the existing text-listener automatic request both before enqueue and when the FX callback runs. Keep manual completion and workspace state unchanged.

**Tech Stack:** Java 25, JavaFX 25, JUnit Jupiter 5.11.3, Gradle 9.2.0; no new dependencies.

## Global Constraints

- Work only in D:/Projects/朝花夕拾/.worktrees/sql-workspace-recovery on codex/sql-workspace-recovery.
- Never read, modify, stage or clean .testagent/ contents; preserve other worktrees and user changes.
- Only synthetic SQL and explicit temporary settings; no real config, credentials, connections, network SQL or telemetry.
- Do not change workspace persistence, recovery positions, connection admission, privacy preferences or deletion behavior.
- Preserve explicit Ctrl+Space completion and keyboard candidate acceptance. No public test-only APIs or dependency changes.
- One Gradle process at a time. Scope and restore JAVA_TOOL_OPTIONS. Record native exit and actual XML; retain existing live skips and warnings honestly.
- No push, tag, release, installation or main integration in this fix task.

### Task 1: Gate passive completion and protect interactive behavior

**Files:** Modify src/com/datacube/fx/SqlAutoComplete.java. Create test/com/datacube/fx/SqlAutoCompleteFocusTest.java. Write local report .superpowers/sdd/completion-focus-task-1-report.md. Controller owns docs, not implementer.

**Interfaces:** SqlAutoComplete(CodeArea, Supplier<Collection<String>>, ShortcutSettings), void refresh(), void hide(); FxUiTestSupport.call(Callable<T>); ShortcutSettings(Path). No interface changes.

- [x] **Step 1: Add and run the smallest genuine RED.** Start from this complete test class, using actual controls/listener and an FX-queue barrier:

```java
package com.datacube.fx;

import com.datacube.config.ShortcutSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlAutoCompleteFocusTest {
    @TempDir Path directory;

    @Test void unfocusedRestoreDoesNotRequestCandidates() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CodeArea area = FxUiTestSupport.call(() -> {
            CodeArea value = new CodeArea();
            new SqlAutoComplete(value, () -> {
                requests.incrementAndGet();
                return List.of("SELECT");
            }, new ShortcutSettings(directory.resolve("shortcuts.properties")));
            assertFalse(value.isFocused());
            value.replaceText("select 'synthetic alpha';\n");
            value.selectRange(9, 3);
            return value;
        });
        FxUiTestSupport.call(() -> {
            assertEquals(0, requests.get(), "Passive restoration must not request completion");
            assertEquals("select 'synthetic alpha';\n", area.getText());
            assertEquals(9, area.getAnchor());
            assertEquals(3, area.getCaretPosition());
            return null;
        });
    }
}
```

Run `./gradlew.bat test --tests '*SqlAutoCompleteFocusTest.unfocusedRestoreDoesNotRequestCandidates' --no-daemon --console=plain` with scoped `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`, JDK25. Expected actual assertion failure: candidate request count greater than zero. Preserve RED XML outside build output and send root its path/native exit. Wait root acknowledgment before production changes.

- [x] **Step 2: Extend real-FX coverage before the relevant fix.** Use an owned Stage/Scene with CodeArea and a second focusable control, temporary ShortcutSettings and bounded future/property listeners for focus readiness. No fixed sleeps or fabricated production state. Cover replacement while unfocused then focus gained before delivery (no request); replacement while focused then focus lost before delivery (no request/popup); focused edit (actual candidates/popup), Ctrl+Space (still opens actual candidates), and Tab/Enter acceptance (expected inserted text, popup closes, no recursive reopen). Hide only owned popup/stage in finally. Reflection may inspect the existing private Popup, but do not invoke maybeShow directly or add production test hooks. Record which additional cases fail old code versus preserve existing behavior.

- [x] **Step 3: Minimal implementation.** Replace only the text listener's enqueue body:

```java
if (mutating || !area.isFocused()) return;
Platform.runLater(() -> {
    if (area.isFocused()) maybeShow();
});
```

Add a short comment explaining both focus checks: programmatic unfocused loads are not input, and queued completion may outlive focus. Leave onKeyPressed, maybeShow, refresh and all recovery code intact. If real evidence requires a different guard, report it before expanding the patch.

- [x] **Step 4: Verify and commit.** Run focused `*SqlAutoCompleteFocusTest`, then adjacent `*SqlEditorDraftRecoveryTest`, `*SqlWorkspaceRecoveryTabsTest`, `*SqlWorkspaceManagerTest`; full `./gradlew.bat test --rerun-tasks --no-daemon --console=plain` once. Inspect exact test XML and preserve results/report. Self-review and commit only the two source/test paths. Return status, SHA, native exits/counts, concerns and report path. Controller performs independent verification, task review and final bounded branch review.

## Controller follow-through

Record actual desktop evidence separately: two normal exits (sessions9444/59636), first restore opened2, repeat opened0/reused2, beta-alpha order/alpha current/visible ranges and both themes, then same-profile restart restored2. Correct the stale integration status in the older acceptance plan using the main integration record. Re-run desktop after this fix; keep keyboard-modal and update-enabled distribution boundaries explicit. Advance P3 only after this defect is resolved and recorded.
