package com.datacube.build;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiWorkflowContractTest {

    @Test
    void verificationWorkflowGatesPullRequestsMainWindowsLinuxAndRedis() throws IOException {
        String workflow = workflow("verify.yml");

        assertAll(
                () -> assertTrue(workflow.contains("pull_request:")),
                () -> assertTrue(workflow.contains("branches: [main]")),
                () -> assertTrue(workflow.contains("workflow_call:")),
                () -> assertTrue(workflow.contains("ubuntu-latest")),
                () -> assertTrue(workflow.contains("windows-latest")),
                () -> assertTrue(workflow.contains("actions/setup-java@v5")),
                () -> assertTrue(workflow.contains("java-version: '25'")),
                () -> assertTrue(workflow.contains("gradle/actions/setup-gradle@v6")),
                () -> assertTrue(workflow.contains("gradle/actions/wrapper-validation@v6")),
                () -> assertTrue(workflow.contains("clean test")),
                () -> assertTrue(workflow.contains("RedisLiveIntegrationTest")),
                () -> assertTrue(workflow.contains("redis:7.4-alpine")),
                () -> assertTrue(workflow.contains("jlink"))
        );
    }

    private static String workflow(String name) throws IOException {
        Path path = Path.of(System.getProperty("user.dir"), ".github", "workflows", name);
        assertTrue(Files.exists(path), "missing workflow: " + path);
        return Files.readString(path);
    }
}
