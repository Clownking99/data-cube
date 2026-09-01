# SQL Script File Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add safe, explicit `.sql` open/save/save-as and recent-file workflows to the existing managed SQL editor.

**Architecture:** Keep blocking file I/O in a pure `sqleditor` store and run it on existing virtual-thread scopes. A pure document model owns file identity and dirty semantics; a small JavaFX controller binds that model to an editor and tab title. `AppShell` owns only open/recent navigation and tab creation.

**Tech Stack:** Java 25, JavaFX 25, RichTextFX, JUnit Jupiter 5.11.3, Gradle 9.2.0; no new dependencies.

## Global Constraints

- Never auto-connect, query metadata, execute SQL, or infer a target database when opening a file.
- Strict UTF-8 only, optional UTF-8 BOM on read, no content truncation or newline conversion.
- Maximum script size is exactly 8 MiB encoded bytes.
- Saving requires same-directory temporary output, full close, final version check and atomic replace; failures preserve prior target bytes.
- `.testagent/` is user-owned and must not be read, changed, staged or cleaned.
- Tests use synthetic content and exclusive temporary directories; never read real user scripts, profiles, credentials or database state.
- Existing draft/workspace formats remain unchanged and do not persist file paths.
- No push, tag, release, installer run or external database access in this implementation branch.

---

### Task 1: Safe SQL script file store

**Files:**
- Create: `src/com/datacube/sqleditor/SqlScriptFileStore.java`
- Create: `test/com/datacube/sqleditor/SqlScriptFileStoreTest.java`

**Interfaces:**
- Produces `SqlScriptFileStore.Target` as an opaque versioned target with `path()` and `exists()`.
- Produces `SqlScriptFileStore.Loaded(Path path, String text, Target target)`.
- Public operations: `Target capture(Path)`, `Loaded load(Path)`, and `Loaded save(Target, String)`.
- Typed failures expose only `FailureCode` (`INVALID_TARGET`, `TOO_LARGE`, `INVALID_UTF8`, `CHANGED`, `BUSY`, `WRITE`, `PUBLISH`, `CLEANUP`) plus an owned temporary path only for cleanup diagnostics.

- [ ] **Step 1: Write failing store tests**

Cover exact UTF-8/BOM reads, malformed bytes, 8 MiB boundary, regular-file and symlink rules, changed-during-read detection using an injected reader seam, new/existing writes, external modification/replacement/deletion, target appearing after capture, atomic-move failure, writer failure, same-target concurrency and cleanup failure. Assert old bytes and unrelated files remain unchanged.

- [ ] **Step 2: Verify RED**

Run:

```powershell
./gradlew.bat test --tests '*SqlScriptFileStoreTest' --no-daemon --console=plain
```

Expected: compilation fails because `SqlScriptFileStore` does not exist.

- [ ] **Step 3: Implement the minimal store**

Use this public shape:

```java
public final class SqlScriptFileStore {
    public static final long MAX_BYTES = 8L * 1024 * 1024;
    public enum FailureCode { INVALID_TARGET, TOO_LARGE, INVALID_UTF8, CHANGED, BUSY, WRITE, PUBLISH, CLEANUP }
    public static final class Failure extends IOException {
        public FailureCode code();
        public Path temporaryPath();
    }
    public static final class Target {
        public Path path();
        public boolean exists();
    }
    public record Loaded(Path path, String text, Target target) { }
    public Target capture(Path chosen) throws Failure;
    public Loaded load(Path chosen) throws Failure;
    public Loaded save(Target expected, String text) throws Failure;
}
```

Normalize through a real parent path, reject links using `NOFOLLOW_LINKS`, compare file key/size/modified/created, decode with a reporting `CharsetDecoder`, write UTF-8 without BOM, and use `ATOMIC_MOVE + REPLACE_EXISTING`. Maintain a static canonical-target busy set and release it in `finally`.

- [ ] **Step 4: Verify GREEN and commit**

Run focused tests, then `git diff --check`. Commit only store and test as `feat: add safe SQL script file storage`.

### Task 2: Document state and recent paths

**Files:**
- Create: `src/com/datacube/sqleditor/SqlScriptDocument.java`
- Create: `src/com/datacube/config/RecentSqlFiles.java`
- Create: `test/com/datacube/sqleditor/SqlScriptDocumentTest.java`
- Create: `test/com/datacube/config/RecentSqlFilesTest.java`

**Interfaces:**
- `SqlScriptDocument` consumes `SqlScriptFileStore.Loaded`; provides `path()`, `target()`, `dirty(String)`, `title(String fallback, String currentText)`, `attach(Loaded)`, and `saved(Loaded)`.
- `RecentSqlFiles(Path storage)` provides `List<Path> recent()`, `void record(Path)`, and `void clear()`; maximum 10, newest first, canonical absolute de-duplication.

- [ ] **Step 1: Write failing state tests**

Assert unbound fallback title, filename title, `*` dirty suffix, reverting to baseline clears dirty, save snapshot remains baseline when later text changes, save-as rebinding, exact text comparison, recent de-dup/order/limit, malformed lines ignored, clear deletes only the index, and storage failure leaves the in-memory previous list unchanged.

