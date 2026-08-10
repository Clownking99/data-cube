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
        String own = "construction.ownBlocking(jdbcSession::close)";

        int openIndex = source.indexOf(open);
        int ownIndex = source.indexOf(own, openIndex);
        assertTrue(openIndex >= 0, "editor session must be opened by the pane");
        assertTrue(ownIndex > openIndex, "opened JDBC session must immediately gain blocking ownership");
        assertEquals(";", source.substring(openIndex + open.length(), ownIndex).trim(),
                "only the opening statement terminator may precede ownBlocking");
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
}
