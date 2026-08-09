package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.ConnectionManager;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.TableRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlEditorPaneLifecycleTest {

    @Test
    void isAutoCloseableAndRequiresSharedTaskRunner() throws Exception {
        assertTrue(AutoCloseable.class.isAssignableFrom(SqlEditorPane.class));
        assertNotNull(SqlEditorPane.class.getConstructor(
                SessionContext.class, ConnectionManager.class, ObjectTreeService.class,
                AppSettings.class, BiConsumer.class, ConnConfig.class, String.class,
                SqlHistoryStore.class, ShortcutSettings.class, FxTaskRunner.class));
    }

    @Test
    void separatesBlockingResourceCleanupFromFxFinalization() throws Exception {
        assertNotNull(SqlEditorPane.class.getDeclaredMethod("closeResources"));
        assertNotNull(SqlEditorPane.class.getDeclaredMethod("finalizeCloseOnFx"));
        assertEquals(CompletionStage.class,
                SqlEditorPane.class.getDeclaredMethod("requestClose").getReturnType());
    }

    @Test
    void appShellUsesThePaneAsyncGuardInsteadOfAnFxBlockingFinalizer() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/AppShell.java"));

        assertTrue(source.contains("pane::requestClose"));
        assertFalse(source.contains("AsyncTabCloseGuards.blocking(pane::closeResources)"));
        assertTrue(source.contains("pane -> binding.bind(pane::closeResources)"));
        assertTrue(source.contains("pane -> pane.setSqlText(entry.sql())"));
    }

    @Test
    void backgroundCloseUsesBestEffortSequenceAndRetryableGuardCache() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));

        assertTrue(source.contains("AsyncTabCloseGuards.blockingAttempt"));
        assertTrue(source.contains("BestEffortCloseSequence.run"));
        assertTrue(source.contains("metadataTasks::close"));
        assertTrue(source.contains("tasks::close"));
        assertTrue(source.contains("construction.own(() -> settings.commentModeProperty()"
                + ".removeListener(commentModeListener))"));
        assertTrue(source.contains("construction.own(() -> session.activeConnectionProperty()"
                + ".removeListener(activeConnectionListener))"));
        assertTrue(source.indexOf("metadataTasks::close") < source.indexOf("persistCloseSnapshot"));
        assertTrue(source.indexOf("tasks::close") < source.indexOf("persistCloseSnapshot"));
    }
}
