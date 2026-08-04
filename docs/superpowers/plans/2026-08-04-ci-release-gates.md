# CI and Release Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add reproducible pull-request/main verification and make every Windows release depend on the same Java 25, Redis, unit-test, and jlink gates.

**Architecture:** A reusable `verify.yml` owns wrapper validation, Windows/Linux unit tests, Windows jlink, and an isolated Redis integration job. `release.yml` calls that reusable workflow, repeats `clean test` immediately before packaging, and publishes only after verification succeeds. A focused JUnit contract test protects the workflow invariants without requiring GitHub-hosted runners locally.

**Tech Stack:** GitHub Actions, Java 25, Gradle 9.2 Wrapper, JUnit 5, Redis 7.4 container, Windows jpackage/WiX 5.

## Global Constraints

- Work directly on `main`; each completed task is a separate commit.
- Windows remains the primary packaging platform; ordinary tests run on Windows and Linux.
- Use Temurin JDK 25 and the checked-in Gradle Wrapper in every workflow.
- CI Redis credentials are disposable test-only values; never use the user-provided Redis endpoint or password.
- Release packaging must not run unless reusable verification succeeds.
- Keep `.testagent/**` local and untracked.
- Use `apply_patch` for file edits and CodeGraph before indexed-source exploration.

---

### Task 1: Add the Reusable Verification Workflow

**Files:**
- Create: `.github/workflows/verify.yml`
- Create: `test/com/datacube/build/CiWorkflowContractTest.java`

**Interfaces:**
- Consumes: `gradlew`, `gradlew.bat`, Java toolchain 25, `RedisLiveIntegrationTest` environment variables.
- Produces: reusable workflow event `workflow_call` plus PR/main triggers and jobs `wrapper-validation`, `test`, and `redis-integration`.

- [x] **Step 1: Write the failing verification-workflow contract test**

Create `test/com/datacube/build/CiWorkflowContractTest.java`:

```java
package com.datacube.build;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiWorkflowContractTest {

    @Test
    void verificationWorkflowGatesPullRequestsMainWindowsLinuxAndRedis() throws IOException {
        String workflow = workflow("verify.yml");

        assertAll(
                () -> assertTrue(workflow.contains("pull_request:")),
                () -> assertTrue(workflow.contains("branches: [main]")),
                () -> assertTrue(workflow.contains("workflow_call:")),
                () -> assertTrue(workflow.contains("ubuntu-latest")),
                () -> assertTrue(workflow.contains("windows-latest")),
                () -> assertTrue(workflow.contains("actions/setup-java@v5")),
                () -> assertTrue(workflow.contains("java-version: '25'")),
                () -> assertTrue(workflow.contains("gradle/actions/setup-gradle@v6")),
                () -> assertTrue(workflow.contains("gradle/actions/wrapper-validation@v6")),
                () -> assertTrue(workflow.contains("clean test")),
                () -> assertTrue(workflow.contains("RedisLiveIntegrationTest")),
                () -> assertTrue(workflow.contains("redis:7.4-alpine")),
                () -> assertTrue(workflow.contains("jlink"))
        );
    }

    private static String workflow(String name) throws IOException {
        Path path = Path.of(System.getProperty("user.dir"), ".github", "workflows", name);
        assertTrue(Files.exists(path), "missing workflow: " + path);
        return Files.readString(path);
    }
}
```

- [x] **Step 2: Run the focused test and observe the missing workflow**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.build.CiWorkflowContractTest
```

Expected: FAIL with `missing workflow` for `.github/workflows/verify.yml`.

- [x] **Step 3: Create the reusable verification workflow**

Create `.github/workflows/verify.yml`:

```yaml
name: Verify

on:
  pull_request:
  push:
    branches: [main]
  workflow_call:

permissions:
  contents: read

