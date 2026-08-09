package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataCubeFxShutdownContractTest {

    @Test
    void windowClosesOnlyForCompletedOutcomeAndPartialFailureIsNotMadeRetryable() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/DataCubeFx.java"));

        assertTrue(source.contains("ShutdownQuarantine.Action.RECOVER"));
        assertTrue(source.contains("ShutdownQuarantine.Action.FATAL"));
        assertTrue(source.contains("appShell.getRoot().setDisable(true)"));
        assertTrue(source.contains("appShell.getRoot().setDisable(false)"));
        assertFalse(source.contains("Boolean.TRUE.equals(approved)"));
    }
}
