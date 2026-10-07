# Schema object search compact window worker verification (2026-10-07)

Goal: make the existing SchemaObjectSearchDialog name snapshot usable at a real 640x480 and 480x480 Stage, preserving bound Schema, explicit reload, local filtering, injected clipboard and Enter confirmation semantics.

## Confirmed defect and scope boundary

Eight combinations use actual shown JavaFX Dialog/Stage, width640 or480 and height480, light/dark themes, ordinary or long synthetic connection name, successful mock snapshot and normally selected candidate. COPY uses an injected fake CopyResult function, never system clipboard. Field-search entry is installed normally. Layout settles over three AnimationTimer pulses; this is JavaFX geometry/event verification, not native input automation.

red-stage failed eight cases on an initial120px height threshold; that threshold alone is not a product defect. The authoritative red-visible removes this threshold and compares a rendered selected ListCell with the actual list bounds. Four 480px cases fail: row y147.33..172 versus list y146..168, clipping the bottom4px. All four640px cases pass actual reachability, although the list is only30px high. The 480px list is22px. Target name is a read-only two-row TextArea and remains reachable; no additional target/preview/hint/copy/field-action clipping was proved in the ordinary state. Original source is red-dialog.java; red-visible-test.java and red-visible.patch bind the definitive regression. Raw logs/XML/command/exit are preserved separately for each run.

## Minimal implementation

Only SchemaObjectSearchDialog production code changed: width-fitting ScrollPane with computed content height, preserving complete candidate rows and existing200px preferred result area. Scene focus changes reveal only own content descendants; listener is removed during FX close cleanup. Viewport dimensions and content-height changes reveal existing focus after layout, including fake-copy feedback becoming managed or local filtering clearing it. Ordinary scroll value changes do not reveal focus. Ctrl+F explicitly reveals query even if it already owns focus. Existing onShown reload, query Down/Enter, list Enter, selection binding, admission, cancellation, closeMetadata and disposal remain intact. No shared helper extraction or metadata-dialog change.

The existing dialog test gained the eight real-Stage cases and two theme cases for copy feedback/filter clearing, shrinking existing field-entry focus, manual scrolling across pulses, outside Confirm/Cancel focus across pulses, same-query-focus Ctrl+F across pulses, Tab, Down, fake COPY/field-entry counts and Enter returning the exact same TableRef without another read. Both Confirm/Cancel bounds remain in the Stage. Typing/filtering and focus navigation keep exactly one snapshot job/load.

SchemaObjectSearchLifecycleTest only changes its pre-show list lookup helper: when content is a ScrollPane, query scroll.getContent() directly because the skin has not attached its descendants before show. All lifecycle assertions and ordering are unchanged.

## Run record before full validation

- red-stage: eight threshold failures, not used as defect evidence.
- red-visible:8 cases,4 pass at640,4 fail at480 on actual selected-row clipping.
- green-layout: object dialog suite23 tests,0 failure/error/skip.
- green-dynamic: object dialog suite25 tests,0 failure/error/skip.
- green-targeted:113 tests,3 failures in pre-show test lookup only; preserved and not counted as passing.
- green-targeted-lifecycle: five requested suites executed,113 tests,0 failures/errors/skips. Suite counts: object dialog25, object lifecycle8, metadata dialog31, shell routing44, metadata service5.

Final.patch and final-source-sha256.json freeze the reviewed implementation before full/buildSrc/image. Full validation currently runs; source will not change during validation.

Runner is copied from this round's root coordination version. It uses JDK25.0.1+8, cached Gradle offline, new UUID synthetic profile and owned short temporary alias, clears live/JVM injection without printing previous values, copies only actual executed test-task XML and no binary cache. Image runs copy no test XML and inherit no test JVM flags. No database, network, user configuration/history, real system clipboard or forbidden area is accessed. No commit/merge/push is performed by worker.
## First full failure and reviewed fixture correction

First full (directory full) executed3957 tests and failed4, with3 explicitly gated live skips. The four failures are the preexisting Copy/Kind narrow-theme cases that resize an unshown DialogPane to480x548 and compare coordinates to548. red-unshown-dimensions retains the diagnostic source snapshots and raw XML: after layout the actual pane is480x570, Scene0x0, window not showing, and Confirm y533..560 is within the real pane. The548 assertion did not describe a shown viewport. No production change was made in response.

Root approved rewriting exactly these four parameter cases as real shown Stage480x548 checks, waiting three JavaFX layout pulses, comparing exterior Confirm/Cancel to actual Scene bounds and checking interior content through focus/manual scroll reachability. Copy failure/retry feedback, configured target/preview row heights, full201-object cap warning wrap and snapshot/copy counts remain asserted. Existing fake ConnectionTreeClipboard writer is still used; no system clipboard operation.

The diagnostic snapshots are red-unshown-SchemaObjectCopyTest.java and red-unshown-SchemaObjectKindFilterTest.java; first-full originals are separately preserved. final-reviewed.patch and final-reviewed-source-sha256.json bind the five reviewed source/test files. First final.patch/source-sha256 files are unchanged and still bind the first full.

New green-targeted-seven executes7 suites,160 tests,0 failures/errors/skips: Object25, Lifecycle8, Metadata31, Routing44, Service5, Copy25, Kind22. full-reviewed is currently running with frozen source. Subsequent forced buildSrc and image will run only after it finishes successfully.
## Final validation and handback

- Final targeted directory green-targeted-seven:7 suites /160 tests /0 failures /0 errors /0 skips.
- full-reviewed: test EXECUTED, BUILD SUCCESSFUL in5m17s;314 suites /3957 total /3954 passed /0 failures /0 errors /3 skips. skips.json preserves the explicit Redis host/password gate and Oracle/PostgreSQL Schema Diff write/provider gates. Skipped live cases are unverified, not passing evidence.
- buildsrc-forced: root :buildSrc:test --rerun-tasks, four tasks EXECUTED;1 suite /8 tests /0 failures/errors/skips. Only buildSrc XML copied.
- image: jpackageImage plus jar/merge/jlink EXECUTED, BUILD SUCCESSFUL in47s. No test JAVA_TOOL_OPTIONS inherited, no test XML copied. image/check.json confirms launcher exists, no injected user.home/tmpdir/test flag in DataCube.cfg, required SchemaObjectSearchDialog.class exists in runtime modules, no test classes or worker evidence/profile entries, and no external fixture filenames. Launcher/runtime SHA256 and matching five final-reviewed source SHA256 are recorded.
- The worker evidence directory contains XML/log/command/exit/text snapshots and hashes only; no Gradle binary caches or image binaries are copied into evidence. First full/diagnostic failures remain unchanged. .gitattributes uses '* -text' and manifest.json binds every archived file except itself after completion.

Source remains frozen at final-reviewed-source-sha256.json. Production SHA256 B98803EF30AE0A6D217C87C5D51743BEE161F690BCA7100D211CC849E367BB86; final-reviewed.patch is the final five-file source/test diff. No commit, merge, push, release or external contact was performed.

All worker work is complete. No Gradle process remains active from this worker. Gradle execution ownership is returned to root for independent byte audit and integration.