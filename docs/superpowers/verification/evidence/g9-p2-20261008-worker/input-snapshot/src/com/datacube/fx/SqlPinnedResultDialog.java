package com.datacube.fx;

import com.datacube.sqleditor.result.CompactResultText;
import com.datacube.sqleditor.result.PinnedResultStore.Entry;
import com.datacube.sqleditor.result.ResultValueFormatter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.Duration;

/** A single inert viewer. All controls are detached when hidden, including queued search work. */
final class SqlPinnedResultDialog extends Dialog<Void> {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(ZoneId.systemDefault());
    private final TableView<List<Object>> table = new TableView<>();
    private final TextArea sql;
    private final TextArea cell = area("sql-pinned-cell", "", 3);
    private final TextField search = new TextField();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(120));
    private final javafx.collections.ObservableList<List<Object>> rows;
    private final FilteredList<List<Object>> filtered;
    private final SortedList<List<Object>> sorted;
    private final Button remove;
    private Runnable removeAction;
    private boolean disposed;

    SqlPinnedResultDialog(Window owner, Entry entry, Runnable removeEntry) {
        removeAction = removeEntry;
        setTitle("固定结果 #" + entry.id() + "（只读）"); setHeaderText(null); setResizable(true);
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
            if (owner.getScene() != null) getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        var result = entry.result(); var source = entry.source();
        Label identity = label("sql-pinned-identity", source.target() + "\nSchema：" + source.schema()
                + "\n固定时间：" + TIME.format(entry.pinnedAt()) + " · 已加载 " + result.rows.size() + " 行 / " + result.columns.size() + " 列");
        Label boundary = label("sql-pinned-boundary", "当前标签内存中的已加载结果；不会重新查询。关闭标签即释放。\n"
                + "固定全部已加载行，不继承原视图筛选或列布局；表格排序按显示文本，选择单元格查看内容。"
                + (result.truncated ? "\n原结果已截断，未加载行不在此快照内。" : "")
                + (result.retentionNotice == null || result.retentionNotice.isEmpty() ? "" : "\n" + result.retentionNotice)
                + (source.databaseFiltered() ? "\n此结果已应用数据库筛选；下方为原始 SQL，筛选参数未保留。" : "")
                + "\n返回结果不代表事务已提交；固定时间不是数据库一致性快照时间。");
        sql = area("sql-pinned-sql", source.sql(), 3);
        table.setId("sql-pinned-table"); table.setEditable(false); table.setAccessibleText("固定的已加载查询结果");
        table.getSelectionModel().setCellSelectionEnabled(true);
        table.setPrefHeight(300); table.setMinHeight(120);
        for (int i = 0; i < result.columns.size(); i++) {
            final int index = i;
            TableColumn<List<Object>, Object> column = new TableColumn<>(result.columns.get(i));
            column.setUserData(i); column.setPrefWidth(160);
            column.setCellValueFactory(value -> new ReadOnlyObjectWrapper<>(value.getValue().get(index)));
            column.setComparator((a, b) -> ResultValueFormatter.format(a).compareTo(ResultValueFormatter.format(b)));
            column.setCellFactory(ignored -> new TableCell<>() {
                @Override protected void updateItem(Object value, boolean empty) {
                    super.updateItem(value, empty);
                    setText(empty ? null : value == null ? "NULL" : CompactResultText.preview(ResultValueFormatter.format(value)));
                }
            });
            table.getColumns().add(column);
        }
        rows = FXCollections.observableArrayList(result.rows);
        filtered = new FilteredList<>(rows);
        sorted = new SortedList<>(filtered); sorted.comparatorProperty().bind(table.comparatorProperty()); table.setItems(sorted);
        Label count = label("sql-pinned-count", "显示 " + rows.size() + " / " + rows.size() + " 行");
        search.setId("sql-pinned-search"); search.setPromptText("搜索固定结果的已加载内容（最多 256 字）");
        search.setAccessibleText("搜索固定结果");
        search.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= 256 ? change : null));
        debounce.setOnFinished(event -> {
            if (disposed) return;
            String needle = search.getText().toLowerCase(Locale.ROOT);
            table.getSelectionModel().clearSelection(); cell.clear();
            filtered.setPredicate(row -> needle.isEmpty() || row.stream().anyMatch(value -> ResultValueFormatter.format(value).toLowerCase(Locale.ROOT).contains(needle)));
            count.setText("显示 " + filtered.size() + " / " + rows.size() + " 行");
        });
        search.textProperty().addListener((obs, before, after) -> { if (!disposed) debounce.playFromStart(); });
        table.getSelectionModel().getSelectedCells().addListener((javafx.collections.ListChangeListener<TablePosition>) change -> {
            if (disposed || table.getSelectionModel().getSelectedCells().isEmpty()) { cell.clear(); return; }
            var selected = table.getSelectionModel().getSelectedCells().getFirst();
            if (selected.getRow() < 0 || selected.getRow() >= table.getItems().size()
                    || selected.getTableColumn() == null || !(selected.getTableColumn().getUserData() instanceof Integer index)) return;
            Object value = table.getItems().get(selected.getRow()).get(index);
            cell.setText(value == null ? "NULL（数据库空值）" : value instanceof String text && text.isEmpty() ? "空字符串" : ResultValueFormatter.format(value));
            cell.positionCaret(0);
        });
        var metadata = new ScrollPane(new VBox(8, identity, boundary)); metadata.setId("sql-pinned-metadata");
        metadata.setFitToWidth(true); metadata.setPrefViewportHeight(140); metadata.setMinHeight(80); metadata.setMaxHeight(180);
        var content = new VBox(8, metadata, sql, search, count, table, cell);
        content.setMinWidth(320); content.setPrefWidth(760); VBox.setVgrow(table, Priority.ALWAYS);
        getDialogPane().setContent(content); getDialogPane().setPrefSize(760, 680);
        var removeType = new ButtonType("移除这份固定结果", ButtonBar.ButtonData.LEFT);
        var closeType = new ButtonType("关闭窗口", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(removeType, closeType);
        remove = (Button) getDialogPane().lookupButton(removeType); remove.setId("sql-pinned-remove");
        remove.addEventFilter(javafx.event.ActionEvent.ACTION, event -> { event.consume(); if (!disposed) removeAction.run(); });
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (disposed) return;
            if (event.getCode() == KeyCode.ESCAPE) { event.consume(); close(); }
            else if (event.getCode() == KeyCode.F && event.isShortcutDown() && !event.isAltDown() && !event.isShiftDown()) {
                event.consume(); search.requestFocus(); search.selectAll();
            }
        });
        showingProperty().addListener((obs, before, showing) -> { if (!showing) dispose(); });
    }

    void dispose() {
        if (disposed) return;
        disposed = true; removeAction = null; debounce.stop(); debounce.setOnFinished(null); remove.setDisable(true);
        sorted.comparatorProperty().unbind(); table.getSelectionModel().clearSelection();
        table.setItems(FXCollections.observableArrayList()); table.getColumns().clear();
        filtered.setPredicate(null); rows.clear(); search.clear(); sql.clear(); cell.clear();
        getDialogPane().setContent(null);
    }
    private static TextArea area(String id, String value, int lines) {
        var area = new TextArea(value); area.setId(id); area.setAccessibleText(id.equals("sql-pinned-sql") ? "固定结果来源 SQL（只读）" : "固定结果选中单元格（只读）");
        area.setEditable(false); area.setWrapText(true); area.setPrefRowCount(lines); area.setMinHeight(50); return area;
    }
    private static Label label(String id, String text) {
        var label = new Label(text); label.setId(id); label.setWrapText(true); label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE); return label;
    }
}
