# SQL Script Tab Workflow Final Report

Date: 2026-09-02

Status: DONE

Base: `c9cb80455183474bd6f761bf124494c410efe5b2`

Task commit: this report is included in the single final task commit.

## Delivered behavior

- Added an AppShell-owned, FX-thread-only `SqlFileTabRegistry` with opaque owner tokens, canonical committed bindings, provisional Save As claims, collision selection, rollback, commit, release, and shutdown cleanup.
- Duplicate opens, including store-resolved aliases, select the authoritative existing dirty tab before constructing another editor, isolated session, draft binding, or controller. They also schedule no duplicate recent-file callback.
- Save As retains source path A while claiming target B. An occupied B selects the existing owner without prompting or writing. Cancel, capture/store failure, close, and stale completion roll back B; durable success commits A to B during FX settlement.
- Recent-file admission is captured when open/save work is admitted, so a later clear prevents delayed completion from restoring an entry.
- Ordinary, history, and recovered-draft editors start file-unbound and route first save through Save As. History starts clean; recovery retains its draft binding. History/file workflows do not construct provider, session, metadata, or network resources.
- Draft/workspace persistence formats remain file-path-free. File identity stays in the file document, registry, and recent-file index.
- Editor finalization now attempts controller/registry, draft, results, toolbar, settings/session listeners, and autocomplete cleanup independently, while retaining the original first failure.
- Updated README, workflow design, implementation plan, and verification documentation.

## TDD evidence

Production behavior was added only after the corresponding focused RED:

1. Registry tests first failed compilation with 15 missing `SqlFileTabRegistry` symbols, then passed after the minimal registry implementation.
2. Duplicate-open tests first failed for the missing registry-aware `SqlFileEntry` constructor and `openLoadedSqlFile` overload, then passed after the pre-construction admission gate was added.
3. Save/recent tests first failed for the missing registry-aware controller seam, then passed after claim/rollback/commit and admission-token handling were implemented.
4. Ordinary/history/recovery tests first failed with three missing factory-overload errors, then passed after file-unbound controller installation was integrated.
5. The injected unsubscribe failure test first showed draft cleanup was skipped; per-component best-effort finalization made it pass. A follow-up RED exposed nested aggregation obscuring the original cause; controller detach now preserves that cause.
6. The shutdown contract test first failed because registry shutdown cleanup was absent, then passed after `sqlFileTabs.close()` was wired into AppShell shutdown.

## Verification

- Focused final set: 13 XML files, 222 tests, 0 failures, 0 errors, 0 skipped.
- Fresh full command: `.\gradlew.bat clean test --no-daemon --console=plain`.
- Fresh full result: `BUILD SUCCESSFUL in 1m 42s`; 171 XML files, 1,723 tests, 0 failures, 0 errors, 3 skipped.
- `git diff --check`: passed; only Windows LF-to-CRLF working-copy warnings were emitted.

An earlier fresh run had two failures. One was a brittle source-text assertion that accepted only one equivalent lambda spelling; it was narrowed to the actual lifecycle contract. The other was a one-off failure in the unchanged `SchemaDiffServiceTest.providerAwareCompareReturnsOtherObjectsWhenOneRoutineRequiresManualReview`. That test passed immediately in isolation, and the subsequent fresh full run passed. No Schema Diff production or test behavior was changed.

## Scope and remaining manual checks

No push, merge, tag, installer build, packaged-app launch, database connection, or network operation was performed. Real desktop interaction with `FileChooser` and overwrite `Alert` remains unverified; their decision paths are covered through injected test seams. The unrelated `.superpowers/sdd/progress.md` working-tree modification was intentionally neither changed nor committed.
