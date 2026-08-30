# SQL Draft Process Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify the completed draft path across independent JVMs and expose the actual AppShell for isolated desktop acceptance.

**Architecture:** A local-only verification launcher exercises existing production controls, coordinator, writer and managed close. Process modes distinguish normal shutdown, confirmed checkpoint followed by abrupt exit, restore, and persistent disable. The desktop mode uses actual AppShell with a verified disposable user.home and omits only the outer DataCubeFx public update check.

**Tech Stack:** Existing Java25/JavaFX25/Gradle and test-only rejecting connection probe; no dependencies or production changes.

## Global Constraints

- Execute only after manager Task1 review is clean; do not redispatch completed storage/runtime/editor/manager work.
- Worktree `D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery`; no main merge until all P1 gates, no push/tag/install/release.
- Never read/write/delete/stage `.testagent/` contents or real user configurations, credentials, history or database data.
- Every process receives an explicit owned temporary directory; only terminate child Process objects created by this launcher, never process-name sweeps.
- Assertions distinguish confirmed checkpoints from unflushed edits; no claim of zero loss on power failure.
- Only the test harness may call Runtime.halt, in the dedicated abrupt child after a confirmed checkpoint. No production changes or OS security/privacy modifications.
- Desktop control must use computer-use skill. Do not click privacy settings or confirm deletions via that tool; automated isolated tests cover those paths.

---

### Task 1: Verify restart boundaries and prepare isolated desktop entry

**Files:**
- Create local ignored artifact: `.superpowers/sdd/SqlDraftAcceptanceLauncher.java`.
- Create local ignored artifact: `.superpowers/sdd/draft-acceptance.init.gradle`.
- Report: `.superpowers/sdd/draft-process-task-1-report.md`.
- Controller alone updates tracked verification docs after actual runs. No production or JUnit source edits in this task.

**Interfaces:**
- Consumes completed SqlDraftUi/SqlDraftRecoveryTabs/SqlEditorPane managed APIs and test-only DraftConnectionProbe.
- Produces Gradle `verifySqlDraftProcesses` (terminating validation), `runSqlDraftDesktop` (interactive window, requires `-PdraftAcceptanceHome=<owned-directory>`).
- Existing behavior verification, not a production feature: do not invent a product RED by deliberately breaking production. First compile/run result is recorded honestly; any discovered product failure gets a separate regression/fix/review cycle.

- [ ] **Step 1: Add the exact harness and init script below.** Confirm current signatures against the clean manager task before compilation. Programmatic control inside this isolated Java test harness is automated integration evidence, not a claim that a human desktop path has been tested.

