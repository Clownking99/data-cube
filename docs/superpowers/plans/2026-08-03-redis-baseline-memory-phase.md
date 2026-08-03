# Redis Baseline and Memory Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Commit the verified Redis一期 as a clean baseline, then reduce idle memory with a G1 256MB launcher profile and lazy construction of heavyweight startup services.

**Architecture:** Preserve the Redis subsystem exactly as verified, changing only commit state in the baseline task. Resource optimization is isolated behind launcher flags, `AppSettings` defaults, and a small generic `LazyValue<T>` used by `AppShell`; existing Pane behavior and public service APIs remain unchanged.

**Tech Stack:** Java 25, JavaFX 25, Gradle 9.2, org.beryx.jlink 4.1.0, JUnit 5, PowerShell 7, CodeGraph.

## Global Constraints

- Work directly on `main`; every completed task is a separate commit.
- Windows is the primary release platform, while source and ordinary Gradle execution remain cross-platform.
- Default GUI profile is G1 with `-Xms16m -Xmx256m`.
- Do not persist or print the user-provided Redis password.
- `.testagent` remains local and must not enter a commit.
- The real Redis integration test uses environment variables, random `datacube:smoke:*` keys, and cleanup in `finally`.
- Use `apply_patch` for source and document edits.
- Run CodeGraph before locating or reasoning about indexed source, and sync it after changes.

## File Map

- `README.md`: document Redis support and the 256MB default resource profile.
- `.codegraph/.gitignore`: keep the local CodeGraph database out of Git while preserving initialization metadata.
- `docs/superpowers/plans/2026-08-03-redis-support.md`: Redis一期 execution evidence.
- `src/com/datacube/redis/*.java`: RESP2, sessions, lifecycle and console support; baseline only in this plan.
- `src/com/datacube/fx/RedisKeyBrowserPane.java`: Redis key/value UI; baseline only in this plan.
- `src/com/datacube/fx/RedisConsolePane.java`: Redis console UI; baseline only in this plan.
- `test/com/datacube/redis/*.java`: Redis unit and opt-in live integration tests; baseline only in this plan.
- `build.gradle`: GUI launcher memory flags.
- `src/com/datacube/config/AppSettings.java`: persisted default maximum heap.
- `test/com/datacube/config/AppSettingsTest.java`: default/custom heap regression tests.
- `src/com/datacube/fx/LazyValue.java`: package-local, thread-safe lazy holder for heavyweight UI services.
- `test/com/datacube/fx/LazyValueTest.java`: proves no eager construction and exactly-once access/cleanup behavior.
- `src/com/datacube/fx/AppShell.java`: lazy `MigrationPane` and `UpdateService` wiring.
- `tools/measure-memory.ps1`: reproducible packaged-image idle memory measurement and process cleanup.

## Scope Boundary

This plan implements sections 3 and 4 of the approved modernization design. The remaining independently testable sub-projects are intentionally planned at their phase boundaries so file paths and interfaces reflect the code produced by earlier phases:

- CI and release gates.
- Atomic configuration and versioned credentials.
- Virtual-thread task lifecycle and managed tabs.
- UI decomposition and JavaFX smoke tests.
- Gradle 10, JDK 25/CDS and deprecated-API cleanup.

---

### Task 1: Freeze and Commit the Redis一期 Baseline

**Files:**
- Modify: `README.md`
- Modify: `src/com/datacube/fx/AppShell.java`
- Modify: `src/com/datacube/fx/ConnectionDialog.java`
- Modify: `src/com/datacube/fx/ConnectionTreePane.java`
- Modify: `src/com/datacube/service/ConnectionManager.java`
- Modify: `src/com/datacube/spi/model/ConnConfig.java`
- Modify: `src/com/datacube/spi/model/DbType.java`
- Create: `.codegraph/.gitignore`
- Create: `docs/superpowers/plans/2026-08-03-redis-support.md`
- Create: `src/com/datacube/fx/RedisConsolePane.java`
- Create: `src/com/datacube/fx/RedisKeyBrowserPane.java`
- Create: `src/com/datacube/redis/*.java`
- Create: `test/com/datacube/redis/*.java`
- Exclude: `.testagent/**`

**Interfaces:**
- Consumes: approved `docs/superpowers/specs/2026-07-08-redis-support-design.md`.
- Produces: committed Redis baseline with `ConnectionManager.acquireRedis`, `openRedisSession`, `closeRedisSession`, `RedisSession`, and opt-in `RedisLiveIntegrationTest`.

- [x] **Step 1: Confirm the live-test credential is environment-only**

Run:

```powershell
rg -n "DATACUBE_REDIS_(HOST|PORT|DB|USERNAME|PASSWORD)" test/com/datacube/redis
git diff -- README.md src test docs/superpowers/plans .codegraph
```

