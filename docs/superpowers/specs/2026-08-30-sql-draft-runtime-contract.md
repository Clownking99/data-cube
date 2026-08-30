# SQL draft runtime integration contract

Companion to [P1 design](2026-08-30-sql-draft-recovery-design.md). This is a design contract, not implementation or test evidence. Routine design decisions are delegated by the user; no additional approval gate is introduced.

## Responsibilities

- `SqlDraftSaveState`: owner-confined timing and publication tickets; no text, executor, UI or disk operations. The executable state plan is separate.
- Serialized writer: at most one running write plus one pending immutable snapshot per open ID; newer pending snapshots supersede older ones and settle superseded futures immediately. Do not accumulate full SQL or callbacks for every keystroke. It owns ordering, not UI state or connection resolution.
- Application coordinator: owns handles and local protection mode, reads editor snapshots on FX, submits only immutable values, dispatches status on FX, and exposes background-completing flush futures. It opens storage in the background and releases it after all editor guards and the writer drain.
- Editor bridge: installs subscriptions after initial text/schema assignment; captures exact raw text/schema and stable identity, and unregisters on construction abort or successful finalization. A rejected close does not unregister it.

## Queue ordering

Only one drain runnable may be scheduled/running. On dequeue, remove that save from the pending-ID map before executing it, permitting exactly one later snapshot of the same ID to wait behind it. Coalescing removes the older queued write and appends the newest at the current tail; it must not move a post-barrier snapshot into a pre-barrier queue slot.

Explicit clear cancels all pending save jobs before appending its storage action. Delete cancels pending jobs for that ID only. Any write already executing finishes before the action. New qualifying edits can append new saves only behind the action. Both discarded queue jobs and coalesced jobs settle their futures with a typed cancellation/supersession result; callers must not mistake either for successful publication.

Disable pauses admission in the coordinator immediately, cancels pending saves, then appends strict `store.setEnabled(false)`. Successful completion confirms permanent disable; failure retains a paused current session. Re-enable is a distinct explicit barrier; only a successful `store.setEnabled(true)` and usable snapshot permit fresh capture of open editors. Do not use a checkbox listener that first sets enabled=true and rolls it back later.

Ordinary save failures settle that save and do not automatically requeue it. Structural loss (root/lock identity, scan limit, CLEANUP, closed storage) pauses the coordinator and cancels pending saves before another write can begin; this classification belongs on the writer-result path, not only a delayed FX callback. Consequently, rapid input cannot create more temporary files while the UI thread is busy. An unsafe result remains relevant even when its display ticket is stale.

An executor rejection must settle every already accepted queued future and make the worker unavailable; never leave a close future pending indefinitely. The writer does not silently replace a dead executor or steal a lock. Shutdown rejects new jobs, drains already accepted jobs, then allows the background owner to close the store. Failure to schedule a drain is propagated to the owner, which still owns final resource cleanup.

## Snapshot and closure semantics

- Input events only mark timing/eligibility; snapshot strings are read on FX at due/force time, not captured into an unbounded per-keystroke queue.
- A new empty editor has neither a checkpoint nor a write to recover; if text is cleared before its first snapshot, cancel pending timing. After any snapshot has been offered, empty text is qualifying because an older nonempty write may already be executing.
- Clearing stored drafts resets per-handle eligibility and generation immediately. An unedited open editor must not recreate its old text on force flush/close. A subsequent user edit or explicit re-enable can qualify it again.
- A flush of a captured revision completes in the background after its publication (or cancellation/failure), regardless of whether FX has processed the status callback. A closing worker may await it; FX may not block on it.
- The existing SQL/transaction close decision remains authoritative. Interactive transaction cancellation returns REJECTED without destroying subscriptions. Draft failure must gate destructive cleanup, not be inserted into a best-effort sequence that proceeds to dispose after failure.
- Review refinement: complete draft protection before opening transaction/running-query close-decision dialogs. A draft refusal leaves both text and completed operation callbacks available. In mandatory close, suppress session-operation callbacks only after the draft gate succeeds; suppressing during asynchronous persistence would permanently drop a result arriving before a later refusal. Existing commit/rollback choices and mandatory non-interactive rollback remain unchanged.
- Mandatory editor close currently rolls back without a transaction dialog. Preserve that contract and its tests; handle unsaved-draft refusal before irreversible resource disposal. Do not report FAILED_PARTIAL for a failure that occurred before any destructive step merely to force application quarantine.

## Verified current integration seams (read-only, 2026-08-30)

