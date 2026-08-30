# Windows CI Path Contract Verification

Date: 2026-08-30
Worktree: `D:/Projects/朝花夕拾/.worktrees/product-continuity`
Branch: `codex/product-continuity`

## RED

The focused test was changed only to use `directory.resolve(".").resolve("result.csv")`, while retaining the old `assertEquals(target, published)` assertion. Command:

```powershell
$ciPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$ciPreviousJavaOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --tests 'com.datacube.export.SafeResultFilePublisherTest.successfulPublishAndCancellationHaveDifferentTerminalEffects' --rerun-tasks --no-daemon --console=plain
    $ciTestExit = $LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS = $ciPreviousJavaOptions }
```

Result: exit code 1; one test completed and one failed at `SafeResultFilePublisherTest.java:80` with `AssertionFailedError`. There was no compilation or write failure.

## GREEN

The assertion now checks `directory.toRealPath().resolve("result.csv")` and `Files.isSameFile(target, published)`, while retaining the existing terminal-state, content, and cancellation assertions.

Focused class command (exit code 0, `BUILD SUCCESSFUL`):

```powershell
$ciPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$ciPreviousJavaOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --tests 'com.datacube.export.SafeResultFilePublisherTest' --rerun-tasks --no-daemon --console=plain
    $ciTestExit = $LASTEXITCODE
    if ($ciTestExit -ne 0) { throw "Focused class test failed with exit code $ciTestExit" }
} finally {
    $env:JAVA_TOOL_OPTIONS = $ciPreviousJavaOptions
}
```

The original root experiment mapped `C:/Users/hetia/AppData/Local/Temp/datacube-ci-path-probe-hejdab0m.kmp` to `DATACU~1.KMP`; it is historical evidence only, not a path to reuse. The independently runnable recipe below creates an exclusive directory and obtains its actual alias. It never assumes a `~1` suffix.

```powershell
$ciPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
$ciScratch = [System.IO.Directory]::CreateTempSubdirectory('datacube-ci-path-probe-')
$ciOptionsRestored = $false
try {
    $ciLongTemp = $ciScratch.FullName
    $ciFso = New-Object -ComObject Scripting.FileSystemObject
    $ciShortTemp = $ciFso.GetFolder($ciLongTemp).ShortPath
    if ([string]::IsNullOrWhiteSpace($ciShortTemp) -or $ciShortTemp -eq $ciLongTemp) {
        throw 'The filesystem did not provide a distinct 8.3 short path for the fresh test directory.'
    }
    $ciPathProbe = Join-Path $ciLongTemp 'CiPathProbe.java'
    @'
import java.nio.file.Path;
class CiPathProbe {
    public static void main(String[] args) throws Exception {
        if (!Path.of(args[0]).toRealPath().equals(Path.of(args[1]).toRealPath())) System.exit(1);
    }
}
'@ | Set-Content -LiteralPath $ciPathProbe -Encoding ascii
    & java $ciPathProbe $ciLongTemp $ciShortTemp
    if ($LASTEXITCODE -ne 0) { throw "Short path does not resolve to the created directory: $ciShortTemp" }

    $env:JAVA_TOOL_OPTIONS = "$ciPreviousJavaOptions -Djava.io.tmpdir=`"$ciShortTemp`" -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --tests 'com.datacube.export.SafeResultFilePublisherTest' --rerun-tasks --no-daemon --console=plain
    $ciTestExit = $LASTEXITCODE
    if ($ciTestExit -ne 0) { throw "Short-path class test failed with exit code $ciTestExit" }
} finally {
    $env:JAVA_TOOL_OPTIONS = $ciPreviousJavaOptions
    $ciOptionsRestored = ($env:JAVA_TOOL_OPTIONS -eq $ciPreviousJavaOptions)
}
if (-not $ciOptionsRestored) { throw 'JAVA_TOOL_OPTIONS was not restored.' }
```

In that recipe, `GetFolder($ciLongTemp).ShortPath` is the actual COM alias. The temporary Java probe compares the two `Path.toRealPath()` results; `GetFolder($ciShortTemp).Path` is deliberately not used as a long-path check because it retains the short spelling. The fresh directory is intentionally left in place and contains only this recipe's probe and test artifacts; the recipe performs no automatic deletion.

The earlier Resolve-Path diagnostic was rejected before Gradle ran because PowerShell retains the short spelling rather than establishing filesystem identity. The corrected Java-real-path/no-cleanup recipe above was executed: `SafeResultFilePublisherTest` passed with exit code 0 (`BUILD SUCCESSFUL`, 15 seconds), and `JAVA_TOOL_OPTIONS` restoration was confirmed.

Export/coordinator combination command (exit code 0, `BUILD SUCCESSFUL`):

```powershell
$ciPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$ciPreviousJavaOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --tests 'com.datacube.export.*' --tests 'com.datacube.fx.SqlResultExportCoordinatorTest' --rerun-tasks --no-daemon --console=plain
    $ciTestExit = $LASTEXITCODE
    if ($ciTestExit -ne 0) { throw "Export/coordinator tests failed with exit code $ciTestExit" }
} finally {
    $env:JAVA_TOOL_OPTIONS = $ciPreviousJavaOptions
}
```

Full command (exit code 0, `BUILD SUCCESSFUL`; XML totals below):

```powershell
$ciPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$ciPreviousJavaOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat clean test --no-daemon --console=plain
    $ciTestExit = $LASTEXITCODE
    if ($ciTestExit -ne 0) { throw "Full test suite failed with exit code $ciTestExit" }
} finally {
    $env:JAVA_TOOL_OPTIONS = $ciPreviousJavaOptions
}
```

XML totals read from `build/test-results/test` after the full run:

| suites | tests | passed | failures | errors | skipped |
|---:|---:|---:|---:|---:|---:|
| 138 | 1211 | 1208 | 0 | 0 | 3 |

The three skips are existing environment-dependent/live tests; no new skip or dependency was added.

## Scope review

- Modified only `test/com/datacube/export/SafeResultFilePublisherTest.java` in the requested test method.
- Added this verification document.
- No production source, `.testagent/`, plan, progress, or unrelated test files were changed by this task.
- `git diff --check` completed without whitespace errors.
- `JAVA_TOOL_OPTIONS` was restored after each command.

## Root verification and remaining gates

- Independent task review approved `f3f4abc..40d51c6` after correcting the reproducibility documentation. Whole-increment review approved `151a64a..ab3a3aa` for local merge, with no Critical, Important, Minor or plan findings. These are code/document review results, not remote CI results.
- Root checked the exact `f3f4abc..da3dee9` test diff: the fixture contains `.`; the expected path is independently derived from the real temporary directory; file identity, terminal-state and file-content assertions are retained. Production source is unchanged.
- Root independently summed the resulting XML: 138 suites, 1211 tests, 1208 passed, 0 failures/errors, 3 skipped.
- Skips: `RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle`, `SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas`, and `SchemaDiffLiveIntegrationTest.postgresqlSafeDeploymentConvergesInDisposableSchemas`. No real database was configured for these tests.
- Root ran `.\gradlew.bat jlink --no-daemon --console=plain`: exit 0, 35 seconds, 6 tasks executed. The toolchain emitted its JEP 493/module-location notice; the build completed successfully. This is linked-runtime validation, not an installer or desktop acceptance pass.
- Remote Verify still refers to the failing original `151a64a` run until a separately authorized push creates a new run. No remote pass is claimed.
- Full product rollout, packaged install/upgrade, outstanding export interactions, and real Excel scrolling/row-height checks remain under P0.2 of the roadmap. SQL draft/workspace features are planned, not implemented by this repair.
