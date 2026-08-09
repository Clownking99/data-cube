package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedPaneCloseContractTest {

    @Test
    void dataGridSplitsBlockingTasksFromFxListenerFinalizer() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/DataGridPane.java"));

        assertTrue(source.contains("void closeResources()"));
        assertTrue(source.contains("void finalizeCloseOnFx()"));
        assertTrue(source.contains("removeListener(commentModeListener)"));
    }

    @Test
    void redisPanesUseBestEffortCloseForQueueAndSession() throws Exception {
        for (String pane : new String[] {"RedisConsolePane", "RedisKeyBrowserPane"}) {
            String source = Files.readString(Path.of("src/com/datacube/fx/" + pane + ".java"));
            assertTrue(source.contains("RedisPaneCloseSequence.close"), pane);
        }
    }

    @Test
    void appShellBackgroundTabsCarryIndependentCleanupAndFxFinalizer() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/AppShell.java"));

        assertTrue(source.contains("record BackgroundTab(Node content, Runnable blockingCleanup,"
                + " Runnable uiFinalizer)"));
        assertTrue(source.contains("tab.uiFinalizer()"));
    }
}
