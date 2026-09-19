package com.datacube.fx;

import com.datacube.sqleditor.SqlScriptExecutionReport;
import com.datacube.sqleditor.SqlScriptExecutionReport.Entry;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.scene.control.Button;
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
    private final Label notice = new Label();
    private final FlowPane bar = new FlowPane(8, 4, result, button, notice);
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
        notice.setId("sql-script-details-notice"); notice.setWrapText(true);
        notice.setMaxWidth(460);
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
        table.setItems(rows);
        notice.setText(report.displayNotice()); bar.setVisible(true); bar.setManaged(true); refresh();
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

    void clear() {
        if (dialog != null) { dialog.close(); dialog = null; }
        entries.clear(); notice.setText(""); bar.setVisible(false); bar.setManaged(false); refresh();
    }

    @Override public void close() {
        if (closed) return;
        closed = true; clear();
        table.getSelectionModel().selectedItemProperty().removeListener(selection);
        table.removeEventHandler(KeyEvent.KEY_PRESSED, keys);
    }
}
