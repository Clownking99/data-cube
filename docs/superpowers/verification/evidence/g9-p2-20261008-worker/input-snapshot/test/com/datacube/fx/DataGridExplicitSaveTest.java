package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.*;
import com.datacube.spi.model.*;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DataGridExplicitSaveTest {
    @TempDir Path directory;
    @ParameterizedTest
    @CsvSource({"480,dark", "760,dark", "760,light", "1200,light"})
    void pagingActionsRemainLegibleWithinNarrowAndWidePanels(double width, String theme) throws Exception {
        try (var probe = new GridSaveProbe(DbType.POSTGRESQL, false, "PRODUCTION"); var runner = new FxTaskRunner()) {
            DataGridPane pane = FxUiTestSupport.call(() -> new DataGridPane(new DataBrowseService(probe.manager), probe.edit,
                    "target", "synthetic-connection", new TableRef("synthetic", "items"),
                    new AppSettings(directory.resolve("settings")), false, runner));
            try {
                FxUiTestSupport.call(() -> {
                    var root = (javafx.scene.layout.Region) pane.getNode();
                    var scene = new javafx.scene.Scene(root, width, 800);
                    scene.getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                            ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                    root.resize(width, 800); root.applyCss(); root.layout();
                    for (String name : List.of("reloadBtn", "prevBtn", "nextBtn", "addBtn", "deleteBtn")) {
                        var button = (Button) field(pane, name);
                        var bounds = button.localToScene(button.getLayoutBounds());
                        assertTrue(button.getWidth() + 1 >= button.prefWidth(-1), name + " must show its complete action label");
                        assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= width + 1, name + " must remain inside the panel");
                        assertTrue(bounds.getMaxY() <= grid(pane).localToScene(grid(pane).getLayoutBounds()).getMinY() + 1,
                                name + " must not overlap the data rows");
                    }
                    assertTrue(grid(pane).getHeight() >= 100, "wrapping actions must leave usable data space");
                    return null;
                });
                assertEquals(0, probe.jdbc.executes.get(), "resizing must never write data");
                assertEquals(0, probe.jdbc.commits.get());
            } finally {
                pane.closeResources();
                FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
            }
        }
    }
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
