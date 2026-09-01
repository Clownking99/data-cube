# DataCube Single-Launcher Distribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `DataCube.exe` the only Windows application launcher shipped in DataCube releases.

**Architecture:** Preserve all migration implementation and the Java console entry class, but remove the jpackage secondary-launcher declaration. Protect the product contract with a source-level build/documentation test and verify the actual Windows app-image contents.

**Tech Stack:** Gradle 9.2, org.beryx.jlink 4.1.0, jpackage, Java 25, JUnit 5, Markdown.

## Global Constraints

- Do not delete `com.datacube.DataCube` or migration implementation code.
- Do not change GUI migration behavior or database behavior.
- The Windows app-image must contain `DataCube.exe` and must not contain `DataCubeCli.exe` or `DataCubeCli.cfg`.

---

### Task 1: Lock the single-launcher release contract

**Files:**
- Create: `test/com/datacube/build/DistributionLauncherContractTest.java`
- Modify: `build.gradle:210-215`
- Modify: `README.md:3-6,18,27,30,205,216-240`

**Interfaces:**
- Consumes: repository-root `build.gradle` and `README.md` text.
- Produces: a JUnit contract that rejects a published `DataCubeCli` secondary launcher or user-facing download instruction.

- [x] **Step 1: Write the failing test**

Create `DistributionLauncherContractTest` with one test that asserts `build.gradle` contains the primary `DataCube` launcher, does not contain `secondaryLauncher` or `DataCubeCli`, and that README does not advertise `DataCubeCli.exe`.

- [x] **Step 2: Run the focused test to verify RED**

Run: `./gradlew.bat test --tests com.datacube.build.DistributionLauncherContractTest --no-daemon --console=plain`

Expected: FAIL because the current build and README still declare `DataCubeCli`.

- [x] **Step 3: Implement the minimal release change**

Delete the `secondaryLauncher` block from `build.gradle`. Rewrite README so the desktop GUI is the single released entry and the migration section directs users to the top-level “数据迁移” workspace. Keep the existing full/incremental behavior table.

- [x] **Step 4: Run the focused test to verify GREEN**

Run the same focused Gradle test.

Expected: PASS.

### Task 2: Verify source and packaged artifacts

**Files:**
- Verify: `build/jpackage/DataCube/DataCube.exe`
- Verify absent: `build/jpackage/DataCube/DataCubeCli.exe`
- Verify absent: `build/jpackage/DataCube/app/DataCubeCli.cfg`

**Interfaces:**
- Consumes: Task 1 build configuration.
- Produces: a clean Windows app-image with one user launcher.

- [x] **Step 1: Run the complete automated test suite**

Run: `./gradlew.bat clean test --no-daemon --console=plain`

Expected: exit code 0 and zero failed tests.

- [x] **Step 2: Build a fresh app-image**

Run: `./gradlew.bat jpackageImage --rerun-tasks --no-daemon --console=plain`

Expected: exit code 0.

- [x] **Step 3: Inspect exact launcher contents**

Assert that `build/jpackage/DataCube/DataCube.exe` exists and that neither `DataCubeCli.exe` nor `app/DataCubeCli.cfg` exists. Run `git diff --check` and inspect `git status --short` without touching `.testagent/`.