concurrency:
  group: verify-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  wrapper-validation:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
      - uses: gradle/actions/wrapper-validation@v6

  test:
    name: Test (${{ matrix.os }})
    runs-on: ${{ matrix.os }}
    strategy:
      fail-fast: false
      matrix:
        include:
          - os: ubuntu-latest
            gradle: ./gradlew
          - os: windows-latest
            gradle: .\gradlew.bat
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v6
      - name: Unit tests
        run: ${{ matrix.gradle }} clean test --no-daemon --console=plain
      - name: Windows linked image
        if: runner.os == 'Windows'
        run: .\gradlew.bat jlink --no-daemon --console=plain

  redis-integration:
    runs-on: ubuntu-latest
    env:
      DATACUBE_REDIS_HOST: 127.0.0.1
      DATACUBE_REDIS_PORT: '6379'
      DATACUBE_REDIS_DB: '0'
      DATACUBE_REDIS_USERNAME: ''
      DATACUBE_REDIS_PASSWORD: datacube-ci-only
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v6
      - name: Start password-protected Redis
        shell: bash
        run: |
          docker run --detach --name datacube-redis --publish 6379:6379 \
            redis:7.4-alpine redis-server --requirepass "$DATACUBE_REDIS_PASSWORD"
          for attempt in {1..30}; do
            if docker exec -e REDISCLI_AUTH="$DATACUBE_REDIS_PASSWORD" datacube-redis redis-cli ping | grep -q PONG; then
              exit 0
            fi
            sleep 1
          done
          docker logs datacube-redis
          exit 1
      - name: Redis integration test
        run: ./gradlew test --tests com.datacube.redis.RedisLiveIntegrationTest --rerun-tasks --no-daemon --console=plain
      - name: Stop Redis
        if: always()
        run: docker rm --force datacube-redis
```

- [x] **Step 4: Run the focused contract test**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.build.CiWorkflowContractTest
```

Expected: PASS.

- [x] **Step 5: Commit reusable verification**

Run:

```powershell
git add -- .github/workflows/verify.yml test/com/datacube/build/CiWorkflowContractTest.java
git diff --cached --check
git commit -m "ci: 新增跨平台验证与 Redis 门禁"
```

Expected: commit contains only the reusable workflow and its contract test.

---

### Task 2: Gate the Release Workflow

**Files:**
- Modify: `test/com/datacube/build/CiWorkflowContractTest.java`
- Modify: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: reusable workflow `./.github/workflows/verify.yml` and its success result.
- Produces: release job `build` with `needs: verify`, a fresh `clean test`, Windows app-image/installer artifacts, and GitHub CLI release publication.

- [x] **Step 1: Add the failing release-gate contract test**

Add to `CiWorkflowContractTest`:

```java
    @Test
    void releaseWaitsForVerificationAndRetestsBeforePackaging() throws IOException {
        String workflow = workflow("release.yml");

        assertAll(
                () -> assertTrue(workflow.contains("uses: ./.github/workflows/verify.yml")),
                () -> assertTrue(workflow.contains("needs: verify")),
                () -> assertTrue(workflow.contains("actions/checkout@v6")),
                () -> assertTrue(workflow.contains("actions/setup-java@v5")),
                () -> assertTrue(workflow.contains("gradle/actions/setup-gradle@v6")),
                () -> assertTrue(workflow.contains("clean test")),
                () -> assertTrue(workflow.contains("jpackageImage")),
                () -> assertTrue(workflow.contains("jpackage -PinstallerType=exe")),
                () -> assertTrue(workflow.contains("gh release"))
        );
    }
```

- [x] **Step 2: Run the test and observe the missing release gate**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.build.CiWorkflowContractTest.releaseWaitsForVerificationAndRetestsBeforePackaging
```

Expected: FAIL because the current release workflow does not call `verify.yml` and uses older Action versions.

- [x] **Step 3: Update release workflow structure and tools**

Replace `.github/workflows/release.yml` with:

```yaml
name: Build and Release

on:
  push:
    tags:
      - 'v*'
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: release
  cancel-in-progress: false

env:
  JAVA_VERSION: '25'
  WIX_VERSION: '5.0.2'

