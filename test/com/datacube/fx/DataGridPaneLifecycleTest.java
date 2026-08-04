package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DataBrowseService;
import com.datacube.service.DataEditService;
import com.datacube.spi.model.TableRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataGridPaneLifecycleTest {

    @Test
    void isAutoCloseableAndRequiresSharedTaskRunner() throws Exception {
        assertTrue(AutoCloseable.class.isAssignableFrom(DataGridPane.class));
        assertNotNull(DataGridPane.class.getConstructor(
                DataBrowseService.class, DataEditService.class, String.class, String.class,
                TableRef.class, AppSettings.class, boolean.class, FxTaskRunner.class));
    }
}
