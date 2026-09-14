package com.datacube.fx;

import com.datacube.sqleditor.SqlScriptExecutionReport;
import com.datacube.sqleditor.SqlScriptExecutionReport.Entry;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;

/** Owns only bounded display snapshots; table row identity survives sorting. */
final class SqlScriptDetails implements AutoCloseable {
    private final TableView<ObservableList<Object>> table;
    private final BooleanSupplier allowed;
    private final Map<ObservableList<Object>, Entry> entries = new IdentityHashMap<>();
    private final Button button = new Button("执行详情");
    private final Label notice = new Label();
    private final FlowPane bar = new FlowPane(8, 4, button, notice);
    private final ChangeListener<ObservableList<Object>> selection = (obs, before, after) -> refresh();
    private final EventHandler<KeyEvent> keys = event -> {
        if (event.getCode() == KeyCode.ENTER && !event.isShiftDown() && !event.isControlDown()
                && !event.isAltDown() && !event.isMetaDown() && selectedEntry() != null && canOpen()) {
            event.consume(); showSelected();
        }
    };
    private boolean closed;
    private SqlScriptDetailsDialog dialog;

    SqlScriptDetails(TableView<ObservableList<Object>> table, BooleanSupplier allowed) {
        this.table = table; this.allowed = allowed;
        bar.setId("sql-script-details-bar"); button.setId("sql-script-details");
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

    Entry selectedEntry() { return entries.get(table.getSelectionModel().getSelectedItem()); }

    private boolean canOpen() {
        return !closed && bar.isVisible() && !bar.isDisabled() && !table.isDisabled() && allowed.getAsBoolean();
    }

    void refresh() { button.setDisable(closed || selectedEntry() == null || !canOpen()); }

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