jobs:
  verify:
    uses: ./.github/workflows/verify.yml

  build:
    needs: verify
    runs-on: windows-latest
    permissions:
      contents: write
    steps:
      - name: Checkout
        uses: actions/checkout@v6
        with:
          fetch-depth: 0

      - name: Setup Java
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: ${{ env.JAVA_VERSION }}

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v6

      - name: Compute version
        id: version
        shell: bash
        run: |
          if [[ "$GITHUB_REF" == refs/tags/v* ]]; then
            NEXT="${GITHUB_REF#refs/tags/}"
            echo "is_tag=true" >> "$GITHUB_OUTPUT"
          else
            LATEST=$(git tag --sort=-v:refname | grep '^v[0-9]' | head -1 || true)
            if [ -z "$LATEST" ]; then
              NEXT="v3.0.0"
            else
              V=${LATEST#v}
              MAJOR=$(echo "$V" | cut -d. -f1)
              MINOR=$(echo "$V" | cut -d. -f2)
              PATCH=$(echo "$V" | cut -d. -f3)
              NEXT="v${MAJOR}.${MINOR}.$((PATCH+1))"
            fi
            echo "is_tag=false" >> "$GITHUB_OUTPUT"
          fi
          echo "tag=$NEXT" >> "$GITHUB_OUTPUT"
          echo "app_version=${NEXT#v}" >> "$GITHUB_OUTPUT"

      - name: Verify release source
        shell: pwsh
        run: .\gradlew.bat clean test --no-daemon --console=plain

      - name: Install WiX Toolset (v5)
        shell: pwsh
        run: |
          dotnet tool install --global wix --version $env:WIX_VERSION
          $tools = Join-Path $env:USERPROFILE ".dotnet\tools"
          $tools | Out-File -FilePath $env:GITHUB_PATH -Append -Encoding utf8
          & "$tools\wix.exe" extension add --global WixToolset.Util.wixext/$env:WIX_VERSION
          & "$tools\wix.exe" extension add --global WixToolset.UI.wixext/$env:WIX_VERSION
          & "$tools\wix.exe" --version

      - name: Build app-image and exe installer
        shell: pwsh
        run: |
          $app = "${{ steps.version.outputs.app_version }}"
          .\gradlew.bat jpackageImage "-PappVersion=$app" --no-daemon --console=plain
          if ($LASTEXITCODE -ne 0) { throw "jpackageImage failed ($LASTEXITCODE)" }
          .\gradlew.bat jpackage -PinstallerType=exe "-PappVersion=$app" --no-daemon --console=plain
          if ($LASTEXITCODE -ne 0) { throw "jpackage failed ($LASTEXITCODE)" }

      - name: Assemble artifacts
        shell: pwsh
        run: |
          $tag = "${{ steps.version.outputs.tag }}"
          $app = "${{ steps.version.outputs.app_version }}"
          Compress-Archive -Path "build/jpackage/DataCube" -DestinationPath "DataCube-$tag-win64-portable.zip" -Force
          Copy-Item "build/jpackage/DataCube-$app.exe" "DataCube-$tag-win64-setup.exe"
          Get-ChildItem "DataCube-$tag-*" | Select-Object Name, @{N='MB';E={"{0:N1}" -f ($_.Length/1MB)}}

      - name: Create tag
        if: steps.version.outputs.is_tag != 'true'
        shell: bash
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          git tag -a "${{ steps.version.outputs.tag }}" -m "Release ${{ steps.version.outputs.tag }}"
          git push origin "${{ steps.version.outputs.tag }}"

      - name: Publish release
        shell: pwsh
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          $tag = "${{ steps.version.outputs.tag }}"
          $repo = "${{ github.repository }}"
          $portable = "DataCube-$tag-win64-portable.zip"
          $setup = "DataCube-$tag-win64-setup.exe"
          @"
          ## DataCube $tag

          | 文件 | 说明 |
          |------|------|
          | $portable | Windows x64 免安装绿色版，内置运行时。 |
          | $setup | Windows x64 安装程序，内置运行时。 |
          "@ | Set-Content -LiteralPath release-notes.md -Encoding utf8

          gh release view "$tag" --repo "$repo" *> $null
          $exists = $LASTEXITCODE -eq 0
          if ($exists) {
            gh release edit "$tag" --repo "$repo" --title "Release $tag" --notes-file release-notes.md
            if ($LASTEXITCODE -ne 0) { throw "gh release edit failed ($LASTEXITCODE)" }
            gh release upload "$tag" "$portable" "$setup" --repo "$repo" --clobber
          } else {
            gh release create "$tag" "$portable" "$setup" --repo "$repo" --title "Release $tag" --notes-file release-notes.md --verify-tag
          }
          if ($LASTEXITCODE -ne 0) { throw "gh release publication failed ($LASTEXITCODE)" }

      - name: Summary
        shell: pwsh
        run: |
          $tag = "${{ steps.version.outputs.tag }}"
          $repo = "${{ github.repository }}"
          @"
          ### Release $tag

          - [免安装绿色版](https://github.com/$repo/releases/download/$tag/DataCube-$tag-win64-portable.zip)
          - [exe 安装程序](https://github.com/$repo/releases/download/$tag/DataCube-$tag-win64-setup.exe)
          "@ >> $env:GITHUB_STEP_SUMMARY
```

- [x] **Step 4: Run the focused release contract test**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.build.CiWorkflowContractTest.releaseWaitsForVerificationAndRetestsBeforePackaging
```

Expected: PASS.

- [x] **Step 5: Commit the gated release workflow**

Run:

```powershell
git add -- .github/workflows/release.yml test/com/datacube/build/CiWorkflowContractTest.java
git diff --cached --check
git commit -m "ci: 发布流程依赖完整验证门禁"
```

Expected: release workflow and the added contract test are the only staged paths.

---

### Task 3: Document and Verify the CI Phase

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-08-04-ci-release-gates.md`

**Interfaces:**
- Consumes: verification and release workflow behavior from Tasks 1 and 2.
- Produces: user-facing CI/release documentation and phase completion evidence.

- [x] **Step 1: Update repository layout and release documentation**

Document both workflows in README:

```markdown
├── .github/workflows/verify.yml  # PR/main：Windows + Linux 测试、Redis 集成、Windows jlink
├── .github/workflows/release.yml # v* tag/手动：验证通过后生成 Windows 发布产物
```

State that PRs and pushes to `main` run the reusable verification workflow, Redis uses only a disposable CI password, and tag/manual releases wait for the same gate before packaging.

- [x] **Step 2: Run the full local regression suite**

Run:

```powershell
Remove-Item Env:DATACUBE_REDIS_HOST,Env:DATACUBE_REDIS_PORT,Env:DATACUBE_REDIS_DB,Env:DATACUBE_REDIS_USERNAME,Env:DATACUBE_REDIS_PASSWORD -ErrorAction SilentlyContinue
.\gradlew.bat clean test
.\gradlew.bat jlink
```

Expected: all ordinary tests pass with only the opt-in Redis test skipped, and jlink succeeds.

- [x] **Step 3: Validate text, secrets, and index state**

Run:

```powershell
git diff --check
rg -n "192\.168\.5\.254|DATACUBE_REDIS_PASSWORD" .github test/com/datacube/build README.md
codegraph sync .
codegraph status
```

Expected: no user Redis host appears; only the disposable `datacube-ci-only` password and environment-variable name appear; diff check succeeds; CodeGraph is up to date.

- [x] **Step 4: Mark the plan complete and commit documentation**

Change all plan task checkboxes to `[x]`, then run:

```powershell
git add -- README.md docs/superpowers/plans/2026-08-04-ci-release-gates.md
git diff --cached --check
git commit -m "docs: 记录 CI 与发布门禁"
```

Expected: final CI-phase commit contains only README and this completed plan.

## Phase Completion Evidence

Record the three commit IDs, focused RED/GREEN observations, full test totals, jlink result, workflow contract results, disposable Redis configuration, and CodeGraph status before starting atomic connection persistence.
