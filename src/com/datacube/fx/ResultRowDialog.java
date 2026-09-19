package com.datacube.fx;

import com.datacube.sqleditor.result.CompactResultText;
import com.datacube.sqleditor.result.ResultCellPreview;
import com.datacube.sqleditor.result.ResultRowPreview;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

/** A fixed row snapshot: field browsing never selects rows or exposes hidden columns in the source table. */
final class ResultRowDialog extends Dialog<Void> {
    private static final int MAX_QUERY_LENGTH = 256;

    ResultRowDialog(Window owner, ResultRowPreview snapshot, int focusedColumn) {
        setTitle("查看当前行（只读）"); setHeaderText(null); setResizable(true);
        if (owner != null) {
            initOwner(owner); initModality(Modality.WINDOW_MODAL);
            if (owner.getScene() != null) getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        Label identity = label("result-row-identity", "当前显示第 " + snapshot.displayRow() + " 行 · 结果第 " + snapshot.sourceRow()
                + " 行 · 打开时可见 " + snapshot.visibleColumnCount() + " 列"
                + (snapshot.columnsTruncated() ? "\n仅列出前 200 个可见字段；其余字段未包含在此窗口。" : ""));
        TableView<ResultCellPreview> fields = new TableView<>(); fields.setId("result-row-fields");
        fields.setAccessibleText("当前行的可见字段快照"); fields.setEditable(false);
        fields.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<ResultCellPreview, String> name = new TableColumn<>("字段（原列号）");
        name.setCellValueFactory(cell -> new ReadOnlyStringWrapper("原列 " + cell.getValue().column() + " · " + singleLine(cell.getValue().label())));
        name.setPrefWidth(250); name.setMinWidth(110); name.setSortable(false); name.setReorderable(false);
        TableColumn<ResultCellPreview, String> preview = new TableColumn<>("值预览（选择字段查看正文）");
        preview.setCellValueFactory(cell -> new ReadOnlyStringWrapper(preview(cell.getValue())));
        preview.setPrefWidth(400); preview.setMinWidth(100); preview.setSortable(false); preview.setReorderable(false);
        fields.getColumns().setAll(List.of(name, preview)); fields.getItems().setAll(snapshot.fields());
        Label empty = new Label("没有匹配的字段"); fields.setPlaceholder(empty);
        fields.setPrefHeight(180); fields.setMinHeight(100);
        Label detail = label("result-row-detail", "");
        Label summary = label("result-row-summary", "");
        ScrollPane metadata = new ScrollPane(detail); metadata.setId("result-row-metadata");
        metadata.setFitToWidth(true); metadata.setPrefViewportHeight(45); metadata.setMinHeight(45); metadata.setMaxHeight(80);
        TextArea value = new TextArea(); value.setId("result-row-text"); value.setAccessibleText("只读字段内容");
        value.setEditable(false); value.setWrapText(true); value.setPrefRowCount(6); value.setMinHeight(90);
        value.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");
        CheckBox wrap = new CheckBox("正文自动换行"); wrap.setId("result-row-wrap"); wrap.setSelected(true);
        wrap.selectedProperty().addListener((o, before, after) -> value.setWrapText(after));
        var find = new ResultCellFindBar(value, "result-row-find", "查找当前字段正文…", "查找当前字段已显示正文（Ctrl+Alt+F）");
        Label boundary = label("result-row-boundary", "只读：打开时的可见列快照，不查询或修改数据。隐藏列不包含在内。\n"
                + "字段筛选仅匹配窗口已列出的字段名；正文查找仅当前字段（Ctrl+Alt+F），不含截断尾部。\n"
                + "每字段正文最多 4,096 UTF-16 单元；更长内容请关闭后使用“查看单元格”。Esc 关闭。");
        TextField query = new TextField(); query.setId("result-row-query");
        query.setPromptText("按字段名筛选（不搜索值）"); query.setAccessibleText("按快照字段名筛选");
        query.setMinWidth(80); HBox.setHgrow(query, Priority.ALWAYS);
        Button clear = new Button("清空"); clear.setId("result-row-clear"); clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.disableProperty().bind(query.textProperty().isEmpty());
        clear.setOnAction(event -> { query.clear(); query.requestFocus(); });
        Label filterStatus = label("result-row-filter-status", "匹配 " + snapshot.fields().size() + " / " + snapshot.fields().size() + " 个快照字段");
        query.setTooltip(new javafx.scene.control.Tooltip("忽略大小写的字面匹配；最多 256 UTF-16 单元。Ctrl+F 聚焦，Enter / ↓ 进入字段列表。"));
        // Replacing table items can emit transient selections; never render a different duplicate.
        boolean[] filtering = {false};
        fields.getSelectionModel().selectedItemProperty().addListener((o, before, field) -> {
            if (!filtering[0]) renderField(field, detail, summary, value);
        });
        query.textProperty().addListener((o, before, after) -> {
            String raw = Objects.requireNonNullElse(after, "");
            boolean tooLong = raw.length() > MAX_QUERY_LENGTH;
            String needle = tooLong ? "" : raw.strip().toLowerCase(Locale.ROOT);
            var matches = tooLong ? List.<ResultCellPreview>of() : snapshot.fields().stream()
                    .filter(field -> singleLine(field.label()).toLowerCase(Locale.ROOT).contains(needle)).toList();
            var selected = fields.getSelectionModel().getSelectedItem();
            boolean keep = selected != null && matches.stream().anyMatch(field -> field == selected);
            filtering[0] = true;
            try {
                fields.getItems().setAll(matches); fields.getSelectionModel().clearSelection();
                if (keep) {
                    fields.getSelectionModel().select(selected);
                    fields.scrollTo(fields.getSelectionModel().getSelectedIndex());
                }
            } finally { filtering[0] = false; }
            if (!keep) {
                renderField(null, detail, summary, value);
                if (tooLong) detail.setText("请缩短查找词后选择字段。");
                else if (matches.isEmpty()) detail.setText("没有匹配的字段。");
            }
            empty.setText(tooLong ? "查找词过长，请缩短后重试" : "没有匹配的字段");
            filterStatus.setText(tooLong ? "查找词过长：最多 256 个 UTF-16 单元，请缩短后重试。"
                    : "匹配 " + matches.size() + " / " + snapshot.fields().size() + " 个快照字段");
        });
        VBox content = new VBox(8, identity, new HBox(8, query, clear), filterStatus, fields, metadata, summary, boundary, find, wrap, value);
        content.setId("result-row-content"); content.setPrefWidth(680); content.setMinWidth(320);
        VBox.setVgrow(value, Priority.ALWAYS); getDialogPane().setContent(content);
        var closeType = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE); getDialogPane().getButtonTypes().add(closeType);
        Button close = (Button) getDialogPane().lookupButton(closeType); close.setId("result-row-close");
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) { event.consume(); close.fire(); }
            else if (event.getCode() == KeyCode.F && event.isShortcutDown() && !event.isShiftDown()) {
                event.consume();
                if (event.isAltDown()) find.focusQuery(); else { query.requestFocus(); query.selectAll(); }
            } else if (!event.isControlDown() && !event.isAltDown() && !event.isMetaDown()
                    && (event.getCode() == KeyCode.F3 || event.getCode() == KeyCode.ENTER && find.queryFocused())) {
                event.consume(); find.navigate(!event.isShiftDown());
            } else if (event.getTarget() == query && !event.isControlDown() && !event.isAltDown()
                    && !event.isMetaDown() && !event.isShiftDown()
                    && (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.DOWN)) {
                event.consume();
                if (!fields.getItems().isEmpty()) {
                    if (fields.getSelectionModel().getSelectedItem() == null) fields.getSelectionModel().selectFirst();
                    fields.scrollTo(fields.getSelectionModel().getSelectedIndex()); fields.requestFocus();
                }
            }
        });
        fields.getSelectionModel().select(snapshot.fields().stream().filter(field -> field.column() == focusedColumn)
                .findFirst().orElse(snapshot.fields().getFirst()));
        setOnShown(event -> { fields.scrollTo(fields.getSelectionModel().getSelectedIndex()); fields.requestFocus(); });
        // The owning pane also uses onHidden; do not let it replace listener cleanup.
        showingProperty().addListener((o, before, showing) -> { if (!showing) find.close(); });
    }

    private static void renderField(ResultCellPreview field, Label detail, Label summary, TextArea value) {
        if (field == null) { detail.setText("请选择字段查看正文。"); summary.setText(""); value.clear(); return; }
        detail.setText("原列 " + field.column() + " · " + singleLine(field.label()) + "\n类型：" + singleLine(field.type())
                + "（JDBC " + field.jdbcType() + "）"
                + (field.metadataTruncated() ? "\n列名或类型名过长，已省略部分显示。" : ""));
        summary.setText(kind(field) + " · 显示表示长度 " + field.displayLength() + " UTF-16 单元"
                + (field.truncated() ? "\n正文已截断，仅保留前 " + field.text().length() + " 个单元。" : "")
                + (field.displayOnly() ? "\n特殊类型的显示表示可能已在读取时截断，不代表完整原值。" : ""));
        value.setText(field.text()); value.positionCaret(0);
    }

    private static String kind(ResultCellPreview field) {
        return field.nullValue() ? "NULL（数据库空值）" : field.emptyString() ? "空字符串" : "非 NULL 值";
    }

    private static String preview(ResultCellPreview field) {
        if (field.nullValue() || field.emptyString()) return kind(field);
        if (field.text().equals("NULL")) return "\"NULL\"（文字）";
        return CompactResultText.preview(field.text());
    }

    private static String singleLine(String text) { return text.replaceAll("[\\p{Cntrl}\\u0085\\u2028\\u2029]", " "); }
    private static Label label(String id, String text) {
        var label = new Label(text); label.setId(id); label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE); label.setMaxWidth(Double.MAX_VALUE); return label;
    }
}
