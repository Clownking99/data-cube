package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppShellTest {

    @Test
    void shutdownUsesMandatoryCloseAllWhileSqlTabsKeepInteractiveClose() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/AppShell.java"));

        assertTrue(source.contains("contentTabs::closeAllManagedTabsMandatory"));
        int sqlTabs = source.indexOf("private void openSqlTab");
        int backgroundTabs = source.indexOf("private void openBackgroundCleanupTab", sqlTabs);
        String sqlBody = source.substring(sqlTabs, backgroundTabs);
        assertTrue(sqlBody.contains("pane::requestClose"));
        assertTrue(sqlBody.contains("pane::requestMandatoryClose"));
    }

    @Test
    void ordinaryBackgroundTabsKeepTheCompatibleManagedSpecConstructor() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/AppShell.java"));
        int backgroundTabs = source.indexOf("private void openBackgroundCleanupTab");
        int nextSection = source.indexOf("/** 连接树动作实现", backgroundTabs);
        String body = source.substring(backgroundTabs, nextSection);

        assertTrue(body.contains("new ContentTabPane.ManagedTabSpec("));
        assertFalse(body.contains("requestMandatoryClose"));
    }

    @Test
    void shutdownClosesTheAppOwnedSqlFileRegistryBeforeManagedTabShutdown() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/AppShell.java"));
        int shutdown = source.indexOf("public CompletionStage<ShutdownOutcome> shutdownAsync()");
        int nextMethod = source.indexOf("\n    /**", shutdown + 1);
        String body = source.substring(shutdown, nextMethod);

        assertTrue(body.contains("sqlFileTabs.close()"));
        assertTrue(body.indexOf("sqlFileTabs.close()") < body.indexOf("shutdown.shutdown()"));
    }
}
