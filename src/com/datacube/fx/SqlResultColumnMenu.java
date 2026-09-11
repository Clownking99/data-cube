package com.datacube.fx;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javafx.collections.ObservableList;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;

/** Controls visible result columns without changing result rows or their order. */
final class SqlResultColumnMenu implements AutoCloseable {
    private final TableView<ObservableList<Object>> table;
    private final MenuButton menu = new MenuButton("列（0/0）");
    private boolean available;
    private boolean closed;
    private final BooleanSupplier navigationAllowed;
    private ResultColumnFindDialog finder;

    SqlResultColumnMenu(TableView<ObservableList<Object>> table) {
        this(table, () -> true);
    }

    SqlResultColumnMenu(TableView<ObservableList<Object>> table, BooleanSupplier navigationAllowed) {
        this.table = Objects.requireNonNull(table);
        this.navigationAllowed = Objects.requireNonNull(navigationAllowed);
        menu.setId("sql-result-columns");
        menu.setAccessibleText("查找、显示或隐藏结果列");
        menu.setTooltip(new Tooltip("查找定位或调整当前结果的可见列；导出仅包含可见列。至少保留一列。"));
        menu.setOnShowing(event -> rebuild());
        refresh(false);
    }

    MenuButton getNode() { return menu; }

    void refresh(boolean available) {
        menu.hide();
        this.available = available && !closed;
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
            MenuItem find = new MenuItem("查找列…");
            find.setId("sql-result-columns-find");
            find.setOnAction(event -> {
                if (available && columns().equals(captured)) showFinder();
            });
            // Keep navigation reachable before a potentially long list of column toggles.
            menu.getItems().addAll(0, List.of(find, new SeparatorMenuItem()));
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

    private void showFinder() {
        if (closed || finder != null || !available || menu.isDisabled() || table.isDisabled()
                || !navigationAllowed.getAsBoolean()) return;
        var dialog = new ResultColumnFindDialog(table, table.getScene() == null ? null : table.getScene().getWindow(),
                () -> !closed && available && !menu.isDisabled() && navigationAllowed.getAsBoolean());
        finder = dialog;
        dialog.setOnHidden(event -> { dialog.dispose(); if (finder == dialog) finder = null; updateState(); });
        try { dialog.show(); }
        catch (RuntimeException failure) { dialog.dispose(); finder = null; throw failure; }
    }

    @Override public void close() {
        closed = true; available = false;
        if (finder != null) { finder.close(); if (finder != null) finder.dispose(); finder = null; }
        menu.hide(); updateState();
    }
}
