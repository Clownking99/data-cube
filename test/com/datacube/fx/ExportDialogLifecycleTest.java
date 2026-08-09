package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.model.TableRef;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ExportDialogLifecycleTest {

    @Test
    void showRequiresSharedTaskRunner() throws Exception {
        assertNotNull(ExportDialog.class.getMethod("show",
                ConnectionManager.class, String.class, TableRef.class,
                Window.class, FxTaskRunner.class));
    }
}
