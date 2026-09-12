package com.datacube.fx;

import com.datacube.config.SqlHistoryStore;
import com.datacube.config.SqlHistoryStore.Entry;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

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
        return create(store.recent(), owner, themeManager).showAndWait();
    }

    static Dialog<Entry> create(List<Entry> entries, Window owner, ThemeManager themeManager) {
        Dialog<Entry> dialog = new Dialog<>();
        dialog.setTitle("SQL 历史 - 找回近期使用的 SQL");
        dialog.setHeaderText(null);
        dialog.setResizable(true);
        if (owner != null) dialog.initOwner(owner);

        TextField filter = new TextField();
        filter.setId("sql-history-filter");
        filter.setAccessibleText("筛选 SQL 历史");
        filter.setPromptText("过滤：连接 / schema / SQL 内容");
        filter.setMinWidth(0);
        Button clear = new Button("清除筛选");
        clear.setId("sql-history-clear");
        clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.disableProperty().bind(filter.textProperty().isEmpty());
        clear.setOnAction(event -> { filter.clear(); filter.requestFocus(); });
        HBox search = new HBox(8, filter, clear);
        HBox.setHgrow(filter, Priority.ALWAYS);
        Label count = new Label();
        count.setId("sql-history-count");
        count.setMinHeight(Region.USE_PREF_SIZE);

        ObservableList<Entry> all = FXCollections.observableArrayList(entries);
        FilteredList<Entry> filtered = new FilteredList<>(all, e -> true);

        ListView<Entry> list = new ListView<>(filtered);
        list.setId("sql-history-list");
        list.setAccessibleText("匹配的 SQL 历史，按 Enter 打开");
        Label placeholder = new Label();
        placeholder.setWrapText(true);
        placeholder.setPadding(new Insets(12));
        list.setPlaceholder(placeholder);
        list.setPrefWidth(300);
        list.setMinWidth(160);

        TextArea preview = new TextArea();
        preview.setId("sql-history-preview");
        preview.setAccessibleText("历史 SQL 完整预览，只读");
        preview.setMinWidth(160);
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");
        preview.setPromptText("选中左侧条目预览完整 SQL");

        list.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) ->
                preview.setText(nv == null ? "" : nv.sql()));

        filter.textProperty().addListener((o, ov, nv) -> {
            Entry previous = list.getSelectionModel().getSelectedItem();
            String q = nv == null ? "" : nv.strip().toLowerCase(Locale.ROOT);
            filtered.setPredicate(e -> q.isEmpty()
                    || contains(e.sql(), q) || contains(e.connName(), q) || contains(e.schema(), q));
            // Filtering can move indexes; keep the entry itself only if it is still visible.
            int retained = previous == null ? -1 : filtered.indexOf(previous);
            list.getSelectionModel().clearSelection();
            if (!filtered.isEmpty()) list.getSelectionModel().select(retained < 0 ? 0 : retained);
            list.scrollTo(list.getSelectionModel().getSelectedIndex());
            updateSummary(count, placeholder, filtered.size(), all.size());
        });

        if (!filtered.isEmpty()) list.getSelectionModel().select(0);
        updateSummary(count, placeholder, filtered.size(), all.size());

        SplitPane split = new SplitPane(list, preview);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.42);
        VBox.setVgrow(split, Priority.ALWAYS);

        Label hint = new Label("仅载入新 SQL 标签，不自动连接或执行。\nCtrl+F 筛选 · ↓ 进入列表 · Enter 打开 · Esc 取消");
        hint.setId("sql-history-hint");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);
        VBox content = new VBox(8, search, count, split, hint);
        content.setPadding(new Insets(12));
        content.setPrefSize(720, 460);
        dialog.getDialogPane().setContent(content);

        ButtonType openType = new ButtonType("打开到新 SQL", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(openType, cancelType);
        Button open = (Button) dialog.getDialogPane().lookupButton(openType);
        open.setId("sql-history-open");
        open.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        // Enter in the read-only preview must not activate a default dialog button.
        open.setDefaultButton(false);
        Button cancel = (Button) dialog.getDialogPane().lookupButton(cancelType);
        cancel.setId("sql-history-cancel");
        list.setCellFactory(v -> new EntryCell(entry -> {
            list.getSelectionModel().select(entry);
            open.fire();
        }));
        filter.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (modified(event)) return;
            if (event.getCode() == KeyCode.DOWN) {
                if (!filtered.isEmpty()) {
                    if (list.getSelectionModel().isEmpty()) list.getSelectionModel().selectFirst();
                    list.requestFocus();
                    list.scrollTo(list.getSelectionModel().getSelectedIndex());
                }
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER) {
                open.fire(); event.consume();
            }
        });
        list.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!modified(event) && event.getCode() == KeyCode.ENTER) {
                open.fire(); event.consume();
            }
        });
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && !event.isAltDown() && !event.isMetaDown()
                    && !event.isShiftDown() && event.getCode() == KeyCode.F) {
                filter.requestFocus(); filter.selectAll(); event.consume();
            } else if (!modified(event) && event.getCode() == KeyCode.ESCAPE) {
                cancel.fire(); event.consume();
            }
        });
        dialog.setOnShown(event -> filter.requestFocus());

        dialog.setResultConverter(bt ->
                bt == openType ? list.getSelectionModel().getSelectedItem() : null);

        if (themeManager != null) themeManager.applyTo(dialog.getDialogPane());

        return dialog;
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private static boolean modified(KeyEvent event) {
        return event.isAltDown() || event.isControlDown() || event.isMetaDown() || event.isShiftDown();
    }

    private static void updateSummary(Label count, Label placeholder, int matches, int total) {
        count.setText("显示 " + matches + " / " + total + " 条历史");
        placeholder.setText(total == 0 ? "暂无 SQL 历史" : "没有匹配的历史，请修改或清除筛选");
    }

    /** 列表单元格：时间 + [连接/schema] + SQL 首行。 */
    private static final class EntryCell extends ListCell<Entry> {
        EntryCell(Consumer<Entry> activate) {
            setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                        && !event.isAltDown() && !event.isControlDown() && !event.isMetaDown()
                        && !event.isShiftDown() && !isEmpty() && getItem() != null) {
                    activate.accept(getItem()); event.consume();
                }
            });
        }

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
