package com.datacube.fx;

import com.datacube.sqleditor.SqlScriptExecutionReport;
import com.datacube.sqleditor.SqlScriptExecutionReport.Entry;
import com.datacube.spi.model.QueryResult;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;

/** Owns only bounded display snapshots; table row identity survives sorting. */
final class SqlScriptDetails implements AutoCloseable {
    private final TableView<ObservableList<Object>> table;
    private final BooleanSupplier allowed;
    private final Consumer<Entry> selectResult;
    private final Map<ObservableList<Object>, Entry> entries = new IdentityHashMap<>();
    private final Button button = new Button("执行详情");
    private final Button result = new Button("查看结果");
    private final CheckBox onlyFailures = new CheckBox("仅看异常");
    private final Label count = new Label();
    private final Label notice = new Label();
    private final FlowPane bar = new FlowPane(8, 4, result, button, onlyFailures, count, notice);
    private List<ObservableList<Object>> sourceRows = List.of();
    private boolean filteringFailures;
    private final ChangeListener<ObservableList<Object>> selection = (obs, before, after) -> refresh();
    private final EventHandler<KeyEvent> keys = event -> {
        if (event.getCode() == KeyCode.ENTER && !event.isShiftDown() && !event.isControlDown()
                && !event.isAltDown() && !event.isMetaDown() && selectedEntry() != null && canOpen()) {
            event.consume(); showSelected();
        }
    };
    private boolean closed;
    private SqlScriptDetailsDialog dialog;

    SqlScriptDetails(TableView<ObservableList<Object>> table, BooleanSupplier allowed, Consumer<Entry> selectResult) {
        this.table = table; this.allowed = allowed; this.selectResult = selectResult;
        bar.setId("sql-script-details-bar"); button.setId("sql-script-details");
        result.setId("sql-script-result"); result.setMinWidth(Region.USE_PREF_SIZE);
        button.setMinWidth(Region.USE_PREF_SIZE);
        result.setTooltip(new Tooltip("查看所选语句已返回的数据、影响行数或错误，不重新执行 SQL。"));
        result.setOnAction(event -> {
            if (!canOpen()) return;
            Entry entry = selectedEntry();
            if (entry != null) selectResult.accept(entry);
        });
        onlyFailures.setId("sql-script-only-failures"); onlyFailures.setMinWidth(Region.USE_PREF_SIZE);
        onlyFailures.setTooltip(new Tooltip("只显示本次概览已保留的失败、超时和取消；不执行 SQL。离开概览后重置。"));
        onlyFailures.setOnAction(event -> {
            if (!canOpen()) { onlyFailures.setSelected(filteringFailures); return; }
            if (filteringFailures == onlyFailures.isSelected()) return;
            filteringFailures = onlyFailures.isSelected(); closeDetails(); applyFilter();
        });
        count.setId("sql-script-filter-count"); count.setWrapText(true); count.setMinHeight(Region.USE_PREF_SIZE);
        count.maxWidthProperty().bind(bar.widthProperty());
        notice.setId("sql-script-details-notice"); notice.setWrapText(true);
        notice.setMinHeight(Region.USE_PREF_SIZE);
        notice.maxWidthProperty().bind(javafx.beans.binding.Bindings.min(460, bar.widthProperty()));
        button.setOnAction(event -> showSelected());
        table.getSelectionModel().selectedItemProperty().addListener(selection);
        table.addEventHandler(KeyEvent.KEY_PRESSED, keys);
        clear();
    }

    FlowPane getNode() { return bar; }

    void display(SqlScriptExecutionReport report) {
        clear();
        if (closed) return;
        ObservableList<ObservableList<Object>> rows = FXCollections.observableArrayList();
        for (Entry entry : report.entries()) {
            ObservableList<Object> row = FXCollections.observableArrayList(entry.index(), entry.status(),
                    entry.elapsedMillis() + "ms", entry.summary());
            entries.put(row, entry); rows.add(row);
        }
        sourceRows = List.copyOf(rows);
        notice.setText(report.displayNotice()); bar.setVisible(true); bar.setManaged(true); applyFilter();
    }

    private void applyFilter() {
        var selectedRow = table.getSelectionModel().getSelectedItem();
        var sortOrder = List.copyOf(table.getSortOrder());
        var visible = sourceRows.stream().filter(row -> !filteringFailures
                || entries.get(row).kind() == QueryResult.Kind.ERROR).toList();
        table.getSelectionModel().clearSelection();
        table.setItems(FXCollections.observableArrayList(visible));
        // TableView clears sort order when its items list is replaced.
        table.getSortOrder().setAll(sortOrder); table.sort();
        // Do not let equal row values or their new visual positions retarget the selection.
        for (int i = 0; i < table.getItems().size(); i++) {
            if (table.getItems().get(i) == selectedRow) {
                table.getSelectionModel().clearAndSelect(i, table.getColumns().getFirst()); break;
            }
        }
        count.setText("显示 " + visible.size() + " / " + sourceRows.size() + " 条已保留结果"
                + (filteringFailures && visible.isEmpty() ? "；已保留结果中没有异常" : ""));
        refresh();
    }

    Entry selectedEntry() {
        var row = table.getSelectionModel().getSelectedItem();
        return row != null && table.getItems().stream().anyMatch(item -> item == row) ? entries.get(row) : null;
    }

    private boolean canOpen() {
        return !closed && bar.isVisible() && !bar.isDisabled() && !table.isDisabled() && allowed.getAsBoolean();
    }

    void refresh() {
        boolean disabled = selectedEntry() == null || !canOpen();
        button.setDisable(disabled); result.setDisable(disabled);
        onlyFailures.setDisable(!canOpen());
    }

    void showSelected() {
        if (!canOpen() || dialog != null) return;
        Entry entry = selectedEntry();
        if (entry == null) return;
        var candidate = new SqlScriptDetailsDialog(table.getScene() == null ? null : table.getScene().getWindow(), entry);
        if (!canOpen() || selectedEntry() != entry) return;
        dialog = candidate;
        candidate.setOnHidden(event -> { if (dialog == candidate) dialog = null; });
        try { candidate.show(); }
        catch (RuntimeException failure) { dialog = null; throw failure; }
    }

    private void closeDetails() {
        if (dialog != null) { dialog.close(); dialog = null; }
    }

    void clear() {
        closeDetails(); sourceRows = List.of(); filteringFailures = false; onlyFailures.setSelected(false); count.setText("");
        entries.clear(); notice.setText(""); bar.setVisible(false); bar.setManaged(false); refresh();
    }

    @Override public void close() {
        if (closed) return;
        closed = true; clear();
        table.getSelectionModel().selectedItemProperty().removeListener(selection);
        table.removeEventHandler(KeyEvent.KEY_PRESSED, keys);
    }
}
