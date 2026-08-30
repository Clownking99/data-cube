## Review amendment 1 (authoritative over the initial close patch)

The user delegated routine design decisions. The controller accepts moving interactive draft protection before close-decision dialogs: the earlier plan placed dialogs before the flush; a dialog is not itself a commit/rollback, but delaying it until protection succeeds avoids asking for a transaction choice on a close that is immediately refused. Actual transaction resolution remains in the existing background gate. This is an autonomous UX/order refinement, not authorization to change transaction semantics.

The review also established a functional callback bug: mandatory close calls suppressCallbacks before asynchronous persistence. SerialSessionOperationQueue permanently drops a result callback arriving in that window, even if draft refusal later reopens the editor. Only suppress after successful protection.

Fix all review findings together, preserving exact supplied code readability. Source and test minification is not an accepted deviation. Restore the complete readable Binding/Ui/test blocks above, retaining the semantic Cancel lookup portability fix and autocomplete guards. Remove conditional skipping of autocomplete assertions: its presence must be asserted. Include all original assertions and comments. Reformat only newly introduced Pane/AppShell hunks, not unrelated code.

### Exact close-flow replacement

Replace the interactive decision method with these two methods; existing captureClosePlan and transaction/background cleanup remain unchanged:
```java
    private void continueCloseDecisionOnFx(CompletableFuture<CloseGuardOutcome> result) {
        if (result.isDone()) return;
        continueAfterDraftFlush(false, result, () -> continueTransactionCloseDecisionOnFx(result));
    }

    private void continueTransactionCloseDecisionOnFx(CompletableFuture<CloseGuardOutcome> result) {
        ClosePlan plan;
        try {
            if (!Platform.isFxApplicationThread()) {
                throw new IllegalStateException("close decision must run on the FX Application Thread");
            }
            plan = captureClosePlan(sessionOperations.snapshot());
        } catch (Throwable preCleanupFailure) {
            reopenAfterRejectedClose();
            result.completeExceptionally(preCleanupFailure);
            return;
        }
        if (plan.decision() == CloseDecision.CANCEL_CLOSE) {
            reopenAfterRejectedClose();
            result.complete(CloseGuardOutcome.REJECTED);
            return;
        }
        sessionOperations.suppressCallbacks();
        try {
            Thread.startVirtualThread(() -> {
                try {
                    closeInBackground(plan);
                    result.complete(CloseGuardOutcome.APPROVED);
                } catch (RetryableTransactionCloseFailure gateFailure) {
                    finishRetryableCloseFailure(result, gateFailure.getCause());
                } catch (Throwable partialFailure) {
                    partialFailure.printStackTrace(System.err);
                    result.complete(CloseGuardOutcome.FAILED_PARTIAL);
                }
            });
        } catch (Throwable startupFailure) {
            reopenAfterRejectedClose();
            result.completeExceptionally(startupFailure);
        }
    }
```

In mandatory close, replace pre-flush suppression and continuation with:
```java
            continueAfterDraftFlush(true, result, () -> {
                sessionOperations.suppressCallbacks();
                Thread.startVirtualThread(() -> result.complete(closeMandatoryInBackground(plan)));
            });
```
Preserve CloseDecision.CANCEL_ROLLBACK and the no-dialog mandatory contract. Both close entries hide autoComplete immediately after freeze (amendment above). Restore confirm's explicit Cancel default from full Binding code.

### Regression code to add before fixes

