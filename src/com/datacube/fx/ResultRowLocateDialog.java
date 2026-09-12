package com.datacube.fx;

import java.util.Objects;
import java.util.function.BiPredicate;
import javafx.beans.InvalidationListener;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Explicit navigation in a fixed displayed projection; no result values are copied or fetched. */
final class ResultRowLocateDialog extends Dialog<Boolean> {
    private final TableView<ObservableList<Object>> table;
    private final ObservableList<ObservableList<Object>> rows;
    private final int rowCount;
    private final TableColumn<ObservableList<Object>, ?> column;
    private final BiPredicate<Integer, TableColumn<ObservableList<Object>, ?>> navigationAllowed;
    private final TextField query = new TextField();
    private final Label status = label("result-row-locate-status", "");
    private final Button locate;
    private final InvalidationListener changed = ignored -> invalidate();
    private final ListChangeListener<ObservableList<Object>> rowsChanged = change -> invalidate();
    private final ListChangeListener<TableColumn<ObservableList<Object>, ?>> columnsChanged = change -> invalidate();
    private boolean stale;
    private boolean disposed;

    ResultRowLocateDialog(TableView<ObservableList<Object>> table, Window owner,
                         BiPredicate<Integer, TableColumn<ObservableList<Object>, ?>> navigationAllowed) {
        this.table = Objects.requireNonNull(table);
        this.navigationAllowed = Objects.requireNonNull(navigationAllowed);
        rows = table.getItems(); rowCount = rows == null ? 0 : rows.size();
        var dataColumns = table.getVisibleLeafColumns().stream()
                .filter(c -> c.getUserData() instanceof Integer i && i >= 0).toList();
        var focused = table.getFocusModel().getFocusedCell();
        column = dataColumns.stream().filter(c -> c == focused.getTableColumn()).findFirst()
                .orElse(dataColumns.isEmpty() ? null : dataColumns.getFirst());
        setTitle("定位到结果行"); setHeaderText(null); setResizable(true);
        if (owner != null) {
            initOwner(owner);
            if (owner.getScene() != null) getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        var range = label("result-row-locate-range", "当前显示 " + rowCount + " 行" + (rowCount > 0 ? " · 可定位 1–" + rowCount : ""));
        query.setId("result-row-locate-query"); query.setAccessibleText("目标显示行号");
        query.setPromptText("输入当前显示行号");
        query.setText(Integer.toString(focused.getRow() >= 0 && focused.getRow() < rowCount ? focused.getRow() + 1 : 1));
        var hint = label("result-row-locate-hint", "行号按当前筛选、排序后的显示顺序计算，不是主键或数据库总行数。\n"
                + "定位会替换原选区，选中目标行的当前可见焦点列；没有时选择第一个可见数据列。\n"
                + "只定位已显示数据，不拉取更多行。结果变化后须重开。Enter 定位，Esc 取消。");
        var content = new VBox(10, range, query, status, hint);
        content.setPrefWidth(520); content.setMinWidth(300); getDialogPane().setContent(content);
        var locateType = new ButtonType("定位", ButtonBar.ButtonData.OK_DONE);
        var cancelType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(locateType, cancelType);
        locate = (Button) getDialogPane().lookupButton(locateType); locate.setId("result-row-locate-confirm");
        locate.addEventFilter(ActionEvent.ACTION, event -> { if (!locateRow()) event.consume(); });
        var cancel = (Button) getDialogPane().lookupButton(cancelType); cancel.setId("result-row-locate-cancel");
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() || event.isAltDown() || event.isMetaDown() || event.isShiftDown()) return;
            if (event.getCode() == KeyCode.ENTER) { event.consume(); locate.fire(); }
            else if (event.getCode() == KeyCode.ESCAPE) { event.consume(); cancel.fire(); }
        });
        query.textProperty().addListener(ignored -> validateInput());
        setResultConverter(button -> button == locateType ? Boolean.TRUE : null);
        setOnShown(event -> { query.requestFocus(); query.selectAll(); });
        showingProperty().addListener((obs, before, showing) -> { if (!showing) dispose(); });
        table.itemsProperty().addListener(changed);
        if (rows != null) rows.addListener(rowsChanged);
        table.getVisibleLeafColumns().addListener(columnsChanged);
        validateInput();
    }

    private int targetIndex() {
        String raw = query.getText();
        if (raw == null || raw.length() > 32) return -1;
        String number = raw.strip();
        if (number.isEmpty() || number.length() > 10) return -1;
        long value = 0;
        for (int i = 0; i < number.length(); i++) {
            char digit = number.charAt(i);
            if (digit < '0' || digit > '9') return -1;
            value = value * 10 + digit - '0';
        }
        return value >= 1 && value <= rowCount ? (int) value - 1 : -1;
    }

    private void validateInput() {
        if (disposed || stale) return;
        int target = targetIndex();
        locate.setDisable(target < 0 || column == null);
        status.setText(rowCount == 0 ? "没有可定位的显示行。" : column == null ? "没有可见数据列，请关闭后显示列。"
                : target < 0 ? "请输入 1–" + rowCount + " 的十进制整数（最多 10 位，输入最多 32 个字符）。"
                : "将定位到当前显示第 " + (target + 1) + " 行。");
    }

    private boolean locateRow() {
        if (disposed || stale) return false;
        int target = targetIndex();
        if (target < 0 || column == null) { validateInput(); return false; }
        if (table.getItems() != rows || rows.size() != rowCount || table.isDisabled()
                || !table.getVisibleLeafColumns().contains(column) || !navigationAllowed.test(target, column)) {
            invalidate(); return false;
        }
        table.getSelectionModel().clearAndSelect(target, column);
        table.getFocusModel().focus(target, column);
        table.scrollTo(target); table.scrollToColumn(column); table.requestFocus();
        return true;
    }

    private void invalidate() {
        if (disposed) return;
        stale = true; query.setDisable(true); locate.setDisable(true);
        status.setText("结果顺序、列或可用状态已变化，请关闭后重新定位。");
    }

    void dispose() {
        if (disposed) return;
        disposed = true;
        table.itemsProperty().removeListener(changed);
        if (rows != null) rows.removeListener(rowsChanged);
        table.getVisibleLeafColumns().removeListener(columnsChanged);
        query.setDisable(true); locate.setDisable(true);
    }

    private static Label label(String id, String text) {
        var label = new Label(text); label.setId(id); label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE); label.setMaxWidth(Double.MAX_VALUE); return label;
    }
}
