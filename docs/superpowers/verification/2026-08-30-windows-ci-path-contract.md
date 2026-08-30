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

Focused class command: exit code 0 (`BUILD SUCCESSFUL`).

Verified short-path command, after confirming `C:\Users\hetia\AppData\Local\Temp\DATACU~1.KMP` exists:

```powershell
$env:JAVA_TOOL_OPTIONS = "$ciPreviousJavaOptions -Djava.io.tmpdir=C:\Users\hetia\AppData\Local\Temp\DATACU~1.KMP -Djava.awt.headless=false".Trim()
.\gradlew.bat test --tests 'com.datacube.export.SafeResultFilePublisherTest' --rerun-tasks --no-daemon --console=plain
```

Result: exit code 0 (`BUILD SUCCESSFUL`).

Combination command (`com.datacube.export.*` plus `com.datacube.fx.SqlResultExportCoordinatorTest`): exit code 0 (`BUILD SUCCESSFUL`).

Full command (`.\gradlew.bat clean test --no-daemon --console=plain`, with temporary `-Djava.awt.headless=false`): exit code 0. XML totals read from `build/test-results/test` after this run:

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
