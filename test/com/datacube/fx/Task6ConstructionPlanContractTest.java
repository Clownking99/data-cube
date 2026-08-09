package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Task6ConstructionPlanContractTest {

    @Test
    void jdbcSessionTemplateUsesImmediateBlockingOwnershipAndExplicitRollback() throws Exception {
        String plan = Files.readString(
                Path.of("docs/superpowers/plans/2026-08-09-safe-sql-session.md"));

        assertTrue(Pattern.compile(
                "JdbcEditorSession jdbcSession = connections\\.openEditorSession\\([^;]+;\\R"
                        + "\\s*construction\\.ownBlocking\\(jdbcSession::close\\);"
        ).matcher(plan).find());
        assertTrue(plan.contains("throw construction.close(failure).failure();"));
        assertFalse(plan.contains("try (ConstructionOwner"));
    }
}
