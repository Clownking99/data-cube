package com.datacube.fx;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ResultRowLocateDialogTest {
    @ParameterizedTest @CsvSource({"1,0", "' 003 ',2", "150,149"})
    void confirmingSelectsExactDisplayRowAndCapturedDuplicateColumn(String input, int index) throws Exception {
        FxUiTestSupport.call(() -> {
            var table = table(150); var id = table.getColumns().get(1); var other = table.getColumns().get(2);
            table.getColumns().setAll(List.of(table.getColumns().getFirst(), other, id));
            table.getSelectionModel().select(3, id); table.getSelectionModel().select(5, other);
            table.getFocusModel().focus(3, id);
            var parent = new VBox(table); new Scene(parent, 500, 280); parent.applyCss(); parent.layout();
            var before = List.copyOf(table.getSelectionModel().getSelectedCells()); var rows = List.copyOf(table.getItems());
            var scrolled = new AtomicInteger(-1);
            table.addEventFilter(javafx.scene.control.ScrollToEvent.scrollToTopIndex(), e -> scrolled.set(e.getScrollTarget()));
            var d = new ResultRowLocateDialog(table, null, (r, c) -> true);
            try {
                d.show(); assertEquals("4", query(d).getText()); assertEquals("4", query(d).getSelectedText());
                query(d).setText(input); assertEquals(before, table.getSelectionModel().getSelectedCells()); assertEquals(-1, scrolled.get());
                query(d).fireEvent(key(KeyCode.ENTER)); assertFalse(d.isShowing()); assertEquals(Boolean.TRUE, d.getResult());
                assertEquals(index, table.getFocusModel().getFocusedCell().getRow()); assertSame(id, table.getFocusModel().getFocusedCell().getTableColumn());
                assertEquals(1, table.getSelectionModel().getSelectedCells().size()); assertTrue(table.getSelectionModel().isSelected(index, id));
                assertEquals(index, scrolled.get()); assertSame(rows.get(index), table.getItems().get(index));
                assertEquals(List.of(other, id), table.getColumns().subList(1, 3)); assertEquals(160, id.getPrefWidth());
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "0", "-1", "+2", "2.5", "1e2", "３", "151", "9999999999", "00000000001"})
    void invalidNumbersDoNotNavigateOrCloseAndCanRecover(String input) throws Exception {
        FxUiTestSupport.call(() -> {
            var table = table(150); table.getSelectionModel().clearAndSelect(2, table.getColumns().get(1));
            var before = List.copyOf(table.getSelectionModel().getSelectedCells()); var called = new AtomicInteger();
            var d = new ResultRowLocateDialog(table, null, (r, c) -> { called.incrementAndGet(); return true; });
            try {
                d.show(); query(d).setText(input); assertTrue(confirm(d).isDisabled());
                confirm(d).fireEvent(new javafx.event.ActionEvent()); query(d).fireEvent(key(KeyCode.ENTER));
                assertTrue(d.isShowing()); assertNull(d.getResult()); assertEquals(0, called.get());
                assertEquals(before, table.getSelectionModel().getSelectedCells()); assertTrue(status(d).contains("1–150"));
                query(d).setText("100"); confirm(d).fire(); assertEquals(99, table.getFocusModel().getFocusedCell().getRow()); assertEquals(1, called.get());
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @CsvSource({"32,true", "33,false"})
    void rawLengthLimitRejectsRatherThanSilentlyTrimmingExcess(int length, boolean valid) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = new ResultRowLocateDialog(table(150), null, (r, c) -> true);
            try { d.show(); query(d).setText(" ".repeat(length - 1) + "2"); assertEquals(!valid, confirm(d).isDisabled()); }
            finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"none", "sequence", "hidden"})
    void invalidColumnFocusFallsBackToFirstVisibleDataColumn(String focus) throws Exception {
        FxUiTestSupport.call(() -> {
            var table = table(4); var first = table.getColumns().get(1); var second = table.getColumns().get(2);
            table.getColumns().setAll(List.of(table.getColumns().getFirst(), second, first));
            if (focus.equals("hidden")) { first.setVisible(false); table.getFocusModel().focus(0, first); }
            else if (focus.equals("sequence")) table.getFocusModel().focus(0, table.getColumns().getFirst());
            else table.getFocusModel().focus(-1);
            var d = new ResultRowLocateDialog(table, null, (r, c) -> true);
            try {
                d.show(); query(d).setText("3"); confirm(d).fire();
                assertTrue(table.getSelectionModel().isSelected(2, second)); assertSame(second, table.getFocusModel().getFocusedCell().getTableColumn());
                assertEquals(!focus.equals("hidden"), first.isVisible());
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"replace", "remove", "reorder", "hide", "columns", "denied", "disabled", "closed"})
    void changedProjectionOrDeniedContextNeverUsesOldRowNumbers(String state) throws Exception {
        FxUiTestSupport.call(() -> {
            var table = table(150); var allowed = new AtomicBoolean(true); var scrolled = new AtomicInteger();
            table.addEventHandler(javafx.scene.control.ScrollToEvent.scrollToTopIndex(), e -> scrolled.incrementAndGet());
            var d = new ResultRowLocateDialog(table, null, (r, c) -> allowed.get());
            try {
                d.show(); query(d).setText("120");
                switch (state) {
                    case "replace" -> table.setItems(FXCollections.observableArrayList(table.getItems()));
                    case "remove" -> table.getItems().removeLast();
                    case "reorder" -> FXCollections.reverse(table.getItems());
                    case "hide" -> table.getColumns().get(1).setVisible(false);
                    case "columns" -> java.util.Collections.swap(table.getColumns(), 1, 2);
                    case "denied" -> allowed.set(false);
                    case "disabled" -> table.setDisable(true);
                    case "closed" -> d.close();
                    default -> throw new AssertionError(state);
                }
                var before = List.copyOf(table.getSelectionModel().getSelectedCells());
                confirm(d).fireEvent(new javafx.event.ActionEvent());
                assertEquals(0, scrolled.get()); assertEquals(before, table.getSelectionModel().getSelectedCells()); assertNull(d.getResult());
                assertTrue(confirm(d).isDisabled()); assertEquals(!state.equals("closed"), d.isShowing());
                if (!state.equals("closed")) assertTrue(status(d).contains("重新定位"));
            } finally { d.close(); }
            return null;
        });
    }

    @Test void cancelAndHiddenCallbackReleaseListenersWithoutMovingSelection() throws Exception {
        FxUiTestSupport.call(() -> {
            var table = table(150); table.getSelectionModel().clearAndSelect(2, table.getColumns().get(1));
            var selection = List.copyOf(table.getSelectionModel().getSelectedCells()); var hidden = new AtomicBoolean();
            var d = new ResultRowLocateDialog(table, null, (r, c) -> true); d.setOnHidden(e -> hidden.set(true));
            d.show(); query(d).setText("100"); query(d).fireEvent(key(KeyCode.ESCAPE));
            assertTrue(hidden.get()); assertNull(d.getResult()); assertEquals(selection, table.getSelectionModel().getSelectedCells());
            String before = status(d); table.getItems().clear(); table.getColumns().clear(); query(d).setText("3");
            assertEquals(before, status(d)); confirm(d).fireEvent(new javafx.event.ActionEvent()); assertNull(d.getResult());
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "null-items", "all-hidden"})
    void emptyRowsOrNoDataColumnCannotNavigate(String state) throws Exception {
        FxUiTestSupport.call(() -> {
            var table = table(state.equals("empty") ? 0 : 4);
            if (state.equals("null-items")) table.setItems(null);
            if (state.equals("all-hidden")) table.getColumns().stream().filter(c -> c.getUserData() instanceof Integer i && i >= 0).forEach(c -> c.setVisible(false));
            var d = new ResultRowLocateDialog(table, null, (r, c) -> { fail("no navigation without a visible data cell"); return true; });
            try { d.show(); assertTrue(confirm(d).isDisabled()); confirm(d).fireEvent(new javafx.event.ActionEvent()); assertNull(d.getResult()); }
            finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @CsvSource({"480,dark", "720,dark", "480,light", "720,light"})
    void dialogLayoutAndRealScrollWorkInBothThemes(double width, String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            var table = table(150); table.setPrefHeight(240);
            var stage = new Stage(); var parent = new VBox(table); var scene = new Scene(parent, 500, 280); stage.setScene(scene);
            scene.getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                    ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
            var d = new ResultRowLocateDialog(table, stage, (r, c) -> true);
            try {
                stage.show(); parent.applyCss(); parent.layout(); d.show(); d.setWidth(width); d.setHeight(350);
                query(d).clear(); var root = d.getDialogPane(); root.applyCss(); root.layout();
                for (String suffix : List.of("range", "query", "status", "hint", "confirm", "cancel")) {
                    var node = (javafx.scene.layout.Region) root.lookup("#result-row-locate-" + suffix); var b = node.localToScene(node.getLayoutBounds());
                    assertTrue(b.getMinX() >= -1 && b.getMaxX() <= root.getWidth() + 1, suffix);
                    assertTrue(b.getMinY() >= -1 && b.getMaxY() <= root.getHeight() + 1, suffix);
                    if (node instanceof Label) assertTrue(node.getHeight() + 1 >= node.prefHeight(node.getWidth()), suffix);
                }
                query(d).pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), true); root.applyCss();
                var prompt = query(d).lookupAll(".text").stream().filter(javafx.scene.text.Text.class::isInstance).map(javafx.scene.text.Text.class::cast)
                        .filter(t -> query(d).getPromptText().equals(t.getText())).findFirst().orElseThrow();
                assertTrue(prompt.isVisible()); assertEquals(javafx.scene.paint.Color.web(theme.equals("dark") ? "#A8A8B8" : "#555555"), prompt.getFill());
                var bar = table.lookupAll(".scroll-bar").stream().filter(javafx.scene.control.ScrollBar.class::isInstance)
                        .map(javafx.scene.control.ScrollBar.class::cast).filter(b -> b.getOrientation() == javafx.geometry.Orientation.VERTICAL).findFirst().orElseThrow();
                double before = bar.getValue(); query(d).setText("145"); confirm(d).fire(); parent.applyCss(); parent.layout(); table.layout();
                assertTrue(bar.getValue() > before, "confirmation must scroll the actual table skin");
                assertTrue(table.lookupAll(".table-row-cell").stream().filter(javafx.scene.control.TableRow.class::isInstance)
                        .map(javafx.scene.control.TableRow.class::cast).anyMatch(row -> row.getIndex() == 144 && row.isVisible()));
            } finally { d.close(); stage.close(); }
            return null;
        });
    }

    static TableView<ObservableList<Object>> table(int rows) {
        var table = new TableView<ObservableList<Object>>(); table.setFixedCellSize(26);
        table.getSelectionModel().setCellSelectionEnabled(true); table.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        var seq = new TableColumn<ObservableList<Object>, String>("#"); seq.setUserData(-1); table.getColumns().add(seq);
        for (int index = 0; index < 2; index++) {
            final int source = index; var column = new TableColumn<ObservableList<Object>, String>("same"); column.setUserData(index); column.setPrefWidth(160);
            column.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(String.valueOf(cell.getValue().get(source)))); table.getColumns().add(column);
        }
        for (int row = 0; row < rows; row++) table.getItems().add(FXCollections.observableArrayList(row + 1, "value-" + (row + 1)));
        return table;
    }
    static TextField query(ResultRowLocateDialog d) { return (TextField) d.getDialogPane().lookup("#result-row-locate-query"); }
    static Button confirm(ResultRowLocateDialog d) { return (Button) d.getDialogPane().lookup("#result-row-locate-confirm"); }
    static String status(ResultRowLocateDialog d) { return ((Label) d.getDialogPane().lookup("#result-row-locate-status")).getText(); }
    private static KeyEvent key(KeyCode code) { return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false); }
}
