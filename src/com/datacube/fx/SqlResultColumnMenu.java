package com.datacube.fx;

import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
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
    private record ColumnDefaults(int position, double prefWidth, double width, boolean visible) {}
    private final TableView<ObservableList<Object>> table;
    private final MenuButton menu = new MenuButton("列（0/0）");
    private boolean available;
    private boolean closed;
    private final BooleanSupplier navigationAllowed;
    private ResultColumnFindDialog finder;
    private List<TableColumn<ObservableList<Object>, ?>> initialColumns = List.of();
    private final Map<TableColumn<ObservableList<Object>, ?>, ColumnDefaults> defaults = new IdentityHashMap<>();
    private boolean awaitingInitialLayout;
    private javafx.scene.Scene sizingScene;
    private Runnable sizingPulse;
    private final javafx.beans.value.ChangeListener<javafx.scene.Scene> sceneListener =
            (observable, before, after) -> scheduleInitialWidths();

    SqlResultColumnMenu(TableView<ObservableList<Object>> table) {
        this(table, () -> true);
    }

    SqlResultColumnMenu(TableView<ObservableList<Object>> table, BooleanSupplier navigationAllowed) {
        this.table = Objects.requireNonNull(table);
        this.navigationAllowed = Objects.requireNonNull(navigationAllowed);
        menu.setId("sql-result-columns");
        menu.setAccessibleText("查找、显示、隐藏或恢复结果列布局");
        menu.setTooltip(new Tooltip("查找定位、显示隐藏或恢复本次查询初始列布局；导出仅包含可见列。至少保留一列。"));
        menu.setOnShowing(event -> rebuild());
        table.sceneProperty().addListener(sceneListener);
        refresh(false);
    }

    MenuButton getNode() { return menu; }

    void refresh(boolean available) {
        menu.hide();
        this.available = available && !closed;
        if (!this.available) clearDefaults();
        else if (!sameColumns()) {
            clearDefaults();
            // Finish the initial header auto-size before capturing actual widths. In JavaFX a
            // default-width header can auto-size without changing its preferred width.
            table.applyCss(); table.layout();
            initialColumns = List.copyOf(table.getColumns()); defaults.clear();
            for (int i = 0; i < initialColumns.size(); i++) {
                var column = initialColumns.get(i);
                defaults.put(column, new ColumnDefaults(i, column.getPrefWidth(), column.getWidth(), column.isVisible()));
            }
            // A background tab can receive a result while detached from the scene. Finalize
            // only its actual widths after the first visible layout, before user interaction.
            awaitingInitialLayout = table.getScene() == null;
            scheduleInitialWidths();
        }
        rebuild();
    }

    private void clearDefaults() {
        cancelInitialWidths(); awaitingInitialLayout = false;
        initialColumns = List.of(); defaults.clear();
    }

    private void cancelInitialWidths() {
        if (sizingScene != null && sizingPulse != null) sizingScene.removePostLayoutPulseListener(sizingPulse);
        sizingScene = null; sizingPulse = null;
    }

    private void scheduleInitialWidths() {
        cancelInitialWidths();
        if (!awaitingInitialLayout || table.getScene() == null) return;
        var expected = initialColumns;
        sizingScene = table.getScene();
        sizingPulse = new Runnable() {
            @Override public void run() {
                if (sizingPulse != this) return;
                cancelInitialWidths();
                if (closed || !available || initialColumns != expected || !sameColumns()) return;
                defaults.replaceAll((column, saved) ->
                        new ColumnDefaults(saved.position(), saved.prefWidth(), column.getWidth(), saved.visible()));
                awaitingInitialLayout = false;
                updateState();
            }
        };
        sizingScene.addPostLayoutPulseListener(sizingPulse);
        javafx.application.Platform.requestNextPulse();
    }

    private boolean sameColumns() {
        return table.getColumns().size() == defaults.size() && table.getColumns().stream().allMatch(defaults::containsKey);
    }

    private boolean canReset(List<TableColumn<ObservableList<Object>, ?>> expected) {
        return !closed && available && !awaitingInitialLayout && !menu.isDisabled() && !table.isDisabled()
                && navigationAllowed.getAsBoolean() && expected == initialColumns && sameColumns();
    }

    private boolean layoutChanged() {
        if (!sameColumns()) return false;
        if (!table.getColumns().equals(initialColumns)) return true;
        return initialColumns.stream().anyMatch(column -> {
            var saved = defaults.get(column);
            return column.isVisible() != saved.visible() || Math.abs(column.getPrefWidth() - saved.prefWidth()) > 0.01
                    || Math.abs(column.getWidth() - saved.width()) > 0.01;
        });
    }

    private void resetLayout(List<TableColumn<ObservableList<Object>, ?>> expected) {
        if (!canReset(expected) || !layoutChanged()) return;
        var rows = table.getItems();
        var selected = List.copyOf(table.getSelectionModel().getSelectedCells());
        var focused = table.getFocusModel().getFocusedCell();
        // Keep column identities and sort keys; JavaFX may still clear selection on visibility changes.
        javafx.collections.FXCollections.sort(table.getColumns(),
                java.util.Comparator.comparingInt(column -> defaults.get(column).position()));
        for (var column : initialColumns) {
            var saved = defaults.get(column);
            column.setVisible(saved.visible()); column.setPrefWidth(saved.prefWidth());
            // Native header resizing may change width without changing prefWidth.
            table.resizeColumn(column, saved.width() - column.getWidth());
        }
        if (table.getItems() == rows && canReset(expected)) {
            table.getSelectionModel().clearSelection();
            for (var cell : selected) {
                var column = layoutColumn(cell.getTableColumn());
                if (cell.getRow() >= 0 && cell.getRow() < rows.size()
                        && column != null && column.isVisible())
                    table.getSelectionModel().select(cell.getRow(), column);
            }
            var focusedColumn = layoutColumn(focused.getTableColumn());
            if (focused.getRow() >= 0 && focused.getRow() < rows.size()
                    && focusedColumn != null && focusedColumn.isVisible())
                table.getFocusModel().focus(focused.getRow(), focusedColumn);
            else table.getFocusModel().focus(-1);
        }
        updateState();
    }

    @SuppressWarnings("unchecked")
    private TableColumn<ObservableList<Object>, ?> layoutColumn(TableColumn<?, ?> column) {
        // JavaFX exposes raw selection positions; accept only identities from this typed table's baseline.
        return defaults.containsKey(column) ? (TableColumn<ObservableList<Object>, ?>) column : null;
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
            MenuItem reset = new MenuItem("恢复列布局");
            reset.setId("sql-result-columns-reset");
            var expected = initialColumns;
            reset.setOnAction(event -> resetLayout(expected));
            menu.getItems().addAll(0, List.of(find, reset, new SeparatorMenuItem()));
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
            } else if ("sql-result-columns-reset".equals(item.getId())) {
                item.setDisable(!canReset(initialColumns) || !layoutChanged());
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
        table.sceneProperty().removeListener(sceneListener);
        clearDefaults();
        if (finder != null) { finder.close(); if (finder != null) finder.dispose(); finder = null; }
        menu.hide(); updateState();
    }
}