```java
package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import com.datacube.config.SqlDraftStore;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.DbType;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;

/** Disposable verification entry only; never part of the application distribution. */
public final class SqlDraftAcceptanceLauncher {
    private static final String SQL = "  -- synthetic 草稿\nselect a.\nfrom demo a;\n";
    private static final String SCHEMA = "  synthetic_schema  ";
    private static final String MARKER = "DRAFT_CHECKPOINT_CONFIRMED";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Explicit acceptance mode required");
        if (args[0].equals("processes")) {
            Path root = Files.createTempDirectory("datacube-draft-process-");
            child("normal", root.resolve("normal"), 0);
            child("restore", root.resolve("normal"), 0);
            child("abrupt", root.resolve("abrupt"), 37);
            child("restore", root.resolve("abrupt"), 0);
            child("disable", root.resolve("disabled"), 0);
            child("verify-disabled", root.resolve("disabled"), 0);
            child("lock-holder", root.resolve("locked"), 0);
            child("restore", root.resolve("locked"), 0);
            System.out.println("PROCESS_ACCEPTANCE_PASS=" + root);
            return;
        }
        if (args.length != 2) throw new IllegalArgumentException("Explicit isolated directory required");
        Path directory = Path.of(args[1]).toAbsolutePath().normalize();
        if (args[0].equals("desktop")) {
            desktop(directory);
            return;
        }
        Files.createDirectories(directory);
        startFx();
        try (Fixture fixture = new Fixture(directory, !args[0].equals("locked-probe"))) {
            switch (args[0]) {
                case "normal", "abrupt", "lock-holder" -> {
                    fixture.openForWrite();
                    fixture.await(() -> fixture.handle().status().saveStatus() == SqlDraftCoordinator.SaveStatus.SAVED);
                    SqlDraft record = fx(() -> fixture.owner.runtime().refresh()).get(5, TimeUnit.SECONDS)
                            .snapshot().drafts().getFirst();
                    check(SQL.equals(record.sql()) && SCHEMA.equals(record.schema()), "checkpoint mismatch");
                    fixture.offline();
                    System.out.println(MARKER);
                    System.out.flush();
                    if (args[0].equals("lock-holder")) {
                        child("locked-probe", directory, 0);
                        SqlDraft after = fx(() -> fixture.owner.runtime().refresh()).get(5, TimeUnit.SECONDS)
                                .snapshot().drafts().getFirst();
                        check(record.equals(after), "second process changed the locked checkpoint");
                    }
                    if (args[0].equals("abrupt")) fx(() -> {
                        fixture.pane.setSqlText("UNFLUSHED_SYNTHETIC_EDIT");
                        Runtime.getRuntime().halt(37);
                        return null;
                    });
                }
                case "restore" -> {
                    var snapshot = fx(() -> fixture.owner.runtime().lastManagementResult().snapshot());
                    check(snapshot.drafts().size() == 1, "expected one durable record");
                    SqlDraft record = snapshot.drafts().getFirst();
                    check(SQL.equals(record.sql()) && SCHEMA.equals(record.schema()), "wrong recovered checkpoint");
                    fx(() -> {
                        check(fixture.recovery.restore(record), "managed restore refused");
                        fixture.layout();
                        CodeArea editor = (CodeArea) fixture.pane.getNode().lookup("#sql-editor");
                        check(SQL.equals(editor.getText()), "recovered editor text differs");
                        check(SCHEMA.equals(((TextField) field(fixture.pane, "schemaField")).getText()), "schema trimmed");
                        check(fixture.recovery.restore(record), "duplicate focus refused");
                        check(((TabPane) fixture.tabs.getNode()).getTabs().size() == 1, "duplicate tab created");
                        return null;
                    });
                    fixture.offline();
                }
                case "disable" -> {
                    check(fx(() -> fixture.owner.runtime().setEnabled(false)).get(5, TimeUnit.SECONDS).succeeded(),
                            "disable was not persisted");
                    fixture.openForWrite();
                    fx(() -> { check(fixture.owner.runtime().mode() == SqlDraftCoordinator.Mode.DISABLED,
                            "disabled mode not applied"); return null; });
                }
                case "verify-disabled" -> fx(() -> {
                    check(fixture.owner.runtime().mode() == SqlDraftCoordinator.Mode.DISABLED, "disable lost on restart");
                    check(fixture.owner.runtime().lastManagementResult().snapshot().drafts().isEmpty(),
                            "disabled text was persisted");
                    return null;
                });
                case "locked-probe" -> fx(() -> {
                    check(fixture.owner.runtime().mode() == SqlDraftCoordinator.Mode.UNAVAILABLE,
                            "second process acquired active writer lock");
                    check(fixture.owner.runtime().lastManagementResult() == null,
                            "failed lock acquisition published a management snapshot");
                    check(fixture.pane == null, "second process constructed an editor");
                    return null;
                });
                default -> throw new IllegalArgumentException("Unknown acceptance mode");
            }
            fixture.offline();
        } finally { Platform.exit(); }
        System.out.println("CHILD_PASS=" + args[0]);
    }

    private static void child(String mode, Path directory, int expectedExit) throws Exception {
        Files.createDirectories(directory);
        Path log = directory.resolve(mode + ".log");
        String classpath = System.getProperty("draft.acceptance.classpath");
        if (classpath == null || classpath.isBlank()) throw new IllegalStateException("Explicit child classpath required");
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process process = new ProcessBuilder(java.toString(), "--enable-native-access=ALL-UNNAMED",
                "-Djava.awt.headless=false", "-Duser.home=" + directory,
                "-Ddraft.acceptance.classpath=" + classpath, "-cp", classpath,
                SqlDraftAcceptanceLauncher.class.getName(),
                mode, directory.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            check(process.waitFor(30, TimeUnit.SECONDS), "child timed out: " + mode);
            String output = Files.readString(log);
            check(process.exitValue() == expectedExit, "child failed: " + mode + " exit=" + process.exitValue()
                    + " log=" + log);
            check(output.contains(mode.equals("abrupt") ? MARKER : "CHILD_PASS=" + mode), "missing child evidence");
            System.out.println("CHILD_RESULT=" + mode + ",exit=" + process.exitValue());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                check(process.waitFor(5, TimeUnit.SECONDS), "owned child did not terminate");
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final FxTaskRunner runner = new FxTaskRunner();
        final SessionContext context = new SessionContext();
        final Path directory;
        final ContentTabPane tabs;
        final SqlDraftUi owner;
        final SqlDraftRecoveryTabs recovery;
        SqlEditorPane pane;

        Fixture(Path directory, boolean expectAvailable) throws Exception {
            this.directory = directory;
            tabs = fx(ContentTabPane::new);
            owner = fx(() -> new SqlDraftUi(directory.resolve("drafts")));
            recovery = fx(() -> {
                new Scene((TabPane) tabs.getNode(), 1000, 700);
                return new SqlDraftRecoveryTabs(tabs, owner, record -> {
                    pane = SqlEditorPane.recoverDraft(context, probe.manager, new ObjectTreeService(probe.manager),
                            new AppSettings(directory.resolve("settings")), null, record,
                            new SqlHistoryStore(directory.resolve("history")),
                            new ShortcutSettings(directory.resolve("shortcuts")), runner);
                    return pane;
                }, ignored -> {});
            });
            await(() -> !owner.runtime().managementPending());
            fx(() -> {
                check((owner.runtime().mode() != SqlDraftCoordinator.Mode.UNAVAILABLE) == expectAvailable,
                        "unexpected store availability");
                return null;
            });
        }
        void openForWrite() throws Exception {
            fx(() -> {
                SqlDraft empty = new SqlDraft(UUID.randomUUID(), System.currentTimeMillis(), "missing",
                        DbType.POSTGRESQL, "synthetic missing connection", SCHEMA, "");
                check(recovery.restore(empty), "writer pane refused");
                pane.setSqlText(SQL);
                layout();
                return null;
            });
        }
        void layout() { tabs.getNode().applyCss(); ((TabPane) tabs.getNode()).layout(); }
        SqlDraftCoordinator.Handle handle() {
            return (SqlDraftCoordinator.Handle) field(field(pane, "draftBinding"), "handle");
        }
        void await(BooleanSupplier ready) throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            AutoCloseable subscription = fx(() -> {
                Runnable check = () -> { if (ready.getAsBoolean()) latch.countDown(); };
                AutoCloseable registered = owner.observe(check);
                check.run();
                return registered;
            });
            try { check(latch.await(8, TimeUnit.SECONDS), "application timer/state timed out"); }
            finally { fx(() -> { subscription.close(); return null; }); }
        }
        void offline() {
            check(probe.providers.get() == 0 && probe.sessions.get() == 0
                    && probe.metadata.get() == 0 && probe.network.get() == 0, "passive database access occurred");
        }
        public void close() throws Exception {
            try {
                check(tabs.closeAllManagedTabsMandatory().toCompletableFuture().get(8, TimeUnit.SECONDS)
                        == TabCloseOutcome.COMPLETED, "managed shutdown refused");
                offline();
            } finally {
                if (pane != null) {
                    pane.closeResources();
                    fx(() -> { pane.finalizeCloseOnFx(); return null; });
                }
                owner.closeFromBackground();
                runner.close();
                probe.manager.closeAll();
            }
        }
    }

    private static void desktop(Path directory) throws Exception {
        Path configured = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        check(configured.equals(directory), "desktop user.home must be isolated before JVM startup");
        check(directory.getFileName().toString().startsWith("datacube-draft-ui-"), "unexpected desktop directory");
        check(Files.isRegularFile(directory.resolve("ISOLATED_TEST_PROFILE")), "owned marker missing");
        Path config = directory.resolve(".datacube");
        Files.createDirectories(config);
        try (SqlDraftStore store = SqlDraftStore.open(config.resolve("sql-drafts"))) {
            if (store.snapshot().drafts().isEmpty()) store.save(new SqlDraft(UUID.randomUUID(), System.currentTimeMillis(),
                    "missing-synthetic", DbType.POSTGRESQL, "已删除的验收连接", SCHEMA, SQL));
        }
        startFx();
        fx(() -> {
            AppShell shell = new AppShell();
            Stage stage = new Stage();
            Scene scene = new Scene(shell.getRoot(), 1200, 800);
            shell.getThemeManager().register(scene);
            shell.getThemeManager().installWindowHook();
            stage.setScene(scene);
            stage.setTitle("DataCube SQL草稿隔离验收");
            stage.setOnCloseRequest(event -> {
                event.consume();
                shell.getRoot().setDisable(true);
                shell.shutdownAsync().whenComplete((outcome, failure) -> Platform.runLater(() -> {
                    if (failure == null && outcome == ShutdownOutcome.COMPLETED) {
                        stage.hide();
                        Platform.exit();
                    } else {
                        shell.getRoot().setDisable(false);
                        System.err.println("DESKTOP_CLOSE_REFUSED");
                    }
                }));
            });
            stage.show();
            System.out.println("DESKTOP_PROFILE=" + directory);
            System.out.println("DESKTOP_PID=" + ProcessHandle.current().pid());
            return null;
        });
    }

    private static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        check(ready.await(5, TimeUnit.SECONDS), "FX initialization timed out");
    }
    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(8, TimeUnit.SECONDS);
    }
    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
```

