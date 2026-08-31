# SQL completion focus during workspace recovery

## Evidence and scope

Two isolated AppShell desktop launches reproduced a completion popup above the SQL draft manager after explicit workspace restore. No typing or SQL execution occurred. Both restored synthetic drafts and their saved selection positions correctly; normal exit and same-profile restart succeeded. The text listener in SqlAutoComplete queues maybeShow for every programmatic replacement, without checking editor focus. The existing metadata refresh path already checks focus.

User standing authorization permits routine product improvements without further confirmation. This is a narrow acceptance fix, not a new P3 feature.

## Alternatives and decision

1. Gate automatic completion at scheduling and delivery on actual editor focus (chosen). Fixes background/restore traffic in the component that owns completion; preserves explicit shortcut behavior.
2. Suppress completion only in each restore caller. Couples recovery to completion and leaves other programmatic/background text changes exposed.
3. Change all text insertion APIs to distinguish input provenance. Larger change not needed for this observed defect.

Automatic text-triggered completion must not request candidates or open a popup when the editor is unfocused. A callback queued while focused must recheck focus before delivery. Keep explicit Ctrl+Space, completion navigation/application, metadata refresh, text and selection, persistence and connection admission unchanged. Do not add dependencies or public test-only APIs.

## Verification

First reproduce an assertion failure using real CodeArea and its text listener, draining the FX queue rather than sleeping. Cover unfocused replacement, focus changing around queued delivery, focused completion and explicit keyboard acceptance. Use temporary settings and no connections. Re-run adjacent recovery tests and full nonheadless regression. Independently review the bounded change and repeat the isolated desktop restore. Do not claim the entire desktop matrix passes: keyboard-modal targeting and privacy/deletion paths retain their separately documented limits.
