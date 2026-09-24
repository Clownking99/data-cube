package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.*;
import com.datacube.spi.model.*;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DataGridExplicitSaveTest {
    @TempDir Path directory;
    @Test void leavingEditedRowOnlyMovesSelectionAndNeverStartsDatabaseWork() throws Exception {
        try(var probe=new WriteSafetyFxProbe(DbType.POSTGRESQL,false,"TEST");var runner=new FxTaskRunner()) {
            CountDownLatch loaded=new CountDownLatch(1);
            DataGridPane pane=FxUiTestSupport.call(()->{
                var p=new DataGridPane(new DataBrowseService(probe.manager()),new DataEditService(probe.manager()),
                        "target","synthetic",new TableRef("synthetic","items"),new AppSettings(directory.resolve("settings")),false,runner);
                grid(p).itemsProperty().addListener((o,a,b)->loaded.countDown());return p;
            });
            assertTrue(loaded.await(5,TimeUnit.SECONDS));
            try {
                FxUiTestSupport.call(()->{
                    var grid=grid(pane);var model=(EditableGridModel)field(pane,"model");
                    var row=grid.getItems().getFirst();var other=model.toRow(List.of(2));grid.getItems().add(other);
                    grid.getSelectionModel().clearAndSelect(0);
                    row.cell(0).setText("11");row.setState(EditableGridModel.RowState.MODIFIED);
                    grid.getSelectionModel().clearAndSelect(1);
                    assertFalse((boolean)field(pane,"busy"),"moving focus must not start a write");
                    assertTrue(row.dirty());assertSame(other,grid.getSelectionModel().getSelectedItem());return null;
                });
                assertEquals(0,probe.writes());
            } finally {FxUiTestSupport.call(()->{pane.closeResources();pane.finalizeCloseOnFx();return null;});}
        }
    }
    static Object field(Object owner,String name) throws Exception {var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    @SuppressWarnings("unchecked") static TableView<EditableGridModel.Row> grid(DataGridPane pane) throws Exception {
        return (TableView<EditableGridModel.Row>)field(pane,"grid");
    }
}
