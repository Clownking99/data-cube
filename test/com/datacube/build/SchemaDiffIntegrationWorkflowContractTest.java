package com.datacube.build;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffIntegrationWorkflowContractTest {

    @Test
    void liveSchemaDiffWorkflowIsManualExplicitlyWriteEnabledAndLinuxCompatible()
            throws IOException {
        String workflow = workflow();

        assertAll(
                () -> assertTrue(workflow.contains("workflow_dispatch:")),
                () -> assertFalse(workflow.contains("pull_request:")),
                () -> assertFalse(workflow.contains("schedule:")),
                () -> assertFalse(workflow.contains("push:")),
                () -> assertTrue(workflow.contains("type: choice")),
                () -> assertTrue(workflow.contains("environment: schema-diff-postgresql")),
                () -> assertTrue(workflow.contains("environment: schema-diff-oracle")),
                () -> assertTrue(workflow.contains("DATACUBE_SCHEMA_DIFF_TEST_ALLOW_WRITE: 'true'")),
                () -> assertTrue(workflow.contains("./gradlew test --tests "
                        + "com.datacube.schemadiff.SchemaDiffLiveIntegrationTest")),
                () -> assertFalse(workflow.contains("gradlew.bat")),
                () -> assertTrue(workflow.contains("actions/upload-artifact@v4")));
    }

    @Test
    void eachProviderJobReceivesOnlyItsOwnRequiredSecretSet() throws IOException {
        String workflow = workflow();
        String postgres = job(workflow, "postgresql", "oracle");
        String oracle = job(workflow, "oracle", null);

        assertAll(
                () -> assertTrue(postgres.contains("DATACUBE_SCHEMA_DIFF_POSTGRES_HOST:")),
                () -> assertTrue(postgres.contains("DATACUBE_SCHEMA_DIFF_POSTGRES_PORT:")),
                () -> assertTrue(postgres.contains("DATACUBE_SCHEMA_DIFF_POSTGRES_DATABASE:")),
                () -> assertTrue(postgres.contains("DATACUBE_SCHEMA_DIFF_POSTGRES_USERNAME:")),
                () -> assertTrue(postgres.contains("DATACUBE_SCHEMA_DIFF_POSTGRES_PASSWORD:")),
                () -> assertFalse(postgres.contains("DATACUBE_SCHEMA_DIFF_ORACLE_")),
                () -> assertTrue(oracle.contains("DATACUBE_SCHEMA_DIFF_ORACLE_HOST:")),
                () -> assertTrue(oracle.contains("DATACUBE_SCHEMA_DIFF_ORACLE_PORT:")),
                () -> assertTrue(oracle.contains("DATACUBE_SCHEMA_DIFF_ORACLE_DATABASE:")),
                () -> assertTrue(oracle.contains("DATACUBE_SCHEMA_DIFF_ORACLE_USERNAME:")),
                () -> assertTrue(oracle.contains("DATACUBE_SCHEMA_DIFF_ORACLE_PASSWORD:")),
                () -> assertTrue(oracle.contains("DATACUBE_SCHEMA_DIFF_ORACLE_TABLESPACE:")),
                () -> assertFalse(oracle.contains("DATACUBE_SCHEMA_DIFF_POSTGRES_")));
    }

    @Test
    void workflowContainsNoEndpointCredentialOrJdbcExample() throws IOException {
        String workflow = workflow().toLowerCase(Locale.ROOT);

        assertAll(
                () -> assertFalse(workflow.contains("jdbc:")),
                () -> assertFalse(workflow.contains("localhost")),
                () -> assertFalse(workflow.contains("127.0.0.1")),
                () -> assertFalse(workflow.contains("example.com")),
                () -> assertFalse(workflow.contains("password: password")));
    }

    private static String workflow() throws IOException {
        Path path = Path.of(System.getProperty("user.dir"), ".github", "workflows",
                "schema-diff-integration.yml");
        assertTrue(Files.exists(path), "missing workflow: " + path);
        return Files.readString(path);
    }

    private static String job(String workflow, String name, String nextName) {
        String marker = "  " + name + ":";
        int start = workflow.indexOf(marker);
        assertTrue(start >= 0, "missing job: " + name);
        int end = nextName == null ? workflow.length() : workflow.indexOf("  " + nextName + ":", start + 1);
        assertTrue(end > start, "missing next job after: " + name);
        return workflow.substring(start, end);
    }
}
