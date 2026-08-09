package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DdlService;
import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequenceDesignerPaneLifecycleTest {

    @Test
    void isAutoCloseableAndRequiresSharedTaskRunner() throws Exception {
        assertTrue(AutoCloseable.class.isAssignableFrom(SequenceDesignerPane.class));
        assertNotNull(SequenceDesignerPane.class.getConstructor(
                DdlService.class, String.class, String.class, String.class, String.class,
                DbType.class, FxTaskRunner.class));
    }
}
