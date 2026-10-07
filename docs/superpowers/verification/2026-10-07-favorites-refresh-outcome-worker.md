# SQL favorites refresh outcome worker verification (2026-10-07)

Goal: distinguish repository mutation that returned normally from its subsequent snapshot read failure, preserving explicit local operations, exact UUID/CAS identity and offline-open safety. Production scope is only SqlFavoritesDialog; tests only SqlFavoritesDialogTest. Existing repository interface and existing test runner constructor are sufficient; no new production seam, storage rewrite or layout change.

## Authoritative red

red-new-real-store constructs a shown dialog with an empty synthetic UUID-owned TempDir SqlFavoriteStore through local Repository, saves normally once, then injects a read IOException. A second normal Save button fire produces two actual persisted UUID records: expected1, actual2. red-dialog.java/red-test.java/red.patch bind the failing version. Command, log, XML and exit1 are preserved. This is a physical local persistence regression with synthetic SQL, not a database or native-input test.

## State design and implementation

Completed(outcome,value) exists only after save/delete/recover returned normally, including repository resource close. The following load is caught separately as an Exception; Error is not caught, and InterruptedException restores the worker interrupt signal. A failed read publishes the confirmed operation name, retains committed/deleted/recovered identity and text, clears the old candidate snapshot, freezes editing/writes/offline-open/new/discard, and offers explicit Reload. Both disabled controls and handlers enforce this state. dirty() is false only for this confirmed pending-refresh result, so close/reload never ask to discard committed text.

Reread failure or rejected task submission retains the result and notice. Fresh read clears pending state and selects the actual matching saved/recovered UUID; deleted or externally missing items are not fabricated. External updates at that UUID are shown from the fresh snapshot. Write exceptions keep original edit/retry/discard behavior and do not claim all exceptions mean no partial filesystem change. Pending state references are cleared on close; FxTaskScope suppresses queued/late publication.

## Coverage and diagnostic boundaries

- Real TempDir new-save case checks one persisted item, exact text/UUID and unchanged bytes after repeated click.
- Real TempDir protected recovery case corrupts only its owned synthetic primary, preserves exact original/backup bytes and first recovered UUID bytes across duplicate handler/reload failures, and opens that recovered item only after explicit fresh load.
- Mock matrix covers new/edit/delete/recover normal write + failed refresh, attempted direct handlers, failed reread, explicit recovery, fresh external edit and missing ID, rejected reread, completed close without discard, true write failures with explicit retry and dirty-close refusal, and a thrown post-write partial-mutation outcome reconciled only through explicit read.
- Interrupted read test observes the actual owned executor.afterExecute worker signal; it does not infer thread interruption from UI color/message.
- Actual owner Stage close is exercised while post-write load blocks. Ordinary Cancel during busy is refused. The owner test uses the exact production ownership pattern try(view){dialog.showAndWait()}, waits for that wrapper exit, releases and joins the captured actual post-write worker Thread, then uses an FX barrier before asserting no late successful/failed publication, no replay and empty text. An unrelated virtual-thread noop is not used as a completion barrier.

Initial direct-show owner fixture tests (green-failure-owner-protected and red-owner-pulses) failed because direct dialog.show() omitted production try-with-resources/showAndWait ownership. Their raw failures and test snapshots are retained, and are not claimed as a production owner defect. A temporary showing listener made direct-show diagnostics pass (green-owner-hide), but was removed after root identified production ownership. green-owner-production-wrapper proves existing production cleanup without that listener. Final branch adds no owner listener.

## Runs before final root approval

- red-new-real-store:1 failure, actual duplicate persisted UUID.
- green-new-real-store:10 dialog cases pass.
- green-outcome-matrix:20 dialog cases pass.
- green-failure-owner-protected:28 total,26 pass,2 direct-show owner fixture failures.
- red-owner-pulses:2 direct-show fixture failures retained after three FX pulses.
- green-owner-hide:28 pass with temporary diagnostic listener, not final implementation.
- green-owner-production-wrapper:28 pass with listener removed and true production ownership.
- green-targeted-review:6 suites61 total,0 failures/errors/skips;29 Dialog,1 Tabs,8 Store,5 FxTaskScope,17 SqlDraftDirectory,1 SqlTabFileLifecycle. This version precedes the worker-thread join correction.
- green-targeted-task-join:6 suites61 pass,0 failures/errors/skips with actual task join. Root independently reviewed the final source and XML. final.patch/final-source-sha256.json/final-dialog.java/final-test.java freeze this approved version before full-final clean test; source remains unchanged during full/buildSrc/image.

Runner copied from current favorites coordination uses JDK25.0.1+8, cached Gradle offline, new synthetic UUID user.home/owned8.3 temp and cleared live/JVM injection without printing prior values. Only XML from actually executed test tasks is copied, no binary cache; image copies no old test XML. No user configuration/credentials/history/business file, forbidden area, system clipboard, database, network or external communication is accessed. Worker will not commit/merge/push.
## Execution handoff
full-final was interrupted before an exit receipt; its unfinished log and partial XML remain non-passing evidence. The development execution channel disappeared, exact cause unknown. Root confirmed no remaining Java processes and resumed full/buildSrc/image without source changes. Final source remains the approved GPT-6.1-sol implementation; root owns remaining verification and delivery.


## Final branch verification, completed by root

full-root-resumed: 314 suites,3977 total,3974 passed,0 failures/errors,3 explicit live skips (Redis standalone,Oracle and PostgreSQL SchemaDiff). buildsrc-root-final: actual root :buildSrc:test,8 passed,0 skipped. image-root-final: actual jpackageImage,exit0; no stale XML copied. Full/buildSrc/image use unchanged approved source, confirmed by root Audit-Branch-Tests against final-source-sha256.json.

Source commit3b34183ac75b017b1ffc3475a1941c5c5452d799. Root branch-image-audit finds no test/probe/profile/fixture or injected JVM-option leak; packaged driver discovery has0 connect calls. This is engineering/synthetic FX evidence, not native input, true DB or install/signature acceptance. full-final remains interrupted/unknown, never counted as passed.
