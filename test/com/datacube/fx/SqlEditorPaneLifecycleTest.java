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

import java.util.function.BiConsumer;

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
}
