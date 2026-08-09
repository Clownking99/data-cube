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
        String open = "connections.openEditorSession(editorConnection.id())";
        String own = "construction.ownBlocking(jdbcSession::close)";

        int openIndex = source.indexOf(open);
        int ownIndex = source.indexOf(own, openIndex);
        assertTrue(openIndex >= 0, "editor session must be opened by the pane");
        assertTrue(ownIndex > openIndex, "opened JDBC session must immediately gain blocking ownership");
        assertEquals(";", source.substring(openIndex + open.length(), ownIndex).trim(),
                "only the opening statement terminator may precede ownBlocking");
    }

    @Test
    void pinnedEditorStopsFollowingLaterTreeSelections() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));
        int listener = source.indexOf("this.activeConnectionListener");
        int pinnedGuard = source.indexOf("if (editorConnection == null)", listener);
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
}
