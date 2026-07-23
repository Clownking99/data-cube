package com.datacube.fx;

import com.datacube.config.SqlHistoryStore;
import com.datacube.config.SqlHistoryStore.Entry;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * SQL 历史找回对话框（模态）：左侧近期 SQL 列表（最新在前）+ 右侧完整预览 + 顶部过滤框。
 *
 * <p>选中项在右侧预览完整 SQL；双击列表项或点“打开”返回该条目，由 {@link AppShell}
 * 在新的 SQL 编辑标签中载入。列表为空时展示占位提示。
 */
public final class SqlHistoryDialog {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private SqlHistoryDialog() {}

    /**
     * 打开模态历史对话框。
     *
     * @return 用户选择“打开”的历史条目；取消/关闭返回 {@link Optional#empty()}
     */
    public static Optional<Entry> show(SqlHistoryStore store, Window owner, ThemeManager themeManager) {
        Dialog<Entry> dialog = new Dialog<>();
        dialog.setTitle("SQL 历史 - 找回近期使用的 SQL");
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);

        TextField filter = new TextField();
        filter.setPromptText("过滤：连接 / schema / SQL 内容");

        ObservableList<Entry> all = FXCollections.observableArrayList(store.recent());
        FilteredList<Entry> filtered = new FilteredList<>(all, e -> true);

        ListView<Entry> list = new ListView<>(filtered);
        list.setPlaceholder(new Label("（暂无历史）"));
        list.setPrefWidth(300);
        list.setCellFactory(v -> new EntryCell());

        TextArea preview = new TextArea();
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");
        preview.setPromptText("选中左侧条目预览完整 SQL");

        list.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) ->
                preview.setText(nv == null ? "" : nv.sql()));

        filter.textProperty().addListener((o, ov, nv) -> {
            String q = nv == null ? "" : nv.trim().toLowerCase();
            filtered.setPredicate(e -> q.isEmpty()
                    || (e.sql() != null && e.sql().toLowerCase().contains(q))
                    || (e.connName() != null && e.connName().toLowerCase().contains(q))
                    || (e.schema() != null && e.schema().toLowerCase().contains(q)));
        });

        if (!filtered.isEmpty()) list.getSelectionModel().select(0);

        SplitPane split = new SplitPane(list, preview);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.42);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox content = new VBox(8, filter, split);
        content.setPadding(new Insets(12));
        content.setPrefSize(720, 460);
        dialog.getDialogPane().setContent(content);

        ButtonType openType = new ButtonType("打开", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(openType, ButtonType.CLOSE);

        // 双击列表项直接打开
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
                dialog.setResult(list.getSelectionModel().getSelectedItem());
                dialog.close();
            }
        });

        dialog.setResultConverter(bt ->
                bt == openType ? list.getSelectionModel().getSelectedItem() : null);

        if (themeManager != null) themeManager.applyTo(dialog.getDialogPane());

        return dialog.showAndWait();
    }

    /** 列表单元格：时间 + [连接/schema] + SQL 首行。 */
    private static final class EntryCell extends ListCell<Entry> {
        @Override
        protected void updateItem(Entry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                return;
            }
            String time = TIME_FMT.format(Instant.ofEpochMilli(item.timestamp()));
            StringBuilder tag = new StringBuilder();
            if (item.connName() != null) tag.append(item.connName());
            if (item.schema() != null) {
                if (tag.length() > 0) tag.append('.');
                tag.append(item.schema());
            }
            String head = tag.length() > 0 ? time + "  [" + tag + "]" : time;
            setText(head + "\n" + firstLine(item.sql()));
        }

        private static String firstLine(String sql) {
            if (sql == null) return "";
            String trimmed = sql.strip();
            int nl = trimmed.indexOf('\n');
            String line = nl < 0 ? trimmed : trimmed.substring(0, nl);
            return line.length() > 80 ? line.substring(0, 80) + "…" : line;
        }
    }
}