- [ ] **Step 2: Verify RED**

Run both focused classes; require missing-type compilation failures.

- [ ] **Step 3: Implement state and recent storage**

Recent storage format is first line `DATACUBE_SQL_RECENT_V1`, followed by Base64-encoded UTF-8 absolute paths. Cap each decoded path at 4096 characters and the file at 128 KiB. Publish with a same-directory temporary file and atomic replace; best-effort public mutations keep prior memory state when persistence fails and emit only fixed diagnostic text.

- [ ] **Step 4: Verify GREEN and commit**

Run both classes and commit the four files as `feat: track SQL script documents and recent files`.

### Task 3: Per-editor file actions and close safety

**Files:**
- Create: `src/com/datacube/fx/SqlScriptFileController.java`
- Create: `test/com/datacube/fx/SqlScriptFileControllerTest.java`
- Modify: `src/com/datacube/fx/SqlEditorPane.java`
- Modify: `test/com/datacube/fx/SqlEditorSessionContractTest.java`

**Interfaces:**
- Controller consumes `CodeArea`, `SqlScriptFileStore`, `RecentSqlFiles`, `FxTaskScope`, window supplier, title consumer, save-path chooser, overwrite confirmer and close-decision provider.
- `install(Loaded initial)` attaches an opened file; null means an ordinary/draft/history tab.
- `save()` and `saveAs()` return a `CompletionStage<Boolean>`; true means a matching snapshot was published.
- `guardClose(Supplier<CompletionStage<CloseGuardOutcome>> proceed)` returns CANCELLED for user cancellation or failed save and invokes the existing SQL close guard only after save/discard approval.

- [ ] **Step 1: Write failing controller tests**

Use real FX controls and injectable synchronous submitters to cover title updates, first-save chooser, save/save-as, overwrite denial, busy disabling, edit during save, conflict feedback, stale completion suppression, and all three close decisions. Assert a failed/conflicted save never invokes the existing close guard.

- [ ] **Step 2: Verify RED**

Run `*SqlScriptFileControllerTest`; require missing controller/API failure.

- [ ] **Step 3: Implement and integrate minimally**

Add editor toolbar buttons with ids `sql-file-save` and `sql-file-save-as`. Install the controller after the managed tab exists so title changes call `Tab.setText`. Route `requestClose()` through `guardClose`; leave `requestMandatoryClose()` unchanged so app exit never writes a file implicitly. Ensure `closeResources()` closes the controller and invalidates callbacks.

- [ ] **Step 4: Verify GREEN and adjacent lifecycle tests**

Run controller, SQL editor close/session, draft, workspace and managed-tab test classes. Commit as `feat: add SQL editor file save lifecycle`.

### Task 4: Open/recent navigation, shortcuts and product documentation

**Files:**
- Modify: `src/com/datacube/fx/AppShell.java`
- Modify: `src/com/datacube/config/ShortcutAction.java`
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-08-30-product-continuity-roadmap.md`
- Create: `test/com/datacube/fx/SqlScriptFileEntryTest.java`
- Create: `docs/superpowers/verification/2026-09-01-sql-script-file-workflow.md`

**Interfaces:**
- Top bar menu id `sql-files`; actions `sql-file-open`, dynamic `sql-file-recent-*`, and `sql-file-recent-clear`.
- Default shortcuts: `SQL_OPEN_FILE=Ctrl+O`, `SQL_SAVE_FILE=Ctrl+S`, `SQL_SAVE_AS=Ctrl+Shift+S`.
- `AppShell.openSqlFile(Path)` reads through the application task runner and creates an unbound SQL tab only after success.

- [ ] **Step 1: Write failing entry tests**

Assert menu/action ids, shortcut defaults, opened file installs exact text and filename without provider/session/metadata/network calls, recent success updates the menu, missing recent path shows fixed failure and does not open a tab, and file-open failure leaves existing tabs unchanged.

- [ ] **Step 2: Verify RED**

Run entry and shortcut/settings tests; require absent actions/entry failure.

- [ ] **Step 3: Implement AppShell entry and documentation**

Build the menu lazily from `RecentSqlFiles.recent()`. FileChooser filters include `*.sql` and all files. Open on the application virtual-thread runner; on success create a new unbound editor, bind its controller to the loaded file, and select it. Add README user instructions and mark only the implemented P3 SQL-file increment complete; do not claim user-study metrics.

- [ ] **Step 4: Full verification and packaging**

Run:

```powershell
./gradlew.bat clean test --no-daemon --console=plain
./gradlew.bat jpackageImage --rerun-tasks --no-daemon --console=plain
```

Inspect XML counts and the app-image for production classes with no test helpers. Use a synthetic isolated profile for actual packaged-entry checks; record unverified desktop interactions separately.

- [ ] **Step 5: Commit and integration gate**

Commit as `feat: open and reuse SQL script files`. Review the complete branch against the design, fix blocking findings with focused regression, rerun the full suite, then fast-forward merge to local `main` under the existing user authorization. Do not push without a new explicit request.
