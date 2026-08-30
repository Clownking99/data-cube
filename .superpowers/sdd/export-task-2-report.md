# Task 2 report: value capability policy

## Outcome

Implemented `ResultExportValuePolicy` with an explicit SQL scalar allowlist. `null`, ordinary strings (including literal ellipsis), supported boxed scalar types, `BigInteger`/`BigDecimal`, `UUID`, `URI`, and the explicitly supported `java.time` classes remain as original objects. Finite `Float`/`Double` values are supported; non-finite values, enums, arbitrary `Number` subclasses, arbitrary `TemporalAccessor` implementations, immutable binary/aggregate values, and unknown objects are display-only. Display-only values are formatted through `ResultValueFormatter` and block SQL export through `Assessment.sqlAllowed()`.

## Verification evidence

- RED command: `$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'; ./gradlew test --tests com.datacube.sqleditor.result.ResultExportValuePolicyTest --no-daemon --console=plain`
  - Expected failure: compilation failed because `ResultExportValuePolicy` did not yet exist (8 unresolved symbols).
- GREEN command: same focused command after implementation.
  - `BUILD SUCCESSFUL`; 2 tests completed, 0 failures.
- Full suite command (run once, after focused GREEN): `$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'; ./gradlew test --no-daemon --console=plain`
  - `BUILD SUCCESSFUL`; 130 XML suites, 1163 tests, 0 failures, 0 errors, 3 skipped.

## Files and commit

- `src/com/datacube/sqleditor/result/ResultExportValuePolicy.java`
- `test/com/datacube/sqleditor/result/ResultExportValuePolicyTest.java`
- Implementation commit: `59e4757` (`feat(export): distinguish scalar values from display-only cells`)

## Self-review and concern

The supplied brief's third example expects freezing a 700-character `SerialClob` to produce `ImmutableResultValue`. The current checked-in `ImmutableResultValue.readAndFreeClob` instead returns a bounded `String` preview (`text + "..."`), so that exact example fails before policy logic can run and cannot be solved in Task 2 without either changing Task 1's file or inferring completeness from display text (explicitly forbidden). The committed focused test therefore covers the two executable policy boundary cases; no `ImmutableResultValue` implementation was changed.
