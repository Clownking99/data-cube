package com.datacube.fx;

import java.util.List;
import java.util.Objects;
import javafx.collections.ObservableList;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;

/** Controls visible result columns without changing result rows or their order. */
final class SqlResultColumnMenu {
    private final TableView<ObservableList<Object>> table;
    private final MenuButton menu = new MenuButton("列（0/0）");
    private boolean available;

    SqlResultColumnMenu(TableView<ObservableList<Object>> table) {
        this.table = Objects.requireNonNull(table);
        menu.setId("sql-result-columns");
        menu.setAccessibleText("显示或隐藏结果列");
        menu.setTooltip(new Tooltip("仅调整当前结果的可见列；导出仅包含可见列。至少保留一列。"));
        menu.setOnShowing(event -> rebuild());
        refresh(false);
    }

    MenuButton getNode() { return menu; }

    void refresh(boolean available) {
        menu.hide();
        this.available = available;
        rebuild();
    }

    private List<TableColumn<ObservableList<Object>, ?>> columns() {
        return table.getColumns().stream()
                .filter(column -> column.getUserData() instanceof Integer position && position >= 0)
                .toList();
    }

    private void rebuild() {
        menu.getItems().clear();
        if (available) {
            for (var column : columns()) {
                int position = (Integer) column.getUserData();
                String label = Objects.toString(column.getProperties().get("sql-result-label"), column.getText());
                CheckMenuItem item = new CheckMenuItem((position + 1) + " · " + label);
                item.setMnemonicParsing(false);
                item.setId("sql-result-column-" + position);
                item.setUserData(column);
                item.setOnAction(event -> {
                    if (!available || !columns().contains(column)) return;
                    long visible = columns().stream().filter(TableColumn::isVisible).count();
                    if (!column.isVisible() || visible > 1) column.setVisible(!column.isVisible());
                    updateState();
                });
                menu.getItems().add(item);
            }
            MenuItem all = new MenuItem("显示全部列");
            all.setId("sql-result-columns-show-all");
            List<TableColumn<ObservableList<Object>, ?>> captured = columns();
            all.setOnAction(event -> {
                if (!available || !columns().equals(captured)) return;
                captured.forEach(column -> column.setVisible(true));
                updateState();
            });
            menu.getItems().addAll(new SeparatorMenuItem(), all);
        }
        updateState();
    }

    private void updateState() {
        var current = available ? columns() : List.<TableColumn<ObservableList<Object>, ?>>of();
        long visible = current.stream().filter(TableColumn::isVisible).count();
        menu.setText("列（" + visible + "/" + current.size() + "）");
        menu.setDisable(current.isEmpty());
        for (var item : menu.getItems()) {
            if (item instanceof CheckMenuItem check && item.getUserData() instanceof TableColumn<?, ?> column) {
                check.setSelected(column.isVisible());
                check.setDisable(column.isVisible() && visible <= 1);
            } else if ("sql-result-columns-show-all".equals(item.getId())) {
                item.setDisable(visible == current.size());
            }
        }
    }
}
