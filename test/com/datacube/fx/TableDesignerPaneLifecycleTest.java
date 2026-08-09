package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.TableDesignService;
import com.datacube.spi.model.DbType;
import com.datacube.spi.model.TableRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableDesignerPaneLifecycleTest {

    @Test
    void isAutoCloseableAndRequiresSharedTaskRunnerForNewAndExistingTables() throws Exception {
        assertTrue(AutoCloseable.class.isAssignableFrom(TableDesignerPane.class));
        assertNotNull(TableDesignerPane.class.getConstructor(
                TableDesignService.class, String.class, String.class, TableRef.class,
                String.class, DbType.class, FxTaskRunner.class));
    }
}
