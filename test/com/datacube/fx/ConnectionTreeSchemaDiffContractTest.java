package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionTreeSchemaDiffContractTest {

    @Test
    void treeActionsExposeSchemaDiffWithSourceConnectionAndSchema() throws Exception {
        Method method = ConnectionTreePane.Actions.class.getMethod(
                "openSchemaDiff", ConnConfig.class, String.class);

        assertNotNull(method);
        assertEquals(void.class, method.getReturnType());
    }

    @Test
    void relationalConnectionAndSchemaMenusExposeSchemaDiffButRedisMenusNeverDo() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/ConnectionTreePane.java"));
        String connection = switchBlock(source, "case CONNECTION", "case REDIS_DB");
        String redis = switchBlock(source, "case REDIS_DB", "case SCHEMA");
        String schema = switchBlock(source, "case SCHEMA", "case TABLES");

        assertTrue(connection.contains("Schema 对比..."));
        assertTrue(connection.contains("d.conn.type() != DbType.REDIS"));
        assertTrue(connection.contains("actions.openSchemaDiff(d.conn, null)"));
        assertTrue(schema.contains("Schema 对比..."));
        assertTrue(schema.contains("actions.openSchemaDiff(connOf(getTreeItem()), d.schema)"));
        assertFalse(redis.contains("Schema 对比"));
        assertFalse(redis.contains("openSchemaDiff"));
    }

    @Test
    void appShellUsesOneReservationFactoryAndBindsCleanupBeforeManagedSpecPublication()
            throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/AppShell.java"));
        int start = source.indexOf("public void openSchemaDiff");
        int end = source.indexOf("@Override", start + 1);
        String body = source.substring(start, end);

        assertEquals(1, occurrences(body, "contentTabs.openManagedTab("));
        int owner = body.indexOf("ConstructionOwner construction = new ConstructionOwner");
        int pane = body.indexOf("new SchemaDiffPane", owner);
        int owned = body.indexOf("construction.ownBlocking(pane::closeResources)", pane);
        int binding = body.indexOf("binding.bind(pane::closeResources)", owned);
        int spec = body.indexOf("new ContentTabPane.ManagedTabSpec", binding);
        int commit = body.indexOf("construction.commit()", spec);

        assertTrue(owner >= 0);
        assertTrue(pane > owner);
        assertTrue(owned > pane);
        assertTrue(binding > owned);
        assertTrue(spec > binding);
        assertTrue(commit > spec);
        assertTrue(body.contains("pane::requestClose"));
        assertTrue(body.contains("pane::requestMandatoryClose"));
        assertTrue(body.contains("pane::finalizeCloseOnFx"));
        assertTrue(body.contains("catch (SafeConstructionFailure failure)"));
        assertTrue(body.contains("throw failure;"),
                "pane constructor deferred cleanup must survive the outer owner");
        assertTrue(body.contains("throw construction.close(failure).failure()"));
    }

    private static String switchBlock(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + 1);
        return source.substring(start, end);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int cursor = 0;
        while ((cursor = value.indexOf(needle, cursor)) >= 0) {
            count++;
            cursor += needle.length();
        }
        return count;
    }
}