`.superpowers/sdd/draft-acceptance.init.gradle`:

```groovy
allprojects { project ->
    afterEvaluate {
        if (project != rootProject) return
        def compileDraftAcceptance = tasks.register('compileDraftAcceptance', JavaCompile) {
            dependsOn tasks.named('testClasses')
            source = files('.superpowers/sdd/SqlDraftAcceptanceLauncher.java')
            classpath = sourceSets.test.runtimeClasspath
            destinationDirectory = layout.buildDirectory.dir('draft-acceptance/classes')
            javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }
            modularity.inferModulePath = false
            options.encoding = 'UTF-8'
        }
        def configureEntry = { task ->
            task.dependsOn compileDraftAcceptance
            task.classpath = files(compileDraftAcceptance.flatMap { it.destinationDirectory }) + sourceSets.test.runtimeClasspath
            task.mainClass = 'com.datacube.fx.SqlDraftAcceptanceLauncher'
            task.javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
            task.modularity.inferModulePath = false
            task.jvmArgs '--enable-native-access=ALL-UNNAMED', '-Djava.awt.headless=false'
        }
        tasks.register('verifySqlDraftProcesses', JavaExec) { task ->
            configureEntry(task)
            args 'processes'
            doFirst { systemProperty 'draft.acceptance.classpath', classpath.asPath }
        }
        tasks.register('runSqlDraftDesktop', JavaExec) { task ->
            configureEntry(task)
            def profile = project.findProperty('draftAcceptanceHome')
            doFirst {
                if (profile == null) throw new GradleException('Explicit draftAcceptanceHome required')
                systemProperty 'user.home', profile.toString()
                args 'desktop', profile.toString()
            }
        }
    }
}
```