`AppShell.openSqlTab` installs both interactive and mandatory guards through `ManagedTabFactorySequence`; construction cleanup currently binds `pane::closeResources`. Its `shutdownRemaining` runs only after managed tabs, providing the final drain/release position. Existing SQL-history restore matches by connection name and changes global selection, so it cannot serve as the draft restore implementation.

`SqlEditorPane` normal construction currently creates a bound JDBC session before `build`; initial schema is trimmed. Normal `currentConn` falls back to the global candidate. Draft restoration therefore needs an explicit construction mode and exact raw schema path, not a null-connection shortcut. `captureClosePlan` is FX-only but trims schema for history, so draft capture must be independent. `runDestructiveClose` is best-effort across history and resource cleanup; do not put recoverable draft-save failure inside that method.

These observations are code-read evidence, not proof of a functioning recovery UI. The actual queue, coordinator, restoration admission and desktop acceptance each still require executable plans, TDD, task review and a final integrated review before merging P1 to main.

## Restoration admission detail

Before the first explicit database action, resolve the saved ID from `ConnectionManager.config` again and compare its type. That method is a nullable in-memory lookup; a deleted connection must not become a `requireConfig`/provider call or a global-selection fallback. Resolving only when the recovery dialog opens leaves a deletion/type-change window before execution. Use the current matching immutable config at admission, then preserve the editor's existing pinned-session semantics after admission; do not silently retarget an already admitted editor when another connection is selected globally.

This distinction also separates display intent from resource ownership. A matching saved target may be shown while JDBC and metadata access remain inactive. Missing/unmatched targets require an explicit user selection, and showing that selection UI must use the in-memory connection snapshot rather than reload credentials/history files. Restored text assignment, including member-completion triggers such as `alias.`, must remain inside the metadata gate until explicit admission.

Existing `SqlEditorSessionContractTest` checks source-text relationships (including both session-ownership call sites and mandatory rollback/no-dialog structure). Preserve those contracts, but do not cite them as runtime evidence of zero database calls. Draft restoration acceptance needs actual provider/session/metadata call counters with isolated synthetic configs, plus a real FX text assignment that can trigger completion. Existing `SqlEditorConnectionAdmissionTest` covers the underlying pin/close gate, not the new restoration path by itself.

## Initialization and unavailable storage

Opening/initializing the local directory is background work. Before it succeeds, UI must say that draft protection is initializing and no save is confirmed. Editing remains possible and the latest eligible text is captured after successful initialization; do not queue a full SQL snapshot for every initialization-time input event.

Closing a modified editor before initialization completes, or while protection is unavailable, must not report a successful draft flush. Preserve the editor for retry/cancel, or require the explicit discard-latest path before destructive cleanup. Deliberately disabled protection is a distinct user choice and can allow normal close without promising a saved draft. Never translate a failed disable preference write into permanent disabled success merely to bypass the close guard.

## Runtime-to-editor integration decisions

The coordinator executable plan uses one application UI timer calling `pulse()`, a separate writer executor, and UI-owned handles. The runtime does not create JavaFX controls or own an editor task scope. Ordinary failed saves allow explicit retry; structural unavailability is fail-closed for this application instance and requires repair plus restart. Do not present the ordinary retry button as repairing a lost directory identity/lock.

`lastManagementResult()` provides the last applied startup/management snapshot and success flag. A partial startup prune must preserve actual survivor visibility and a warning; it is not an empty-list or successful-cleanup result. Restored-tab creation is temporarily rejected while management is pending, so the manager must disable restore until refresh/clear/delete settles. New, non-restored editors can still open. If a refresh observes an externally persisted disabled preference, do not leave the UI falsely enabled or automatically turn protection back on.

The editor bridge must subscribe after initial SQL/schema assignment. A restored checkpoint starts clean; a new/history-loaded editor with text qualifies for autosave. Capture connection identity from the pinned target or explicit recovery intent, never silently change a recovered draft's intended connection based on global selection. Subscribe to actual connection admission/explicit selection as well as SQL/schema changes so the saved identity follows a user-authorized binding.

During a close attempt, freeze editor text/schema and formatting/comment actions before capturing the final draft revision, and restore their previous editable/disabled state on rejection or retryable transaction failure. Existing managed-tab disabling helps, but direct editor guards and programmatic shortcut handlers must also respect the freeze. Draft flush must finish before transaction resolution and destructive cleanup; an unresolved draft flush does not authorize a commit, rollback or editor destruction. Existing admission stop/queued-operation cancellation remains part of initiating close. A failed/cancelled close keeps the autosave handle registered. Explicit discard-latest bypasses only this attempt's draft flush; it must not delete the previous checkpoint, disable protection globally, or suppress future edits if transaction closure is later rejected.

