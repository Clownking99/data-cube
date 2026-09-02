# SQL Script Files Final Fix Report

Date: 2026-09-02

Status: DONE

Base: `d74fec0eef0fcb25ac6634794f66cdf231edc7d6`

Task commit: this report is included in the separate final-fix commit; the SHA is
recorded in the handoff.

## Findings resolved

- `SqlScriptFileStore.load` now hashes the exact byte array returned by its reader
  and safely compares it with the captured target SHA-256 before UTF-8 decoding.
  A missing fingerprint or unavailable SHA-256 fails closed as `CHANGED`.
- The existing post-read `matches(target)` path/fingerprint recheck remains in
  place, so both returned-byte identity and the restored live path must agree with
  the captured target.
- The SQL-history Javadoc now states the implemented behavior: history opens in
  an isolated empty offline session, while the saved connection name is only used
  for the tab title.

## TDD evidence

The deterministic store reader seam returned valid UTF-8 `select 2;` bytes while
the captured and rechecked path remained the same `select 1;` file. The contents
have equal length, so metadata-only validation cannot distinguish them.

- RED command: `.\gradlew.bat test --tests "com.datacube.sqleditor.SqlScriptFileStoreTest.rejectsAbaReaderBytesThatDifferFromTheCapturedTarget" --no-daemon --console=plain`
- RED result: exit 1; 1 test completed, 1 failed with
  `AssertionFailedError` at `SqlScriptFileStoreTest.java:143`, because the old
  implementation returned `Loaded` instead of throwing `CHANGED`.
- GREEN command: `.\gradlew.bat test --tests "com.datacube.io.BoundedRegularFileReaderTest" --tests "com.datacube.sqleditor.SqlScriptFileStoreTest.rejectsAbaReaderBytesThatDifferFromTheCapturedTarget" --no-daemon --console=plain`
- GREEN result: exit 0; `BUILD SUCCESSFUL in 8s`.

## Verification

- Complete reader/store classes: `.\gradlew.bat test --tests "com.datacube.io.BoundedRegularFileReaderTest" --tests "com.datacube.sqleditor.SqlScriptFileStoreTest" --no-daemon --console=plain` — exit 0, `BUILD SUCCESSFUL in 7s`.
- Relevant history/offline UI tests: `AppShellTest`, `SqlTabFileLifecycleTest`,
  `SqlScriptFileEntryTest`, `SqlDraftRecoveryTabsTest`,
  `SqlWorkspaceRecoveryTabsTest`, and `SqlEditorDraftRecoveryTest` — exit 0,
  `BUILD SUCCESSFUL in 20s`.
- Fresh full command: `.\gradlew.bat clean test --no-daemon --console=plain`.
- Fresh full result: exit 0, `BUILD SUCCESSFUL in 1m 40s`.
- Fresh XML aggregation: 171 suites, 1,724 tests, 1,721 passed, 0 failures,
  0 errors, and 3 skipped. The skips are the existing live Redis and Schema Diff
  integration tests.
- `git diff --check`: passed before report creation with only Windows LF-to-CRLF
  working-copy notices; rerun after the report is recorded.

## Scope and concerns

No push, merge, tag, database connection, or network operation was performed.
The unrelated pre-existing `.superpowers/sdd/progress.md` modification was not
edited or staged. The full compile retained the repository's existing unchecked
operation note for `SqlEditorResultFilterContractTest`; it was not introduced by
this fix.
