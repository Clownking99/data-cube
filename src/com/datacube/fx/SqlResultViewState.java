package com.datacube.fx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

/** Value-free table state, keyed by source positions rather than equal row/column values. */
final class SqlResultViewState {
    private record Column(int source, double width, boolean visible, TableColumn.SortType sort) {}
    private record Cell(int row, int column) {}
    private final List<Column> columns = new ArrayList<>();
    private final List<Integer> sorting = new ArrayList<>();
    private final List<Cell> selection = new ArrayList<>();
    private Cell focus;
    private double horizontal;
    private double vertical;

    static SqlResultViewState capture(TableView<ObservableList<Object>> table,
            Map<ObservableList<Object>, Integer> sources) {
        var state = new SqlResultViewState();
        for (var column : table.getColumns()) state.columns.add(new Column(id(column),
                column.getWidth(), column.isVisible(), column.getSortType()));
        for (var column : table.getSortOrder()) state.sorting.add(id(column));
        for (var cell : table.getSelectionModel().getSelectedCells()) {
            if (cell.getRow() >= 0 && cell.getRow() < table.getItems().size()) {
                Integer source = sources.get(table.getItems().get(cell.getRow()));
                if (source != null) state.selection.add(new Cell(source, id(cell.getTableColumn())));
            }
        }
        var focused = table.getFocusModel().getFocusedCell();
        if (focused.getRow() >= 0 && focused.getRow() < table.getItems().size()) {
            Integer source = sources.get(table.getItems().get(focused.getRow()));
            if (source != null) state.focus = new Cell(source, id(focused.getTableColumn()));
        }
        for (var node : table.lookupAll(".scroll-bar")) if (node instanceof ScrollBar bar) {
            if (bar.getOrientation() == Orientation.HORIZONTAL) state.horizontal = bar.getValue();
            else state.vertical = bar.getValue();
        }
        return state;
    }

    void restore(TableView<ObservableList<Object>> table, Map<ObservableList<Object>, Integer> sources) {
        Map<Integer, TableColumn<ObservableList<Object>, ?>> byId = new HashMap<>();
        for (var column : table.getColumns()) byId.put(id(column), column);
        var ordered = new ArrayList<TableColumn<ObservableList<Object>, ?>>();
        for (var column : columns) {
            var target = byId.get(column.source());
            if (target == null) continue;
            target.setPrefWidth(column.width()); target.setVisible(column.visible()); target.setSortType(column.sort());
            ordered.add(target);
        }
        table.getColumns().setAll(ordered);
        table.getSortOrder().setAll(sorting.stream().map(byId::get).filter(java.util.Objects::nonNull).toList());
        table.sort();
        Map<Integer, Integer> visible = new HashMap<>();
        for (int i = 0; i < table.getItems().size(); i++) visible.put(sources.get(table.getItems().get(i)), i);
        table.getSelectionModel().clearSelection();
        for (var cell : selection) if (visible.containsKey(cell.row()))
            table.getSelectionModel().select(visible.get(cell.row()), byId.get(cell.column()));
        if (focus != null && visible.containsKey(focus.row()))
            table.getFocusModel().focus(visible.get(focus.row()), byId.get(focus.column()));
        restoreScroll(table);
    }

    void restoreScroll(TableView<?> table) {
        table.applyCss(); table.layout();
        for (var node : table.lookupAll(".scroll-bar")) if (node instanceof ScrollBar bar)
            bar.setValue(bar.getOrientation() == Orientation.HORIZONTAL ? horizontal : vertical);
    }

    private static int id(TableColumn<?, ?> column) {
        return column != null && column.getUserData() instanceof Integer source ? source : -1;
    }
}
