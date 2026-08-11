package com.datacube.build;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                () -> assertTrue(workflow.contains("./gradlew test --tests "
                        + "com.datacube.schemadiff.SchemaDiffLiveIntegrationTest")),
                () -> assertFalse(workflow.contains("gradlew.bat")),
                () -> assertTrue(workflow.contains("actions/upload-artifact@v4")));
    }

    @Test
    void providerCredentialsAndWriteGateExistOnlyOnTheMatchingRunStep() throws IOException {
        List<String> lines = workflow().lines().toList();
        Map<String, String> postgresqlEnvironment = Map.of(
                "DATACUBE_SCHEMA_DIFF_TEST_ALLOW_WRITE", "'true'",
                "DATACUBE_SCHEMA_DIFF_POSTGRES_HOST", secret("SCHEMA_DIFF_POSTGRES_HOST"),
                "DATACUBE_SCHEMA_DIFF_POSTGRES_PORT", secret("SCHEMA_DIFF_POSTGRES_PORT"),
                "DATACUBE_SCHEMA_DIFF_POSTGRES_DATABASE", secret("SCHEMA_DIFF_POSTGRES_DATABASE"),
                "DATACUBE_SCHEMA_DIFF_POSTGRES_USERNAME", secret("SCHEMA_DIFF_POSTGRES_USERNAME"),
                "DATACUBE_SCHEMA_DIFF_POSTGRES_PASSWORD", secret("SCHEMA_DIFF_POSTGRES_PASSWORD"));
        Map<String, String> oracleEnvironment = Map.of(
                "DATACUBE_SCHEMA_DIFF_TEST_ALLOW_WRITE", "'true'",
                "DATACUBE_SCHEMA_DIFF_ORACLE_HOST", secret("SCHEMA_DIFF_ORACLE_HOST"),
                "DATACUBE_SCHEMA_DIFF_ORACLE_PORT", secret("SCHEMA_DIFF_ORACLE_PORT"),
                "DATACUBE_SCHEMA_DIFF_ORACLE_DATABASE", secret("SCHEMA_DIFF_ORACLE_DATABASE"),
                "DATACUBE_SCHEMA_DIFF_ORACLE_USERNAME", secret("SCHEMA_DIFF_ORACLE_USERNAME"),
                "DATACUBE_SCHEMA_DIFF_ORACLE_PASSWORD", secret("SCHEMA_DIFF_ORACLE_PASSWORD"),
                "DATACUBE_SCHEMA_DIFF_ORACLE_TABLESPACE", secret("SCHEMA_DIFF_ORACLE_TABLESPACE"));

        assertRunStepEnvironment(
                job(lines, "postgresql"),
                "Run opt-in PostgreSQL Schema Diff smoke",
                postgresqlEnvironment);
        assertRunStepEnvironment(
                job(lines, "oracle"),
                "Run opt-in Oracle Schema Diff smoke",
                oracleEnvironment);
        assertWorkflowSensitiveScope(lines);

        List<String> workflowScopedSecret = new ArrayList<>(lines);
        int permissions = workflowScopedSecret.indexOf("permissions:");
        assertTrue(permissions >= 0, "missing top-level permissions block");
        workflowScopedSecret.addAll(permissions, List.of(
                "env:",
                "  DATACUBE_SCHEMA_DIFF_ESCAPED: ${{ secrets['SCHEMA_DIFF_ESCAPED'] }}"));

        // The old job-slice assertions remain green and demonstrate the inherited-scope gap.
        assertRunStepEnvironment(
                job(workflowScopedSecret, "postgresql"),
                "Run opt-in PostgreSQL Schema Diff smoke",
                postgresqlEnvironment);
        assertRunStepEnvironment(
                job(workflowScopedSecret, "oracle"),
                "Run opt-in Oracle Schema Diff smoke",
                oracleEnvironment);
        assertThrows(AssertionError.class,
                () -> assertWorkflowSensitiveScope(workflowScopedSecret));
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

    private static void assertWorkflowSensitiveScope(List<String> workflow) {
        Set<Integer> allowedLines = new java.util.HashSet<>();
        for (String stepName : List.of(
                "Run opt-in PostgreSQL Schema Diff smoke",
                "Run opt-in Oracle Schema Diff smoke")) {
            String marker = "      - name: " + stepName;
            List<Integer> starts = java.util.stream.IntStream.range(0, workflow.size())
                    .filter(index -> workflow.get(index).equals(marker))
                    .boxed()
                    .toList();
            assertEquals(1, starts.size(), "expected exactly one approved smoke step: " + stepName);

            int start = starts.getFirst();
            int stepIndent = indentation(workflow.get(start));
            int end = start + 1;
            while (end < workflow.size()
                    && (workflow.get(end).isBlank() || indentation(workflow.get(end)) > stepIndent)) {
                end++;
            }
            for (int index = start; index < end; index++) allowedLines.add(index);
        }

        for (int index = 0; index < workflow.size(); index++) {
            if (!isSensitiveWorkflowLine(workflow.get(index))) continue;
            assertTrue(allowedLines.contains(index),
                    "sensitive workflow content escaped approved smoke steps at line " + (index + 1));
        }
    }

    private static boolean isSensitiveWorkflowLine(String line) {
        String lowerCaseLine = line.toLowerCase(Locale.ROOT);
        return line.contains("DATACUBE_SCHEMA_DIFF_")
                || (lowerCaseLine.contains("${{") && lowerCaseLine.contains("secrets"));
    }

    private static int indentation(String line) {
        int indentation = 0;
        while (indentation < line.length() && line.charAt(indentation) == ' ') indentation++;
        return indentation;
    }

    private static void assertRunStepEnvironment(
            List<String> job, String runStepName, Map<String, String> expected) {
        assertFalse(job.stream().anyMatch(line -> line.equals("    env:")),
                "provider secrets must not be job-scoped");
        List<List<String>> steps = steps(job);
        List<String> runStep = steps.stream()
                .filter(step -> step.getFirst().equals("      - name: " + runStepName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing run step: " + runStepName));

        assertEquals(expected, stepEnvironment(runStep));
        for (List<String> step : steps) {
            if (step == runStep) continue;
            assertFalse(step.stream().anyMatch(
                            line -> line.contains("DATACUBE_SCHEMA_DIFF_") || line.contains("secrets.")),
                    "non-run step received provider environment: " + step.getFirst());
        }
        Set<String> providerLines = job.stream()
                .filter(line -> line.contains("DATACUBE_SCHEMA_DIFF_") || line.contains("secrets."))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.copyOf(runStep.stream()
                .filter(line -> line.contains("DATACUBE_SCHEMA_DIFF_") || line.contains("secrets."))
                .toList()), providerLines);
    }

    private static List<String> job(List<String> workflow, String name) {
        String marker = "  " + name + ":";
        int start = workflow.indexOf(marker);
        assertTrue(start >= 0, "missing job: " + name);
        int end = start + 1;
        while (end < workflow.size() && !workflow.get(end).matches("^  [A-Za-z0-9_-]+:$")) end++;
        return workflow.subList(start, end);
    }

    private static List<List<String>> steps(List<String> job) {
        java.util.ArrayList<List<String>> steps = new java.util.ArrayList<>();
        for (int index = 0; index < job.size();) {
            if (!job.get(index).startsWith("      - ")) {
                index++;
                continue;
            }
            int end = index + 1;
            while (end < job.size() && !job.get(end).startsWith("      - ")) end++;
            steps.add(job.subList(index, end));
            index = end;
        }
        return List.copyOf(steps);
    }

    private static Map<String, String> stepEnvironment(List<String> step) {
        int env = step.indexOf("        env:");
        assertTrue(env >= 0, "run step has no step-scoped env");
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = env + 1; index < step.size(); index++) {
            String line = step.get(index);
            if (!line.startsWith("          ")) break;
            String entry = line.substring(10);
            int separator = entry.indexOf(':');
            assertTrue(separator > 0, "invalid environment entry");
            values.put(entry.substring(0, separator), entry.substring(separator + 1).stripLeading());
        }
        return Map.copyOf(values);
    }

    private static String secret(String name) {
        return "${{ secrets." + name + " }}";
    }
}
