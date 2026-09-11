package com.datacube.fx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Explicit horizontal navigation over existing column identities, without reading any result rows. */
final class ResultColumnFindDialog extends Dialog<Boolean> {
    static final int MAX_QUERY_LENGTH = 256;
    static final int MAX_CANDIDATES = 200;
    private final TableView<ObservableList<Object>> table;
    private final BooleanSupplier navigationAllowed;
    private final List<TableColumn<ObservableList<Object>, ?>> columns;
    private final TextField query = new TextField();
    private final ListView<TableColumn<ObservableList<Object>, ?>> list = new ListView<>();
    private final Label status = label("result-column-status", "");
    private final Button locate;
    private final ListChangeListener<TableColumn<ObservableList<Object>, ?>> changed = change -> invalidate();
    private boolean stale;
    private boolean disposed;
    private boolean selectedWasVisible;

    ResultColumnFindDialog(TableView<ObservableList<Object>> table, Window owner, BooleanSupplier navigationAllowed) {
        this.table = Objects.requireNonNull(table);
        this.navigationAllowed = Objects.requireNonNull(navigationAllowed);
        columns = table.getColumns().stream()
                .filter(column -> column.getUserData() instanceof Integer position && position >= 0).toList();
        setTitle("查找结果列"); setHeaderText(null); setResizable(true);
        if (owner != null) {
            initOwner(owner);
            if (owner.getScene() != null) getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        query.setId("result-column-query"); query.setPromptText("按列名查找（含隐藏列，不搜索数据）");
        list.setId("result-column-list"); list.setPlaceholder(new Label("没有匹配的结果列"));
        list.setCellFactory(ignored -> new ListCell<>() {
            @Override protected void updateItem(TableColumn<ObservableList<Object>, ?> column, boolean empty) {
                super.updateItem(column, empty);
                setText(empty || column == null ? null : "原列 " + ((Integer) column.getUserData() + 1) + " · "
                        + displayLabel(column) + (column.isVisible() ? "" : "（隐藏）"));
                setGraphic(null);
            }
        });
        var hint = label("result-column-hint", "Enter 确认，Esc 取消；编号为原始结果列号。仅横向定位，不改变行选择。\n"
                + "隐藏列需“显示并定位”，显示后会进入可见列导出范围。不查询数据库。");
        var content = new VBox(8, query, list, status, hint);
        content.setPadding(new Insets(12)); content.setPrefSize(600, 380); content.setMinWidth(320);
        list.setMinHeight(80); VBox.setVgrow(list, Priority.ALWAYS);
        getDialogPane().setContent(content);
        var locateType = new ButtonType("定位", ButtonBar.ButtonData.OK_DONE);
        var cancelType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(locateType, cancelType);
        locate = (Button) getDialogPane().lookupButton(locateType); locate.setId("result-column-locate");
        // This action changes label after selection; do not freeze it to the initial uniform width.
        ButtonBar.setButtonUniformSize(locate, false);
        locate.setMinWidth(Region.USE_PREF_SIZE);
        locate.addEventFilter(ActionEvent.ACTION, event -> { if (!locateSelected()) event.consume(); });
        var cancel = (Button) getDialogPane().lookupButton(cancelType); cancel.setId("result-column-cancel");
        list.getSelectionModel().selectedItemProperty().addListener((o, before, after) -> updateAction());
        query.textProperty().addListener((o, before, after) -> filter());
        content.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() || event.isAltDown() || event.isMetaDown() || event.isShiftDown()) return;
            if (event.getCode() == KeyCode.ENTER) { event.consume(); locate.fire(); }
            else if (event.getCode() == KeyCode.ESCAPE) { event.consume(); cancel.fire(); }
            else if (event.getTarget() == query && (event.getCode() == KeyCode.DOWN || event.getCode() == KeyCode.UP)) {
                event.consume();
                if (!list.getItems().isEmpty()) {
                    int next = list.getSelectionModel().getSelectedIndex() + (event.getCode() == KeyCode.DOWN ? 1 : -1);
                    next = Math.max(0, Math.min(list.getItems().size() - 1, next));
                    list.getSelectionModel().select(next); list.scrollTo(next);
                }
            }
        });
        setResultConverter(button -> button == locateType ? Boolean.TRUE : null);
        setOnShown(event -> query.requestFocus());
        setOnHidden(event -> dispose());
        table.getColumns().addListener(changed);
        filter();
    }

    private void filter() {
        if (disposed || stale) return;
        String raw = Objects.requireNonNullElse(query.getText(), "");
        if (raw.length() > MAX_QUERY_LENGTH) {
            list.getItems().clear(); status.setText("查找词过长：最多 256 个 UTF-16 单元，请缩短后重试。");
            updateAction(); return;
        }
        String needle = raw.strip().toLowerCase(Locale.ROOT);
        var matches = new ArrayList<TableColumn<ObservableList<Object>, ?>>();
        int count = 0;
        for (var column : columns) {
            if (name(column).toLowerCase(Locale.ROOT).contains(needle)) {
                count++;
                if (matches.size() < MAX_CANDIDATES) matches.add(column);
            }
        }
        list.getItems().setAll(matches);
        list.getSelectionModel().clearSelection();
        if (!matches.isEmpty()) list.getSelectionModel().selectFirst();
        status.setText("匹配 " + count + " / " + columns.size() + " 列"
                + (count > MAX_CANDIDATES ? "；仅列出前 200 项，请继续输入缩小范围。" : ""));
        updateAction();
    }

    private void updateAction() {
        var selected = list.getSelectionModel().getSelectedItem();
        selectedWasVisible = selected != null && selected.isVisible();
        locate.setText(selected == null || selectedWasVisible ? "定位" : "显示并定位");
        locate.setDisable(disposed || stale || selected == null);
    }

    private boolean locateSelected() {
        var selected = list.getSelectionModel().getSelectedItem();
        if (disposed || stale || selected == null || !list.getItems().contains(selected)
                || !table.getColumns().contains(selected) || table.isDisabled() || !navigationAllowed.getAsBoolean()) {
            status.setText("当前结果已变化或暂不可定位，请关闭后重试。"); return false;
        }
        if (selected.isVisible() != selectedWasVisible) {
            updateAction(); list.refresh();
            status.setText("列显示状态已改变，请再次确认操作。"); return false;
        }
        if (!selected.isVisible()) selected.setVisible(true);
        // Realize a newly shown header before scrolling; the skin otherwise defers a missing header.
        table.applyCss();
        table.layout();
        table.scrollToColumn(selected);
        return true;
    }

    private void invalidate() {
        stale = true; list.getItems().clear(); query.setDisable(true); updateAction();
        status.setText("结果列已变化，请关闭后重新打开查找。");
    }

    void dispose() {
        if (disposed) return;
        disposed = true; table.getColumns().removeListener(changed);
        list.getItems().clear(); query.setDisable(true); updateAction();
    }

    private static String name(TableColumn<?, ?> column) {
        return Objects.toString(column.getProperties().get("sql-result-label"), Objects.toString(column.getText(), ""));
    }

    private static String displayLabel(TableColumn<?, ?> column) {
        String text = name(column);
        int end = Math.min(512, text.length());
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end).replaceAll("[\\p{Cntrl}\\u0085\\u2028\\u2029]", " ")
                + (end < text.length() ? "…" : "");
    }

    private static Label label(String id, String text) {
        var label = new Label(text); label.setId(id); label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE); return label;
    }
}
