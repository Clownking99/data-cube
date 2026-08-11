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
    void appShellUsesOneReservationFactoryAndNoPreReservationConnectionStoreIo()
            throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/AppShell.java"));
        int start = source.indexOf("public void openSchemaDiff");
        int end = source.indexOf("@Override", start + 1);
        String body = source.substring(start, end);

        assertEquals(1, occurrences(body, "contentTabs.openManagedTab("));
        assertTrue(body.contains("SchemaDiffManagedTabFactory.factory("));
        assertTrue(body.contains("connectionTree::connectionConfigsSnapshot"));
        assertTrue(body.contains("new SchemaDiffPane("));
        assertFalse(body.contains("store.loadAll()"));
        assertFalse(body.substring(0, body.indexOf("contentTabs.openManagedTab("))
                .contains("loadAll"));
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