Expected: the live test reads environment variable names; no literal live host or password appears in the diff.

- [x] **Step 2: Run the ordinary regression suite without live credentials**

Run:

```powershell
Remove-Item Env:DATACUBE_REDIS_HOST,Env:DATACUBE_REDIS_PORT,Env:DATACUBE_REDIS_DB,Env:DATACUBE_REDIS_USERNAME,Env:DATACUBE_REDIS_PASSWORD -ErrorAction SilentlyContinue
.\gradlew.bat clean test
```

Expected: `BUILD SUCCESSFUL`; the opt-in live test is skipped and all other tests pass.

- [x] **Step 3: Run the opt-in Redis smoke test**

Set the five `DATACUBE_REDIS_*` variables in the current process without echoing their values, then run:

```powershell
.\gradlew.bat test --tests com.datacube.redis.RedisLiveIntegrationTest --rerun-tasks
```

Expected: `standaloneRedisSupportsFiveTypesScanTtlAndLifecycle` passes and verifies that its randomized keys were removed.

- [x] **Step 4: Verify the linked image**

Run:

```powershell
.\gradlew.bat jlink
Test-Path build/image
git diff --check
codegraph sync .
```

Expected: jlink reports `BUILD SUCCESSFUL`, `Test-Path` prints `True`, diff check exits 0, and CodeGraph reports a successful sync.

- [x] **Step 5: Stage only the Redis baseline**

Run:

```powershell
git add -- README.md .codegraph/.gitignore docs/superpowers/plans/2026-08-03-redis-support.md src/com/datacube/fx/AppShell.java src/com/datacube/fx/ConnectionDialog.java src/com/datacube/fx/ConnectionTreePane.java src/com/datacube/fx/RedisConsolePane.java src/com/datacube/fx/RedisKeyBrowserPane.java src/com/datacube/service/ConnectionManager.java src/com/datacube/spi/model/ConnConfig.java src/com/datacube/spi/model/DbType.java src/com/datacube/redis test/com/datacube/redis
git diff --cached --name-only
git diff --cached --check
```

Expected: every listed Redis source/test/document is staged; `.testagent` and this modernization plan are not staged; cached diff check exits 0.

- [x] **Step 6: Commit the baseline**

Run:

```powershell
git commit -m "feat: 完成 Redis 一期管理支持"
git show --stat --oneline HEAD
```

Expected: one baseline commit containing the verified Redis implementation and no `.testagent` files.

---

### Task 2: Set the G1 256MB Default Profile

**Files:**
- Modify: `build.gradle:185-197`
- Modify: `src/com/datacube/config/AppSettings.java:49-60`
- Create: `test/com/datacube/config/AppSettingsTest.java`

**Interfaces:**
- Consumes: `AppSettings(Path file)`, `getMaxHeapMb()`, and the beryx `jlink.launcher.jvmArgs` DSL.
- Produces: `AppSettings.DEFAULT_MAX_HEAP_MB == 256` and a GUI launcher containing `-Xms16m -Xmx256m` with the approved G1 free-ratio parameters.

- [x] **Step 1: Write failing default/custom heap tests**

Create `test/com/datacube/config/AppSettingsTest.java`:

```java
package com.datacube.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppSettingsTest {

    @TempDir Path tempDir;

    @Test
    void defaultsToBalanced256MbHeap() {
        AppSettings settings = new AppSettings(tempDir.resolve("settings.properties"));
        assertEquals(256, settings.getMaxHeapMb());
    }

    @Test
    void preservesExplicitHeapFromExistingSettings() throws Exception {
        Path file = tempDir.resolve("settings.properties");
        Files.writeString(file, "jvm.maxHeapMb=1024\n");
        AppSettings settings = new AppSettings(file);
        assertEquals(1024, settings.getMaxHeapMb());
    }
}
```

- [x] **Step 2: Run the focused test to observe the default mismatch**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.config.AppSettingsTest
```

Expected: `defaultsToBalanced256MbHeap` fails with expected 256 but actual 512; the explicit 1024 test passes.

- [x] **Step 3: Change the persisted default and launcher flags**

Change `AppSettings`:

```java
public static final int DEFAULT_MAX_HEAP_MB = 256;
```

Change the GUI launcher block in `build.gradle` to:

```groovy
jvmArgs = [
    '-Xms16m',
    '-Xmx256m',
    '-XX:+UseG1GC',
    '-XX:MaxHeapFreeRatio=20',
    '-XX:MinHeapFreeRatio=5',
    '-XX:G1PeriodicGCInterval=30000',
    '--enable-native-access=com.datacube'
]
```

Update the adjacent comment to state that 256MB is the balanced default and remains user-overridable.

- [x] **Step 4: Verify tests and generated launcher**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.config.AppSettingsTest
.\gradlew.bat jlink
Select-String -Path build/image/bin/DataCube.bat -Pattern '-Xms16m','-Xmx256m','G1PeriodicGCInterval=30000'
```