- [ ] **Step 2: Compile and execute process acceptance.**

```powershell
.\gradlew.bat -I .superpowers/sdd/draft-acceptance.init.gradle verifySqlDraftProcesses --no-daemon --console=plain
```

Expected: exit0 and eight top-level CHILD_RESULT lines: normal0,restore0,abrupt37,restore0,disable0,verify-disabled0,lock-holder0,restore0, followed by PROCESS_ACCEPTANCE_PASS with an owned temporary path. The lock-holder log must also contain nested locked-probe0: its overlapping process cannot take the lock or alter the exact checkpoint. Every child JVM uses its explicit directory as user.home and receives the explicit child classpath for the nested check. Abrupt37 is success only with prior confirmed-checkpoint marker. A first-pass success is valid verification of already-implemented behavior, not a new product RED/GREEN claim. Run no concurrent Gradle task. Read logs only under the returned isolated path.

- [ ] **Step 3: Produce report and hand desktop work to controller.** Report exact source SHA, command, exit codes, owned directories, assertions and failure distinctions. The controller verifies actual files/results and performs desktop controls after reading current CredentialCipher constructor isolation, then runs complete regression and jpackageImage. Do not launch a desktop or merge main from this subtask.

The desktop controller creates a unique `datacube-draft-ui-<uuid>` directory under the OS temporary directory with PowerShell New-Item, writes marker `ISOLATED_TEST_PROFILE` using apply_patch, and passes the resolved absolute path as draftAcceptanceHome. It must not target user home itself. The Java harness checks directory equality and marker before constructing AppShell.

## Self-review / remaining gate

All helper dependencies already exist in the completed manager task/test fixtures. The child classpath is explicit instead of assuming Gradle worker java.class.path contains application classes. All waits are bounded; force termination targets only the created Process object; no UI waits on disk futures. AppShell desktop isolates profile before JVM startup and omits automatic update invocation, so this is not the packaged DataCubeFx entry and does not prove installation/upgrade. Controlled process acceptance, desktop observation, package build and full branch review remain distinct evidence.