The normal public `SqlEditorPane` constructor must remain available. Introduce an explicit recovery construction path that does not create `JdbcEditorSession`, install global-selection prewarming or start metadata. Raw schema whitespace must survive the restoration path. Before the first explicit database action, revalidate saved ID/type using the in-memory config map; after admission use existing immutable pinning. Guard `prewarm`, `membersFor`, `loadColumnsAsync`, Ctrl-click and global-selection listeners, not only the constructor. No source-text-only test can establish the zero-provider/session/metadata guarantee.

The current `AppShell.shutdownRemaining()` executes on a virtual thread, but coordinator shutdown belongs to UI ownership. Its bridge must dispatch timer stop/runtime shutdown to FX and await the resulting future only from the background teardown. Do not call UI-owned runtime methods directly in `shutdownRemaining`, and never join on FX. Runtime drain/lock release precedes shutting down its caller-owned writer executor. Construction-abort cleanup needs a UI detach path as well as the existing blocking `pane::closeResources` binding; preserve that existing ownership contract and test actual subscription cleanup.

The current editor result status label is also the result-export revision signal. Draft status/privacy/retry controls require a separate label/row, not reuse of `statusLabel`, so background autosave cannot overwrite query/export feedback or increment result-export revisions. Existing source-contract tests are maintained as useful ownership constraints, while actual FX tests validate listener timing, freeze/reject/retry and text equality.

### Concrete editor and restoration acceptance matrix

| Boundary | Required observable evidence |
| --- | --- |
| Normal editor/history initialization | Bind after initial text/schema assignment; a nonempty new editor becomes dirty, empty new editor does not; a restored checkpoint remains clean. |
| Connection intent | Normal constructor remains callable; recovery construction passes no initial bound config to `SqlEditorConnectionAdmission`, so even a matching display candidate is not pinned before explicit action. |
| Passive restoration | Inject a counting provider resolver via the existing package-private `ConnectionManager` constructor in a test-only service-package fixture; real FX text assignment including `alias.`, global selection changes and completion triggers keep resolver/session/metadata counters at zero. Use synthetic configs and isolated settings/history, not a default `AppShell` fixture. |
| Deleted or type-changed identity | Revalidate the saved ID/type on action. A same-name different-ID or globally active connection cannot substitute. A chosen replacement is explicit intent, not immediate session creation; admission later revalidates it too. |
| Post-admission behavior | Once admitted, existing immutable pinned config owns the session; later global selection or registry edits do not silently retarget it. |
| Duplicate restore | Same UUID selects the still-open tab; rejected close retains mapping; successful finalization removes mapping; installation failure does not leave an invisible handle/mapping. |
| Close stability | Actual FX events cannot edit/format/comment during the final snapshot/flush interval. Failed/cancelled close restores editability and continued autosave; no transaction is resolved or resource destroyed because a draft flush failed. |
| Mandatory close | Retain non-interactive rollback contract. A draft failure rejects close and keeps the editor; an explicit interactive discard path is separate, not silent mandatory data loss. |
| Construction abort | Keep the existing early `pane -> binding.bind(pane::closeResources)` resource ownership binding. Blocking abort must also dispatch/await draft detachment safely; no FX join and no leaked subscribed handle if tab installation fails after binding. |
| Status separation | Autosave changes its own status row, not the result status label or result-export revision; privacy copy states history is independent. |

This matrix refines the next executable editor/UI plan; it is not a claim that the editor already implements these paths.

## Recovery manager handoff (next integration task)

Keep a separate “SQL 草稿” entry next to SQL history. Opening that manager refreshes the application runtime snapshot asynchronously and shows initializing, unavailable, partial-cleanup and disabled states truthfully. Protection disabled does not hide recoverable records. Management operations temporarily disable restore/delete/clear controls until their result is applied on FX; a refused operation is not an empty snapshot.

The manager lists records newest first with timestamp, connection display intent, raw schema and a bounded single-line preview. The full raw SQL is shown read-only only after explicit selection. Empty SQL is a recoverable “空草稿”, not a missing record. Include the same privacy disclosure and explicit deletion/clear confirmations; cancelled confirmation performs no mutation. A partial clear reloads actual survivors and keeps a warning about unknown/corrupt or failed deletions.

Recovery must use its own pane factory, not the existing name-matching history restore. Initial bound config passed to normal connection admission is null; a separate recovery-intent value retains saved ID/type/name. Resolve ID/type with the in-memory connection registry for display, then resolve again at the first explicit database action. Missing/deleted/type-changed intent cannot fall back to global selection, and a chosen replacement remains only intent until that action. After admission retain normal immutable pinning.

