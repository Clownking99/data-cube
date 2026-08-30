# Task 2 report: value capability policy

## Outcome

Implemented `ResultExportValuePolicy` with an explicit SQL scalar allowlist. `null`, ordinary strings (including literal ellipsis), supported boxed scalar types, `BigInteger`/`BigDecimal`, `UUID`, `URI`, and the explicitly supported `java.time` classes remain as original objects. Finite `Float`/`Double` values are supported; non-finite values, enums, arbitrary `Number` subclasses, arbitrary `TemporalAccessor` implementations, immutable binary/aggregate values, and unknown objects are display-only. Display-only values are formatted through `ResultValueFormatter` and block SQL export through `Assessment.sqlAllowed()`.

## Verification evidence

- RED command: `$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'; ./gradlew test --tests com.datacube.sqleditor.result.ResultExportValuePolicyTest --no-daemon --console=plain`
  - Expected failure: compilation failed because `ResultExportValuePolicy` did not yet exist (8 unresolved symbols).
- GREEN command: same focused command after implementation.
  - `BUILD SUCCESSFUL`; `build/test-results/test/TEST-com.datacube.sqleditor.result.ResultExportValuePolicyTest.xml` reports 2 tests, 0 failures, 0 errors, 0 skipped.
- Full suite command (run once, after focused GREEN): `$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'; ./gradlew test --no-daemon --console=plain`
  - `BUILD SUCCESSFUL`; 130 XML suites, 1163 tests, 0 failures, 0 errors, 3 skipped.

## Files and commit

- `src/com/datacube/sqleditor/result/ResultExportValuePolicy.java`
- `test/com/datacube/sqleditor/result/ResultExportValuePolicyTest.java`
- Implementation commit: `59e4757` (`feat(export): distinguish scalar values from display-only cells`)
- Report commit: `1b2b609` (`docs(export): report value policy verification`)

## Self-review and concern

The supplied brief's third example expects freezing a 700-character `SerialClob` to produce `ImmutableResultValue`. The current checked-in `ImmutableResultValue.readAndFreeClob` instead returns a bounded `String` preview (`text + "..."`), so that exact example fails before policy logic can run and cannot be solved in Task 2 without either changing Task 1's file or inferring completeness from display text (explicitly forbidden). The committed focused test therefore covers the two executable policy boundary cases; no `ImmutableResultValue` implementation was changed.

## Approved CLOB prerequisite repair

### Root cause and implementation

`ImmutableResultValue.readAndFreeClob` bypassed the shared bounded immutable text representation: it called `Clob.length()` and `getSubString()`, then returned a truncated ordinary `String` with an ellipsis. That discarded the incompleteness/type metadata before `QueryResult`, `ResultExportSnapshot`, and `ResultExportValuePolicy` could assess it.

The repair changes only that CLOB read path to reuse `readBoundedText(clob.getCharacterStream())` inside the existing `readAndCleanup` wrapper. Complete CLOB text of 500 characters or fewer remains a scalar `String`; longer CLOB text retains the existing bounded `ImmutableResultValue` text preview, full-stream SHA-256 fingerprint, and total length. The reader closes through `readBoundedText`, and `Clob.free()` still runs with the established primary-failure/suppression semantics. Export never re-reads JDBC state.

### Tests and evidence

- RED command: `$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'; ./gradlew test --tests com.datacube.sqleditor.result.ResultExportValuePolicyTest --tests com.datacube.spi.model.QueryResultMetadataTest --no-daemon --console=plain`
  - Before the production change: 24 tests completed, 3 failed. The failures were the end-to-end 700-character CLOB `QueryResult -> ResultExportSnapshot -> ResultExportValuePolicy` assertion (still a scalar `String`), the CLOB streaming/boundary assertion (old path called `length`), and the cleanup regression whose CLOB read boundary changed from `getSubString` to `getCharacterStream`.
- GREEN command: same focused command after the production change.
  - `BUILD SUCCESSFUL`; XML results: `ResultExportValuePolicyTest` 3 tests, 0 failures/errors/skips; `QueryResultMetadataTest` 21 tests, 0 failures/errors/skips.
- Full command (run once after focused GREEN): `$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'; ./gradlew test --no-daemon --console=plain`
  - `BUILD SUCCESSFUL`; 130 XML suites, 1165 tests, 0 failures, 0 errors, 3 skipped.

### Changed files and self-review

- `src/com/datacube/spi/model/ImmutableResultValue.java`
- `test/com/datacube/spi/model/QueryResultMetadataTest.java`
- `test/com/datacube/sqleditor/result/ResultExportValuePolicyTest.java`

The policy test restores the mandatory ordinary-text CLOB preview case through the full query/snapshot path and asserts it is display-only and blocks SQL. It also asserts finite `Float` and `Double` values remain complete scalars. The metadata test covers a 501-character streaming CLOB, rejects use of the eager locator APIs, verifies `free()`, preserves the reader-failure cleanup behavior, and checks the 500-character complete boundary. No TSV, selection/copy, or writer behavior changed. No concern remains from this repair.
