package com.datacube.build;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionLauncherContractTest {

    @Test
    void windowsDistributionPublishesOnlyTheDesktopLauncher() throws IOException {
        String build = repositoryFile("build.gradle");
        String readme = repositoryFile("README.md");

        assertAll(
                () -> assertTrue(build.contains("name = 'DataCube'")),
                () -> assertFalse(build.contains("secondaryLauncher")),
                () -> assertFalse(build.contains("DataCubeCli")),
                () -> assertFalse(readme.contains("DataCubeCli.exe"))
        );
    }

    private static String repositoryFile(String name) throws IOException {
        Path path = Path.of(System.getProperty("user.dir"), name);
        assertTrue(Files.exists(path), "missing repository file: " + path);
        return Files.readString(path);
    }
}