Expected: both tests pass, jlink succeeds, and all three options appear in the generated launcher.

- [x] **Step 5: Commit the balanced profile**

Run:

```powershell
git add -- build.gradle src/com/datacube/config/AppSettings.java test/com/datacube/config/AppSettingsTest.java
git diff --cached --check
git commit -m "perf: 默认使用 G1 256MB 平衡配置"
```

Expected: one commit containing only launcher/default-setting changes and their tests.

---

### Task 3: Lazily Construct Migration and Update Services

**Files:**
- Create: `src/com/datacube/fx/LazyValue.java`
- Create: `test/com/datacube/fx/LazyValueTest.java`
- Modify: `src/com/datacube/fx/AppShell.java:64-180`

**Interfaces:**
- Consumes: `MigrationPane::new`, `MigrationPane.isRunning()`, `MigrationPane.shutdown()`, `UpdateService::new`.
- Produces: `LazyValue<T>.get()`, `peek()`, and `ifInitialized(Consumer<? super T>)` for package-local AppShell use.

- [x] **Step 1: Write failing lazy lifecycle tests**

Create `test/com/datacube/fx/LazyValueTest.java`:

```java
package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LazyValueTest {

    @Test
    void doesNotConstructUntilFirstGetAndConstructsOnlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        LazyValue<Object> lazy = new LazyValue<>(() -> {
            calls.incrementAndGet();
            return new Object();
        });

        assertTrue(lazy.peek().isEmpty());
        assertEquals(0, calls.get());
        Object first = lazy.get();
        assertSame(first, lazy.get());
        assertEquals(1, calls.get());
    }

    @Test
    void cleanupCallbackRunsOnlyAfterInitialization() {
        AtomicInteger cleaned = new AtomicInteger();
        LazyValue<Object> lazy = new LazyValue<>(Object::new);

        lazy.ifInitialized(value -> cleaned.incrementAndGet());
        assertEquals(0, cleaned.get());
        lazy.get();
        lazy.ifInitialized(value -> cleaned.incrementAndGet());
        assertEquals(1, cleaned.get());
    }
}
```

- [x] **Step 2: Run the focused test to verify the type is missing**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.fx.LazyValueTest
```

Expected: test compilation fails because `LazyValue` does not exist.

- [x] **Step 3: Implement the minimal lazy holder**

Create `src/com/datacube/fx/LazyValue.java`:

```java
package com.datacube.fx;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class LazyValue<T> {
    private Supplier<? extends T> supplier;
    private T value;

    LazyValue(Supplier<? extends T> supplier) {
        this.supplier = Objects.requireNonNull(supplier, "supplier");
    }

    synchronized T get() {
        if (value == null) {
            value = Objects.requireNonNull(supplier.get(), "supplier returned null");
            supplier = null;
        }
        return value;
    }

    synchronized Optional<T> peek() {
        return Optional.ofNullable(value);
    }

    synchronized void ifInitialized(Consumer<? super T> action) {
        Objects.requireNonNull(action, "action");
        if (value != null) action.accept(value);
    }
}
```

- [x] **Step 4: Run the lazy holder tests**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.fx.LazyValueTest
```

Expected: both tests pass.

- [x] **Step 5: Replace eager AppShell fields and call sites**

Replace fields:

```java
private final LazyValue<MigrationPane> migrationPane = new LazyValue<>(MigrationPane::new);
private final LazyValue<UpdateService> updateService = new LazyValue<>(UpdateService::new);
```

Use the lazy values at call sites:

```java
migrationBtn.setOnAction(e ->
        contentTabs.openSingletonTab("数据迁移", migrationPane.get().getNode()));

aboutBtn.setOnAction(e -> AboutDialog.show(updateService.get(),
        root.getScene() == null ? null : root.getScene().getWindow(), themeManager));

public boolean isRunning() {
    return migrationPane.peek().map(MigrationPane::isRunning).orElse(false);
}

public void checkForUpdatesOnStartup() {
    UpdateService service = updateService.get();
    service.checkInBackground(info -> Platform.runLater(() ->
            UpdateUI.promptUpdate(service, info,
                    root.getScene() == null ? null : root.getScene().getWindow())));
}

public void shutdown() {
    try {
        migrationPane.ifInitialized(MigrationPane::shutdown);
    } finally {
        connMgr.closeAll();
    }
}
```

- [x] **Step 6: Run regression and image verification**

Run:

```powershell
.\gradlew.bat clean test
.\gradlew.bat jlink
```

Expected: all ordinary tests pass with only the live Redis test skipped; jlink succeeds.

- [x] **Step 7: Commit lazy startup construction**

Run:

```powershell
git add -- src/com/datacube/fx/LazyValue.java src/com/datacube/fx/AppShell.java test/com/datacube/fx/LazyValueTest.java
git diff --cached --check
git commit -m "perf: 延迟初始化迁移与更新服务"
```

Expected: one commit containing only the lazy holder, AppShell wiring, and tests.

---

### Task 4: Add a Reproducible Idle-Memory Measurement

**Files:**
- Create: `tools/measure-memory.ps1`
- Modify: `README.md`

**Interfaces:**
- Consumes: `build/image/bin/javaw.exe` and linked module `com.datacube/com.datacube.DataCubeFx`.
- Produces: a PowerShell command that outputs PID, elapsed seconds, working-set MB, private MB and thread count, then closes only the process it started.

- [x] **Step 1: Create the measurement script**

Create `tools/measure-memory.ps1`:

```powershell
param(
    [int]$WarmupSeconds = 12,
    [double]$ExpectedMaxWorkingSetMB = 175
)

$ErrorActionPreference = 'Stop'
$javaw = (Resolve-Path "$PSScriptRoot/../build/image/bin/javaw.exe").Path
$vmArgs = @(
    '-Xms16m', '-Xmx256m', '-XX:+UseG1GC',
    '-XX:MaxHeapFreeRatio=20', '-XX:MinHeapFreeRatio=5',
    '-XX:G1PeriodicGCInterval=30000',
    '--enable-native-access=com.datacube',
    '-m', 'com.datacube/com.datacube.DataCubeFx'
)

$process = Start-Process -FilePath $javaw -ArgumentList $vmArgs -PassThru -WindowStyle Hidden
try {
    Start-Sleep -Seconds $WarmupSeconds
    $sample = Get-Process -Id $process.Id
    $result = [pscustomobject]@{
        Pid = $sample.Id
        ElapsedSeconds = $WarmupSeconds
        WorkingSetMB = [math]::Round($sample.WorkingSet64 / 1MB, 1)
        PrivateMB = [math]::Round($sample.PrivateMemorySize64 / 1MB, 1)
        Threads = $sample.Threads.Count
    }
    $result | Format-List
    if ($result.WorkingSetMB -gt $ExpectedMaxWorkingSetMB) {
        throw "Working set $($result.WorkingSetMB)MB exceeds $ExpectedMaxWorkingSetMB MB"
    }
} finally {
    $live = Get-Process -Id $process.Id -ErrorAction SilentlyContinue
    if ($live) {
        $null = $live.CloseMainWindow()
        if (-not $live.WaitForExit(3000)) {
            Stop-Process -Id $process.Id -Force
        }
    }
}
```

- [x] **Step 2: Document the profile and measurement command**

Add to README's build/performance section:

````markdown
### 内存基线

GUI 启动器默认使用 G1 平衡配置（初始堆 16MB、最大堆 256MB）。构建
`jlink` 镜像后可在 Windows PowerShell 运行：

```powershell
.\tools\measure-memory.ps1
```

脚本等待主窗口稳定后输出工作集、私有内存和线程数，并只关闭自己启动的进程。
````

- [x] **Step 3: Build and run the measurement**

Run:

```powershell
.\gradlew.bat jlink
.\tools\measure-memory.ps1 -WarmupSeconds 12 -ExpectedMaxWorkingSetMB 175
```

Expected on the established development machine: exit 0, working set no more than 175MB, and no remaining process whose executable path is the measured `build/image/bin/javaw.exe`.

- [x] **Step 4: Run final phase verification**

Run:

```powershell
Remove-Item Env:DATACUBE_REDIS_HOST,Env:DATACUBE_REDIS_PORT,Env:DATACUBE_REDIS_DB,Env:DATACUBE_REDIS_USERNAME,Env:DATACUBE_REDIS_PASSWORD -ErrorAction SilentlyContinue
.\gradlew.bat clean test
.\gradlew.bat jlink
git diff --check
codegraph sync .
codegraph status
```

Expected: tests and jlink succeed, diff check exits 0, and CodeGraph reports `[OK] Index is up to date`.

- [x] **Step 5: Commit measurement tooling and documentation**

Run:

```powershell
git add -- tools/measure-memory.ps1 README.md docs/superpowers/plans/2026-08-03-redis-baseline-memory-phase.md
git diff --cached --check
git commit -m "perf: 增加空闲内存基线验证"
```

Expected: one commit containing the measurement script, README instructions, and this implementation plan.

## Phase Completion Evidence

Before starting the CI sub-project, record:

- Redis baseline commit ID.
- G1 profile commit ID and generated launcher flags.
- Lazy-startup commit ID and `LazyValueTest` results.
- `clean test` totals, including the single opt-in live-test skip.
- `jlink` result.
- 12-second memory sample and the absence of a remaining measured Java process.
- CodeGraph up-to-date statistics.