Add import `com.datacube.fx.task.SerialSessionOperationQueue` and these two tests to SqlEditorDraftIntegrationTest:
```java
    @Test void mandatoryDraftRefusalDoesNotDropAnOperationCompletingDuringFlush() throws Exception {
        try (Fixture f = new Fixture("latest", null, true)) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            CountDownLatch delivered = new CountDownLatch(1);
            var operations = (SerialSessionOperationQueue) field(f.pane, "sessionOperations");
            var operation = operations.submit(SerialSessionOperationQueue.OperationKind.EXECUTE, () -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return "query result";
            }, result -> { callbacks.incrementAndGet(); delivered.countDown(); }, failure -> fail(failure));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                f.fx(() -> f.schema().setText("s".repeat(4097)));
                var close = f.beginClose(true);
                assertFalse(close.isDone());
                release.countDown();
                operation.get(5, TimeUnit.SECONDS);
                assertTrue(delivered.await(5, TimeUnit.SECONDS), "completed result callback must survive a pending draft flush");
                assertEquals(1, callbacks.get(), "a draft-pending close must not drop completed results");
                f.drain();
                assertEquals(CloseGuardOutcome.REJECTED, close.get(5, TimeUnit.SECONDS));
                assertEquals(1, callbacks.get());
                f.fx(() -> assertFalse(((AtomicBoolean) field(f.pane, "resourcesClosed")).get()));
            } finally { release.countDown(); operation.get(5, TimeUnit.SECONDS); }
        }
    }

    @Test void failingDraftPrecedesRunningQueryCloseDecisionDialog() throws Exception {
        try (Fixture f = new Fixture("latest", null, true)) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger prematureDialogs = new AtomicInteger();
            var operations = (SerialSessionOperationQueue) field(f.pane, "sessionOperations");
            var operation = operations.submit(SerialSessionOperationQueue.OperationKind.EXECUTE, () -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return "query result";
            }, result -> {}, failure -> fail(failure));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                f.fx(() -> f.schema().setText("s".repeat(4097)));
                var close = f.fxValue(() -> {
                    Platform.runLater(() -> {
                        // Runs inside a premature modal loop, or after correct asynchronous return.
                        for (Window window : List.copyOf(Window.getWindows())) {
                            if (!window.isShowing()) continue;
                            var cancel = window.getScene().getRoot().lookupAll(".button").stream()
                                    .filter(Button.class::isInstance).map(Button.class::cast)
                                    .filter(Button::isCancelButton).findFirst();
                            if (cancel.isPresent()) {
                                prematureDialogs.incrementAndGet();
                                cancel.get().fire();
                            }
                        }
                    });
                    return f.pane.requestClose().toCompletableFuture();
                });
                f.fx(() -> {});
                assertEquals(0, prematureDialogs.get(), "no transaction/running-query decision before draft protection");
                assertFalse(close.isDone());
                f.drain();
                f.dismiss("取消");
                assertEquals(CloseGuardOutcome.REJECTED, close.get(5, TimeUnit.SECONDS));
            } finally { release.countDown(); operation.get(5, TimeUnit.SECONDS); }
        }
    }
```

Both tests must fail against f41e1d5 before production flow changes. The running-query dialog is selected by the same captureClosePlan gate as the pending-transaction decision; document this exact coverage rather than claiming a pending JDBC transaction test.

In Fixture.dismiss, identify Cancel by isCancelButton (locale-independent), and before firing any close-failure dialog button assert safe defaults:
```java
                var buttons = Window.getWindows().stream().filter(Window::isShowing)
                        .flatMap(window -> window.getScene().getRoot().lookupAll(".button").stream())
                        .filter(Button.class::isInstance).map(Button.class::cast).toList();
                Button cancel = buttons.stream().filter(Button::isCancelButton).findFirst().orElseThrow();
                assertTrue(cancel.isDefaultButton());
                assertTrue(buttons.stream().filter(candidate -> candidate.getText().equals("放弃本次最新修改并关闭"))
                        .noneMatch(Button::isDefaultButton));
```
Use a finally path to dismiss the modal if an assertion fails, so fixture cleanup cannot leave the test process blocked.

Add actual visible-popup closure evidence using an owned Stage in the existing close test or a separate test: show only that synthetic pane and its own completion Popup on FX, assert initially showing, begin close on FX, assert immediately hidden, then drain/settle and hide the owned Stage in finally. Do not close unrelated windows. Cover both normal and mandatory entries if parameterizing this case. This is the only additional fixture expansion needed.

```java
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void closingImmediatelyHidesAnAlreadyVisibleCompletionPopup(boolean mandatory) throws Exception {
        try (Fixture f = new Fixture("select 1", null, true)) {
            javafx.stage.Stage[] stage = new javafx.stage.Stage[1];
            try {
                var close = f.fxValue(() -> {
                    stage[0] = new javafx.stage.Stage();
                    stage[0].setScene(f.pane.getNode().getScene());
                    stage[0].show();
                    Object completion = field(f.pane, "autoComplete");
                    assertNotNull(completion);
                    var popup = (javafx.stage.PopupWindow) field(completion, "popup");
                    popup.show(f.editor(), stage[0].getX() + 20, stage[0].getY() + 20);
                    assertTrue(popup.isShowing(), "fixture must begin with an actual visible popup");
                    var result = (mandatory ? f.pane.requestMandatoryClose() : f.pane.requestClose()).toCompletableFuture();
                    assertFalse(popup.isShowing(), "freeze must dismiss the already-visible popup immediately");
                    return result;
                });
                f.drain();
                assertEquals(CloseGuardOutcome.APPROVED, close.get(5, TimeUnit.SECONDS));
            } finally {
                f.fx(() -> { if (stage[0] != null) stage[0].hide(); });
            }
        }
    }
```

Run RED for new regressions, then focused GREEN for both new suites and existing SqlEditorSessionContractTest/SqlEditorPaneLifecycleTest; then one full regression. Record exact XML, commands/exit codes, deviations, and commit in the same report. No real profile/database access. Root performs final verification and re-review. No main merge.
