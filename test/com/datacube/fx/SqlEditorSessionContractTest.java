package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlEditorSessionContractTest {

    @Test
    void routesSqlExecutionThroughDedicatedSafetyAwareSession() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));

        assertTrue(source.contains("JdbcEditorSession"));
        assertTrue(source.contains("SqlSafetyAnalyzer.analyze"));
        assertTrue(source.contains("SqlSafetyPolicy.decide"));
        assertFalse(source.contains("connections.acquire(connId)"));
        assertTrue(source.contains("tasks.submit"));
    }

    @Test
    void recordsBlockingSessionOwnershipImmediatelyAfterOpeningIt() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));
        String open = "connections.openEditorSession(editorConnection)";
        String own = "construction.ownBlocking(this::awaitStrictSessionCleanup)";

        int openings = 0;
        for (int openIndex = source.indexOf(open); openIndex >= 0;
                openIndex = source.indexOf(open, openIndex + open.length())) {
            int ownIndex = source.indexOf(own, openIndex);
            assertTrue(ownIndex > openIndex,
                    "every opened JDBC session must immediately gain strict blocking ownership");
            assertEquals(";", source.substring(openIndex + open.length(), ownIndex).trim(),
                    "only the opening statement terminator may precede ownBlocking");
            openings++;
        }
        assertEquals(2, openings, "constructor and lazy admission must both own the session");
        assertFalse(source.contains("construction.ownBlocking(jdbcSession::close)"),
                "construction cleanup must not use the compatibility API that swallows failures");
        assertFalse(source.contains("openEditorSession(editorConnection.id())"),
                "the session must consume the immutable pinned config rather than reread by id");
    }

    @Test
    void pinnedEditorStopsFollowingLaterTreeSelections() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));
        int listener = source.indexOf("this.activeConnectionListener");
        int pinnedGuard = source.indexOf("if (admission.pinned() == null)", listener);
        int prewarm = source.indexOf("prewarm(connection)", listener);

        assertTrue(listener >= 0 && pinnedGuard > listener);
        assertTrue(prewarm > pinnedGuard,
                "tree selection metadata may only be followed while the editor remains unbound");
    }

    @Test
    void cancelCloseRollsBackOnlyAManualPendingTransaction() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));
        int resolver = source.indexOf("private static void resolveCloseTransaction");
        int nextMethod = source.indexOf("\n    private ", resolver + 1);
        String method = source.substring(resolver, nextMethod);

        assertTrue(method.contains("snapshot.transactionMode()"
                + " == JdbcEditorSession.TransactionMode.MANUAL"));
        assertTrue(method.contains("snapshot.hasPendingTransaction()"));
    }

    @Test
    void explainSplittingUsesThePinnedConnectionsOracleMode() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));

        assertTrue(source.contains(
                "SqlScriptSplitter.split(text, active.type() == DbType.ORACLE)"));
    }

    @Test
    void fxAdmissionPinsBeforeSafetyAndClosingPreventsSessionPublication() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));

        int execute = source.indexOf("private void onExecute()");
        int pin = source.indexOf("admitCurrentConnection()", execute);
        int safety = source.indexOf("allowBySafetyPolicy", execute);
        assertTrue(pin > execute && pin < safety,
                "execution must pin before safety analysis and background submission");
        assertTrue(source.contains("admission.beginClosing()"));
        assertTrue(source.contains("sessionOperations.stopAcceptingAndCancelQueued()"));
        assertTrue(source.contains("admission.requireOpenPinned()"));
        assertTrue(source.contains("existing.snapshot().connectionId().equals(connection.id())"));
    }

    @Test
    void closeWaitsForSessionQueueAndUsesStrictFinalResources() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));

        assertTrue(source.contains("awaitSessionOperationsIdle"));
        assertTrue(source.contains("currentEditorSession()"));
        assertFalse(source.contains("ClosePlan(\n            String connectionName,\n"
                + "            String schema,\n            String sql,\n"
                + "            JdbcEditorSession editorSession"));
        assertTrue(source.contains("history.recordStrict"));
        assertTrue(source.contains("editorSession.closeStrict()"));
        assertTrue(source.contains("running = sessionOperations.snapshot().pending()"));
        assertTrue(source.contains(
                "submitSessionOperation(SerialSessionOperationQueue.OperationKind.EXECUTE"));
        assertTrue(source.contains("tasks.submit(editorSession::cancel"));
    }

    @Test
    void closeWaitsForNonCancellableCurrentOperationBeforeFreshFxDecision() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));

        assertTrue(source.contains("operationSnapshot.running()"
                + " && !operationSnapshot.currentCancellable()"));
        assertTrue(source.contains("continueCloseDecisionOnFx"));
        assertTrue(source.contains("sessionOperations.suppressCallbacks()"));
        assertTrue(source.contains("operationSnapshot.currentCancellable()"));
        assertTrue(source.contains("!operationSnapshot.accepting()"),
                "terminal callbacks must not re-enable controls while close admission is active");
    }

    @Test
    void normalAndMandatoryCloseShareObservableStrictCleanupSettlement() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));

        assertTrue(source.contains("StrictCleanupRetryChannel sessionCleanup"));
        assertTrue(source.contains("awaitStrictSessionCleanup"));
        assertTrue(source.contains("sessionCleanup.start()"));
    }

    @Test
    void transactionResolutionGatesHistoryScopesAndStrictCleanup() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));

        assertTrue(source.contains("SqlEditorCloseSequence.run("));
        int closeMethod = source.indexOf("private void closeInBackground");
        int nextMethod = source.indexOf("\n    private ", closeMethod + 1);
        String body = source.substring(closeMethod, nextMethod);
        int gate = body.indexOf("resolveCloseTransaction");
        int destructive = body.indexOf("runDestructiveClose");

        int destructiveMethod = source.indexOf("private void runDestructiveClose");
        int afterDestructive = source.indexOf("\n    private ", destructiveMethod + 1);
        String destructiveBody = source.substring(destructiveMethod, afterDestructive);
        int history = destructiveBody.indexOf("persistCloseSnapshot");
        int metadata = destructiveBody.indexOf("metadataTasks::close");
        int strict = destructiveBody.indexOf("awaitStrictSessionCleanup");

        assertTrue(gate >= 0 && gate < destructive,
                "transaction gate must precede every destructive close step");
        assertTrue(history >= 0 && history < metadata && metadata < strict,
                "destructive close must retain history, scope, and strict-cleanup order");
    }

    @Test
    void mandatoryCloseIsDialogFreeAndAlwaysChoosesRollback() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));

        assertTrue(source.contains("public CompletionStage<CloseGuardOutcome> requestMandatoryClose()"));
        int start = source.indexOf("private CompletionStage<CloseGuardOutcome> startMandatoryCloseAttempt");
        int nextMethod = source.indexOf("\n    private ", start + 1);
        String body = source.substring(start, nextMethod);
        assertTrue(body.contains("CloseDecision.CANCEL_ROLLBACK"));
        assertFalse(body.contains("showAndWait"));
        assertFalse(body.contains("requestTransactionClose"));
        assertFalse(body.contains("requestCancelRollbackClose"));
    }
}
