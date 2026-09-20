package com.datacube.fx;

import com.datacube.sqleditor.SqlScriptExecutionReport;
import com.datacube.sqleditor.SqlScriptExecutionReport.Entry;
import com.datacube.spi.model.QueryResult;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

/** Owns only bounded display snapshots; table row identity survives sorting. */
final class SqlScriptDetails implements AutoCloseable {
    private static final int MAX_QUERY_UNITS = 256;
    /** Let TableView compare numeric evidence while keeping the existing cell text. */
    private record ElapsedMillis(long value) implements Comparable<ElapsedMillis> {
        @Override public int compareTo(ElapsedMillis other) { return Long.compare(value, other.value); }
        @Override public String toString() { return value + "ms"; }
    }

    private final TableView<ObservableList<Object>> table;
    private final BooleanSupplier allowed;
    private final Consumer<Entry> selectResult;
    private final Map<ObservableList<Object>, Entry> entries = new IdentityHashMap<>();
    private final Button button = new Button("执行详情");
    private final Button result = new Button("查看结果");
    private final CheckBox onlyFailures = new CheckBox("仅看异常");
    private final TextField query = new TextField();
    private final Button clearQuery = new Button("清除关键词");
    private final Button resetOrder = new Button("恢复执行顺序");
    private final HBox search = new HBox(4, query, clearQuery);
    private final Label count = new Label();
    private final Label notice = new Label();
    private final FlowPane bar = new FlowPane(8, 4, result, button, resetOrder, onlyFailures, search, count, notice);
    private List<ObservableList<Object>> sourceRows = List.of();
    private boolean filteringFailures;
    private boolean updatingQuery;
    private boolean queryRejected;
    private final ChangeListener<ObservableList<Object>> selection = (obs, before, after) -> refresh();
    private final InvalidationListener sorting = ignored -> refresh();
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
        bar.setId("sql-script-details-bar"); bar.setMinWidth(0); button.setId("sql-script-details");
        result.setId("sql-script-result"); result.setMinWidth(Region.USE_PREF_SIZE);
        button.setMinWidth(Region.USE_PREF_SIZE);
        result.setTooltip(new Tooltip("查看所选语句已返回的数据、影响行数或错误，不重新执行 SQL。"));
        result.setOnAction(event -> {
            if (!canOpen()) return;
            Entry entry = selectedEntry();
            if (entry != null) selectResult.accept(entry);
        });
        resetOrder.setId("sql-script-reset-order"); resetOrder.setMinWidth(Region.USE_PREF_SIZE);
        resetOrder.setTooltip(new Tooltip("清除表头排序，恢复本批次已保留记录的执行顺序；保留关键词、异常条件和所选语句，不重新执行 SQL。"));
        resetOrder.setOnAction(event -> {
            if (!canOpen() || !needsOrderReset()) return;
            closeDetails(); applyFilter(false);
        });
        onlyFailures.setId("sql-script-only-failures"); onlyFailures.setMinWidth(Region.USE_PREF_SIZE);
        onlyFailures.setTooltip(new Tooltip("只显示本次概览已保留的失败、超时和取消；不执行 SQL。离开概览后重置。"));
        onlyFailures.setOnAction(event -> {
            if (!canOpen()) { onlyFailures.setSelected(filteringFailures); return; }
            if (filteringFailures == onlyFailures.isSelected()) return;
            filteringFailures = onlyFailures.isSelected(); closeDetails(); applyFilter();
        });
        query.setId("sql-script-sql-filter"); query.setMinWidth(140); query.setPrefWidth(190);
        query.setPromptText("筛选 SQL 摘要"); query.setAccessibleText("筛选已显示的 SQL 摘要");
        query.setTooltip(new Tooltip("按字面匹配已显示的 SQL 摘要，忽略大小写及首尾空白，最多 256 个字符。\n"
                + "不搜索完整 SQL、错误信息、结果数据或省略的内容；离开概览后重置，不执行 SQL。"));
        query.setTextFormatter(new TextFormatter<String>(change -> {
            if (updatingQuery) return change;
            if (!canOpen()) return null;
            // Deletions cannot exceed the limit; a null reset need not assemble replacement text.
            if (change.isAdded() && change.getControlNewText().length() > MAX_QUERY_UNITS) {
                queryRejected = true; updateCount(); refresh(); return null;
            }
            if (change.isContentChange() && queryRejected) {
                queryRejected = false; updateCount(); refresh();
            }
            return change;
        }));
        query.textProperty().addListener((obs, before, after) -> {
            if (updatingQuery) return;
            closeDetails(); applyFilter();
        });
        clearQuery.setId("sql-script-clear-filter"); clearQuery.setMinWidth(Region.USE_PREF_SIZE);
        clearQuery.setTooltip(new Tooltip("清除 SQL 摘要关键词，保留“仅看异常”条件和当前排序。"));
        clearQuery.setOnAction(event -> {
            if (!canOpen()) return;
            queryRejected = false; query.clear(); updateCount(); refresh(); query.requestFocus();
        });
        count.setId("sql-script-filter-count"); count.setWrapText(true); count.setMinWidth(0); count.setMinHeight(Region.USE_PREF_SIZE);
        count.maxWidthProperty().bind(bar.widthProperty());
        notice.setId("sql-script-details-notice"); notice.setWrapText(true);
        notice.setMinHeight(Region.USE_PREF_SIZE);
        notice.maxWidthProperty().bind(javafx.beans.binding.Bindings.min(460, bar.widthProperty()));
        button.setOnAction(event -> showSelected());
        table.getSelectionModel().selectedItemProperty().addListener(selection);
        table.getSortOrder().addListener(sorting);
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
                    new ElapsedMillis(entry.elapsedMillis()), entry.summary(), entry.sqlPreview());
            entries.put(row, entry); rows.add(row);
        }
        sourceRows = List.copyOf(rows);
        notice.setText(report.displayNotice()); bar.setVisible(true); bar.setManaged(true); applyFilter();
    }

    private void applyFilter() {
        applyFilter(true);
    }

    private void applyFilter(boolean keepSorting) {
        var selectedRow = table.getSelectionModel().getSelectedItem();
        var sortOrder = List.copyOf(table.getSortOrder());
        if (!keepSorting) sortOrder = List.of();
        String term = normalizedQuery();
        var visible = sourceRows.stream().filter(row -> (!filteringFailures
                || entries.get(row).kind() == QueryResult.Kind.ERROR)
                && row.get(4).toString().toLowerCase(Locale.ROOT).contains(term)).toList();
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
        updateCount();
        refresh();
    }

    private String normalizedQuery() {
        return query.getText() == null ? "" : query.getText().strip().toLowerCase(Locale.ROOT);
    }

    private void updateCount() {
        String empty = "";
        if (table.getItems().isEmpty()) {
            if (!normalizedQuery().isEmpty()) empty = filteringFailures
                    ? "；已保留的异常结果中没有匹配的 SQL 摘要" : "；已保留的 SQL 摘要中没有匹配";
            else if (filteringFailures) empty = "；已保留结果中没有异常";
        }
        count.setText("显示 " + table.getItems().size() + " / " + sourceRows.size() + " 条已保留结果" + empty
                + (queryRejected ? "；筛选词最多 256 个字符，超长输入未应用" : ""));
    }

    Entry selectedEntry() {
        var row = table.getSelectionModel().getSelectedItem();
        return row != null && table.getItems().stream().anyMatch(item -> item == row) ? entries.get(row) : null;
    }

    private boolean canOpen() {
        return !closed && bar.isVisible() && !bar.isDisabled() && !table.isDisabled() && allowed.getAsBoolean();
    }

    private boolean needsOrderReset() {
        if (!table.getSortOrder().isEmpty()) return true;
        // Removing the last TableView sort arrow does not undo an in-place sort.
        int next = 0;
        for (var row : table.getItems()) {
            while (next < sourceRows.size() && sourceRows.get(next) != row) next++;
            if (next == sourceRows.size()) return true;
            next++;
        }
        return false;
    }

    void refresh() {
        boolean disabled = selectedEntry() == null || !canOpen();
        button.setDisable(disabled); result.setDisable(disabled);
        onlyFailures.setDisable(!canOpen());
        query.setDisable(!canOpen());
        clearQuery.setDisable(!canOpen() || (!queryRejected && (query.getText() == null || query.getText().isEmpty())));
        resetOrder.setDisable(!canOpen() || !needsOrderReset());
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
        closeDetails(); sourceRows = List.of(); filteringFailures = false; onlyFailures.setSelected(false);
        queryRejected = false; updatingQuery = true;
        try { query.clear(); } finally { updatingQuery = false; }
        count.setText("");
        entries.clear(); notice.setText(""); bar.setVisible(false); bar.setManaged(false); refresh();
    }

    @Override public void close() {
        if (closed) return;
        closed = true; clear();
        table.getSelectionModel().selectedItemProperty().removeListener(selection);
        table.getSortOrder().removeListener(sorting);
        table.removeEventHandler(KeyEvent.KEY_PRESSED, keys);
    }
}
