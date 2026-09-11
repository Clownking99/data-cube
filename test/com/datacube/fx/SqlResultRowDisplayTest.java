package com.datacube.fx;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlResultRowDisplayTest {
    @ParameterizedTest @ValueSource(strings = {"dark", "light"})
    void actualRowsShrinkAndRestoreWithoutChangingSelectionOrData(String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            var table = new TableView<ObservableList<Object>>();
            var display = new SqlResultRowDisplay(table, () -> true);
            var column = column(display); table.getColumns().add(column);
            var row = FXCollections.<Object>observableArrayList("first\nsecond\nthird\nfourth");
            table.getItems().addAll(List.of(row, FXCollections.<Object>observableArrayList("plain")));
            table.getSelectionModel().setCellSelectionEnabled(true);
            table.getSelectionModel().clearAndSelect(0, column); table.getFocusModel().focus(0, column);
            var root = new VBox(display.getNode(), table);
            var stage = new Stage(); var scene = new Scene(root, 400, 280); stage.setScene(scene);
            scene.getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                    ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
            try {
                stage.show(); layout(root, table);
                assertFalse(display.getNode().isSelected());
                double expanded = cell(table, column).getHeight();
                assertEquals(row.getFirst(), cell(table, column).getText());
                display.refreshAvailability(true); display.getNode().fire(); layout(root, table);
                assertEquals("first ↵ …", cell(table, column).getText());
                assertTrue(cell(table, column).getHeight() < expanded, "compact mode must actually reduce the visible row height");
                assertTrue(table.getSelectionModel().isSelected(0, column));
                assertSame(column, table.getFocusModel().getFocusedCell().getTableColumn());
                assertSame(row, table.getItems().getFirst());
                assertEquals("first\nsecond\nthird\nfourth", row.getFirst());
                display.getNode().fire(); layout(root, table);
                assertEquals(row.getFirst(), cell(table, column).getText());
                assertEquals(expanded, cell(table, column).getHeight(), 1);
                assertTrue(table.getSelectionModel().isSelected(0, column));
            } finally { display.close(); stage.hide(); }
            return null;
        });
    }

    @Test void reusedCellsClearOldTextAndOnlyCompactDisplayNotTheirItems() throws Exception {
        FxUiTestSupport.call(() -> {
            var table = new TableView<ObservableList<Object>>();
            var display = new SqlResultRowDisplay(table, () -> true);
            var column = column(display); table.getColumns().add(column);
            for (Object value : Arrays.asList("long\nvalue", null, "", "NULL", 123))
                table.getItems().add(FXCollections.observableArrayList(Arrays.asList(value)));
            display.refreshAvailability(true); display.getNode().fire();
            var cell = display.createCell(); cell.updateTableView(table); cell.updateTableColumn(column);
            cell.updateIndex(0); assertEquals("long ↵ …", cell.getText()); assertEquals("long\nvalue", cell.getItem());
            cell.updateIndex(1); assertEquals("", cell.getText()); assertNull(cell.getItem()); assertFalse(cell.isEmpty());
            cell.updateIndex(2); assertEquals("", cell.getText()); assertEquals("", cell.getItem());
            cell.updateIndex(3); assertEquals("NULL", cell.getText());
            cell.updateIndex(4); assertEquals("123", cell.getText());
            cell.updateIndex(-1); assertTrue(cell.isEmpty()); assertNull(cell.getText()); assertNull(cell.getGraphic());
            display.close(); return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"unavailable", "guard", "table-disabled", "ancestor-disabled", "closed"})
    void staleActionsKeepActualModeAndOtherEditorsUnchanged(String state) throws Exception {
        FxUiTestSupport.call(() -> {
            var table = new TableView<ObservableList<Object>>(); var allowed = new AtomicBoolean(true);
            var display = new SqlResultRowDisplay(table, allowed::get);
            var other = new SqlResultRowDisplay(new TableView<>(), () -> true);
            assertTrue(display.getNode().isDisabled());
            display.refreshAvailability(true); display.getNode().fire(); assertTrue(display.getNode().isSelected());
            switch (state) {
                case "unavailable" -> display.refreshAvailability(false);
                case "guard" -> allowed.set(false);
                case "table-disabled" -> table.setDisable(true);
                case "ancestor-disabled" -> new VBox(display.getNode()).setDisable(true);
                case "closed" -> { display.close(); display.refreshAvailability(true); assertTrue(display.getNode().isDisabled()); }
                default -> throw new AssertionError(state);
            }
            display.getNode().setSelected(false);
            display.getNode().getOnAction().handle(new javafx.event.ActionEvent());
            assertTrue(display.getNode().isSelected());
            assertFalse(other.getNode().isSelected());
            display.close(); other.close(); return null;
        });
    }

    private static TableColumn<ObservableList<Object>, Object> column(SqlResultRowDisplay display) {
        var column = new TableColumn<ObservableList<Object>, Object>("value");
        column.setCellValueFactory(data -> new javafx.beans.property.SimpleObjectProperty<>(data.getValue().getFirst()));
        column.setCellFactory(ignored -> display.createCell()); column.setPrefWidth(300); return column;
    }
    private static TableCell<?, ?> cell(TableView<?> table, TableColumn<?, ?> column) {
        return table.lookupAll(".table-cell").stream().filter(TableCell.class::isInstance)
                .map(node -> (TableCell<?, ?>) node).filter(cell -> cell.getIndex() == 0 && cell.getTableColumn() == column)
                .findFirst().orElseThrow();
    }
    private static void layout(VBox root, TableView<?> table) { root.applyCss(); root.layout(); table.layout(); }
}
