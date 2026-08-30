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
- Mandatory editor close currently rolls back without a transaction dialog. Preserve that contract and its tests; handle unsaved-draft refusal before irreversible resource disposal. Do not report FAILED_PARTIAL for a failure that occurred before any destructive step merely to force application quarantine.

## Verified current integration seams (read-only, 2026-08-30)

`AppShell.openSqlTab` installs both interactive and mandatory guards through `ManagedTabFactorySequence`; construction cleanup currently binds `pane::closeResources`. Its `shutdownRemaining` runs only after managed tabs, providing the final drain/release position. Existing SQL-history restore matches by connection name and changes global selection, so it cannot serve as the draft restore implementation.

`SqlEditorPane` normal construction currently creates a bound JDBC session before `build`; initial schema is trimmed. Normal `currentConn` falls back to the global candidate. Draft restoration therefore needs an explicit construction mode and exact raw schema path, not a null-connection shortcut. `captureClosePlan` is FX-only but trims schema for history, so draft capture must be independent. `runDestructiveClose` is best-effort across history and resource cleanup; do not put recoverable draft-save failure inside that method.

These observations are code-read evidence, not proof of a functioning recovery UI. The actual queue, coordinator, restoration admission and desktop acceptance each still require executable plans, TDD, task review and a final integrated review before merging P1 to main.

## Restoration admission detail

Before the first explicit database action, resolve the saved ID from `ConnectionManager.config` again and compare its type. That method is a nullable in-memory lookup; a deleted connection must not become a `requireConfig`/provider call or a global-selection fallback. Resolving only when the recovery dialog opens leaves a deletion/type-change window before execution. Use the current matching immutable config at admission, then preserve the editor's existing pinned-session semantics after admission; do not silently retarget an already admitted editor when another connection is selected globally.

This distinction also separates display intent from resource ownership. A matching saved target may be shown while JDBC and metadata access remain inactive. Missing/unmatched targets require an explicit user selection, and showing that selection UI must use the in-memory connection snapshot rather than reload credentials/history files. Restored text assignment, including member-completion triggers such as `alias.`, must remain inside the metadata gate until explicit admission.

## Initialization and unavailable storage

Opening/initializing the local directory is background work. Before it succeeds, UI must say that draft protection is initializing and no save is confirmed. Editing remains possible and the latest eligible text is captured after successful initialization; do not queue a full SQL snapshot for every initialization-time input event.

Closing a modified editor before initialization completes, or while protection is unavailable, must not report a successful draft flush. Preserve the editor for retry/cancel, or require the explicit discard-latest path before destructive cleanup. Deliberately disabled protection is a distinct user choice and can allow normal close without promising a saved draft. Never translate a failed disable preference write into permanent disabled success merely to bypass the close guard.
