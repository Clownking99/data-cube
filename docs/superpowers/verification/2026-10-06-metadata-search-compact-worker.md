# Metadata search compact window worker verification (2026-10-06)

Goal: preserve the existing bound target and explicit read/action lifecycle while making query, results, preview and commands reachable at a real 640 x 480 Stage.

Changed only SchemaMetadataSearchDialog and its existing test. Content now uses its computed wrapped preferred height inside a width-fitting ScrollPane. Results retain their 200 px preferred height (120 px lower bound in the test). Focus changes reveal only content descendants; viewport dimension changes reveal existing focus after FX layout. Manual scrolling does not move focus or force it back. Ctrl+F explicitly reveals query even when query already owns focus. The scene focus listener is removed during FX close cleanup. Enter, target binding, cancellation and admission code are unchanged.

All runs use Run-Main.ps1, JDK 25.0.1+8, cached Gradle offline, fresh UUID synthetic user.home and owned short temporary alias. Relevant live/JVM environment injection is cleared without printing previous values. No database or network used.

Evidence folders:
- red: initial test demanded ScrollPane structure and failed on absent scroll. Not product evidence.
- red-geometry: initial test reached a null-scroll helper after its early geometry checks. Not product evidence.
- red-scene: initial geometry test used real Scene rather than overflowing pane. Stage 640x480, Scene 624x441; SELECT y431..455 was clipped 14px. Early test manually enabled controls; supporting observation only.
- red-real-states: final-red-test.java with original baseline-dialog.java, mock result loaded and selected normally. Four cases fail: light/dark ordinary target results height under 120; light/dark long synthetic target and status SELECT y511..538 beyond Scene bottom441. This is the authoritative original-product regression.
- green-layout: two ordinary cases passed, two long cases failed because original explicit VBox preferred height560 prevented wrapped content from extending scroll range.
- green-computed-height: 29 dialog cases passed after computed height correction.
- green-final-targeted: 103 tests, one resize assertion failed before a true layout pulse; Enter and other cases passed.
- green-pulses: 103 tests passed after awaiting real AnimationTimer layout pulses and dimension-only viewport handling.
- red-same-focus: four cases failed after manual scroll with query already focused; Ctrl+F left query above viewport. same-focus-test.java and same-focus-before-dialog.java bind this regression.
- green-review: final five suites executed, 103 tests, 0 failures, 0 skipped. XML: dialog31, object dialog15, object lifecycle8, shell routing44, service5. Final subsequent source edit is whitespace indentation only.

review.patch holds the limited source/test diff. Raw gradle.log, exit.json, command.json and XML snapshots are kept per run. Full suite, forced buildSrc tests and jpackageImage pending root review. Worker has not committed, merged, pushed or changed any roadmap/handoff ledger. Gradle ownership remains with worker until root requests the next validation or handback.
## Final root-reviewed validation
- green-reviewed-pulses: 5 requested suites, 103 tests, 0 failures/errors/skips. Manual scrolling and external Cancel focus are now asserted after 3 real layout pulses.
- full: test task EXECUTED, 314 suites / 3947 tests / 0 failures / 0 errors / 3 skips, 4m40s. Skips are the explicitly gated Redis standalone and Oracle/PostgreSQL schema deployment live integration cases; no live variables were inherited and skips do not count as passing verification.
- buildsrc-forced: -p buildSrc test --rerun-tasks, 3 tasks EXECUTED; 8 tests / 0 failures/errors/skips.
- image: jpackageImage EXECUTED with jlink/merge tasks, BUILD SUCCESSFUL in35s. No JAVA_TOOL_OPTIONS inherited by packaging. DataCube.exe exists; DataCube.cfg contains no user.home/tmpdir/test flags. Image filename and runtime module-entry checks find no external worker evidence, synthetic fixture or test helper/test class. See image/check.json and module-check.json.
- final.patch and final-source-sha256.json bind the completed implementation; image/check.json repeats matching source hashes. Source SHA256: dialog D42FFC8870ADB6B8E500D6C109FFFE6E20C7DC1DFB0BB4EC3290F730733428FF; test 9E6824F043071D69D8CD63C752F60F623B16AE50A740A95DE26005F0FC51EEF0.

Worker work is complete, with no commit/merge/push. Gradle execution ownership is returned to root. Root may review limited diff/evidence and carry out final repository integration.