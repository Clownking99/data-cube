package com.datacube.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsToBalanced256MbHeap() {
        AppSettings settings = new AppSettings(tempDir.resolve("settings.properties"));

        assertEquals(256, settings.getMaxHeapMb());
    }

    @Test
    void preservesExplicitHeapFromExistingSettings() throws Exception {
        Path file = tempDir.resolve("settings.properties");
        Files.writeString(file, "jvm.maxHeapMb=1024\n");

        AppSettings settings = new AppSettings(file);

        assertEquals(1024, settings.getMaxHeapMb());
    }
}
