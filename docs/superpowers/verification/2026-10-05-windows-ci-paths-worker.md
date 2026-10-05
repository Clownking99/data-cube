# Windows CI paths repair — 2026-10-05

Status: branch engineering complete, pending root review/main integration and actual remote CI. Baseline c3a41806bf17763f91578dfa65c913cdb14c2c15; product/test source commit d5dd127b09342582b5c7448adf5b9aced018ebff; branch codex/windows-ci-paths-20261005. No push/fetch/tag/PR/release, GUI native execution, database, external network, credentials/original profile/history/.testagent access or additional threads.

## Root cause and minimal repair

Windows permits ordinary 8.3 spelling of a file/directory. Original UpdatePaths.noLinks wrongly treated realpath textual inequality as a link. Local owned GetShortPathName returned a real DA5262~1 alias; actual short java.io.tmpdir reproduced the six CI FX failures (two AppShellShutdownRecovery, four WorkspaceShutdown), plus new alias image rejection. SQL store returns canonical parent identity but these test fixtures used lexical temp paths; canonicalizing fixture roots preserves production registry's FX-only/no-file-I/O contract. No product SQL behavior or close guard changed; no sleeps/repair skips added.

UpdatePaths now reads each existing ancestor's NOFOLLOW BasicFileAttributes, rejecting symbolicLink and isOther. JDK25 WindowsFileAttributes.isOther covers non-symbolic reparse points/junctions/devices; only NoSuchFileException permits a missing staging descendant, other I/O failures propagate. Missing ancestors are still inspected up to root. New actual junction target/ancestor and extraction tests confirm denial before target mutation with sentinel unchanged.

Removing the false-positive check exposed a second real handoff failure: Windows PowerShell5.1 GetFullPath expands short paths and rejects the Java plan's noncanonical spelling. Explicit canonicalExisting validates original ancestor attributes, resolves existing NOFOLLOW realpath, then revalidates canonical ancestors. UpdateApplier canonicalizes app target and both PORTABLE/INSTALLED workspaces before owner/plan publication; UpdateStartup applies the same target identity. Shipped helper canonical/ReparsePoint checks remain unchanged. New two-mode alias regression asserts serialized app/workspace/asset identity, wrong-target denial and once-only acknowledgment. Existing nine helper handoff/recovery scenarios validate the real PowerShell script on mock images, never actual update installation.

## Exact validation

All test phases use offline JDK25.0.1+8, unique synthetic home and real 8.3 temp root, inherited live/Java/JDK variables removed, sequential Gradle. Sources frozen in source-freeze.json. Actual XML summaries/failed names/skip reasons: [test-summary](evidence/windows-ci-paths-20261005-worker/test-summary.json).

| Phase | Actual result | Meaning |
| --- | --- | --- |
| 003-red | 15 total,7 failed,8 passed,0 skips; Gradle exit1 | Original product: actual alias false rejection + exact six FX CI failure sites; real junction denial already passes |
| 004-green attempt | 179 total,10 failed,169 passed,0 skips; exit1 | First repair: helper NON_CANONICAL_PATH exposed; name retained as attempted phase, not a pass |
| 005-green | 180 passed,0 failures/errors/skips; exit0 | All update classes, both FX fixtures, SQL registry and SQL file store safety, three new Windows regressions |
| 006-full | 3941 total,3938 passed,0 failures/errors,3 live skipped; exit0 | Fresh clean test under actual short temp paths; no extra Windows capability skips |
| 007-buildsrc | 8 passed; exit0 | Separate :buildSrc:test --rerun-tasks, all required tasks EXECUTED |
| 008-image | jpackageImage exit0 | Independent new Gradle-only home via JAVA_OPTS; JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS/_JAVA_OPTIONS/headless/live absent |
| 009 audit | rejected | Overbroad file-profile matcher wrongly flagged bundled runtime/bin/api-ms-win-core-profile-l1-1-0.dll; original script/log/result retained |
| 010 audit | passed; driver exit0 | New numbered readback: zero test/fixture/profile/class/cfg injection leaks, Oracle/PG driver discovery only, connectCalls0 |

BuildSrc XML copied in other phases is retained but stale and never counted as fresh tests. Main-test XML copied by 007 is likewise not another full run. Initial runner recorded Gradle exit.json correctly but did not propagate it to shell; current runner fixes exit $code, preserves initial script and actual earlier exit1 records. One nested here-string preparation command failed parsing before any file write/test run; no test or product outcome inferred from it. Earlier failed CI user attachment (3938/28failed/3skip) and complete failure excerpt remain separate from local results. Root records actual remote run IDs/current failures and later CI follow-up; local success does not claim CI green.

Skipped: RedisLiveIntegrationTest standaloneRedisSupportsFiveTypesScanTtlAndLifecycle lacks explicitly provided Redis host/password; SchemaDiffLiveIntegrationTest Oracle and PG disposable-schema smoke lack explicit write gate and provider environment. These existing live tests remain unexecuted. Compiler unchecked notes and JEP493 jlink notice retained. Raw logs can have terminal/CRLF/trailing whitespace; they are frozen exact bytes, never normalized to manufacture a clean log.

| Requirement | Concrete test evidence |
| --- | --- |
| Ordinary short aliases + nonexistent staging retain requested identity | UpdatePathsTest.shortNameAliasAcceptsImageAndMissingStagingWithoutRewritingRequestedIdentity |
| Target/ancestor junction and extraction never write through it | UpdatePathsTest.junctionAtTargetOrAncestorRejectsBeforeExtractionWithoutTouchingTarget |
| Both mode plans use canonical identity; wrong target/duplicate startup rejected | UpdatePathsTest.aliasHandoffPinsCanonicalTargetAndBothWorkspacesWithExactStartupIdentity; UpdateStartupTest.onlyExactTargetVersionAndAttemptCanAcknowledgeStartupOnce |
| SQL identity survives cancelled/pre-tab failure | AppShellShutdownRecoveryTest.cancelledShutdownPreservesExistingFileIdentityAndNewAdmission; preTabFailureRestoresExistingIdentityAndNewFileAdmission |
| Actual workspace retry/cancel/ignore/close keeps publication and resources correct | AppShellWorkspaceShutdownTest four existing scenarios, actual production Alert with controlled collaborators |
| Helper handoff/recovery/authenticated downloads still hold | PortableUpdateHelperTest nine scenarios and installed/malformed ownership; UpdateApplier/UpdateServiceSafety/UpdateDownloads/PortableArchive full package |

## New image and handoff

New actual SHA256: DataCube.exe 6C32DDB83447C5754B5484B7D0C0F501CF48AD515993F96143388D2B4A32074F; cfg E53F0D480A7462920E5D0B6DF5E12BB24BBAA011298317A090CA174FBCC6153D; modules 0860780EE572AFA93BF959235646FF45022904C4C27115A2F9BC4CACDE135ADE. Exe/cfg unchanged, runtime modules newly rebuilt (old423331A... is only historical). No old formal/native raw or manifest modified. process-check.json reports no Gradle Java launcher/daemon remaining. Root can now perform main integration and independent new-profile checks without competing Gradle.

Limits: actual hosted Windows CI after this SHA, real installation/upgrade/reparse races/native launcher visual checks remain unverified. Alias detection regression deliberately fails on Windows if no real 8.3 alias can be obtained; it does not assume/skip capability failure. Windows-specific tests are conditional on OS, matching real NTFS behavior; no general unsupported-platform success claimed. All helpers operate only retained owned UUID roots; no recursive cleanup follows junctions.