Track restored UUID to installed managed-tab content. A duplicate recovery selects that content only; do not use a singleton helper with an unmanaged-new-tab fallback. Publish the mapping only after installation succeeds, keep it on rejected close, and remove it on successful finalization or construction abort. Selection failure must not manufacture a second invisible handle. This lifecycle requires actual ContentTabPane installation/rejection tests, beyond a direct pane detach test.

Verification remains separate from the current autosave task: actual FX assignment of SQL containing member-completion triggers, global-selection changes, Ctrl-click and ordinary close must keep provider/session/metadata counters at zero. Use a test-only fixture in the service package to inject the existing provider resolver and synthetic config map; no default AppShell, CredentialCipher profile, connection-store load or live network call. Only an explicit execution/admission test may advance those counters, with a synthetic provider. Full P1 also requires restart/abnormal-exit acceptance using an isolated synthetic profile, then broad review and local main merge.

Fixture detail confirmed from source: `ConnectionManager(null, resolver)` is not valid because `RedisSessionManager` requires a non-null cipher at construction. Use a test-only helper in `com.datacube.config` to call the package-private `CredentialCipher(CredentialProtector, CredentialProtector, CredentialProtector)` constructor with a synthetic protector, then pass that cipher to the service-package manager fixture. Do not call the default cipher constructor or mutate global `user.home` to work around package boundaries. Provider resolution, session creation and network connection are distinct events: `openEditorSession` resolves a provider and calls `sqlRunner` before any JDBC connection opens, so absence of a socket alone is insufficient evidence.

### Text presentation and exact recovery checkpoint

The installed RichTextFX0.11.6 `ReadOnlyStyledDocument.fromString` splits CRLF/CR/LF and joins paragraphs using LF (confirmed from local bytecode, not a live FX acceptance result). Display text therefore cannot be assumed byte-identical to arbitrary valid stored line endings. Keep the original bounded SQL only while the restored SQL is unedited; schema or connection-intent changes must continue saving that original SQL. Release this override on actual SQL editing and capture the new control text thereafter. Highlight against the normalized control text so input line-ending differences cannot overrun style spans. This is an accepted routine design refinement; tests must distinguish normalized visual paragraphs from exact untouched checkpoint bytes.

### Managed recovery UI implementation handoff

The duplicate rule covers every live SQL draft ID, including a normally opened editor which has just autosaved, not only tabs originally opened through recovery. `SqlDraftUi` currently holds bindings only, while `AppShell.openSqlTab` discards the returned managed Tab. Extend ownership bookkeeping so both normal and restored editors publish ID-to-content only after `ContentTabPane.openManagedTab` returns a successfully installed tab. Detachment removes both binding and installed mapping; refusal leaves both. Do not register an already-open normal editor a second time merely because it is shown in the stored-drafts list.

Add a narrow `ContentTabPane.selectExistingContent(Node)` boolean operation which only selects a currently present tab. Existing `openSingletonTab` creates an unmanaged tab when no match is found and is therefore unsuitable. A mapped-but-unselectable content reference must produce a visible failure rather than a replacement unmanaged tab or second handle.

The coordinator's management-busy flag is currently private. An owner-checked read-only accessor is needed for the manager controls and restore admission; mode alone cannot distinguish a clear/refresh still in progress while mode remains ENABLED. In addition to the manager's own pending operation, observe runtime-wide busy state because another editor's privacy controls can start management. Manager callbacks must update only their still-open view, and closing the manager must release its timer/observer registration without stopping the application writer.

Use the existing `ConnectionTreePane.connectionConfigsSnapshot` in-memory supplier for a recovery-specific connection chooser; do not add `store.loadAll` to this path. Never allow the default `ConnConfig.toString()` to render in the choice list, because the record contains credential and host fields. Render only deliberate name/type/ID labels, and revalidate the selected ID/type at execution. Changing the global tree selection is not part of restoration.

Suggested user flow remains explicit: toolbar “SQL 草稿” beside history, newest-first bounded preview list, full read-only SQL only after selecting a row, then “恢复” closes the manager only when the managed open/focus succeeds. Refresh/delete/clear/enable actions show pending and truthful partial/disabled states. Disabled protection keeps the list visible. Cancel is the default on destructive confirmations; cancelling does not mutate disk. Do not keep independent shadow lists which replace a partial-cleanup survivor snapshot with an empty list.

Following tests must use actual ContentTabPane ownership: normal-editor duplicate focus, recovered duplicate focus, refused close mapping retention, approved finalization removal, factory/installation failure abort and subscription release. A controlled factory can start `closeAllManagedTabsMandatory()` before returning the new pane, causing its reserved installation to be refused; wait for the resulting abort barrier before checking for leaked handles. This provides an actual lifecycle test without introducing a production-only fault hook.
