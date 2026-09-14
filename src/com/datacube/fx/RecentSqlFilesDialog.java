package com.datacube.fx;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Path metadata only: no filesystem probing, script preview or open side effects. */
final class RecentSqlFilesDialog {
    static Dialog<Path> create(List<Path> paths, Window owner, BooleanSupplier allowed) {
        var dialog = new Dialog<Path>();
        dialog.setTitle("最近 SQL 文件"); dialog.setHeaderText(null); dialog.setResizable(true);
        if (owner != null) {
            dialog.initOwner(owner);
            if (owner.getScene() != null) dialog.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        var all = FXCollections.observableArrayList(List.copyOf(paths));
        var filtered = new FilteredList<>(all, path -> true);
        var query = new TextField(); query.setId("recent-sql-query"); query.setMinWidth(0);
        query.setAccessibleText("按文件名或路径筛选最近 SQL 文件");
        query.setPromptText("文件名 / 路径（最多 256 字符）");
        query.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= 256 ? change : null));
        var clear = new Button("清除筛选"); clear.setId("recent-sql-clear"); clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.disableProperty().bind(query.textProperty().isEmpty());
        clear.setOnAction(event -> { query.clear(); query.requestFocus(); });
        var search = new HBox(8, query, clear); HBox.setHgrow(query, Priority.ALWAYS);
        var count = new Label(); count.setId("recent-sql-count");
        var placeholder = new Label(); placeholder.setWrapText(true);
        var list = new ListView<Path>(filtered); list.setId("recent-sql-list");
        list.setAccessibleText("最近 SQL 文件列表，选择后按 Enter 打开");
        list.setPlaceholder(placeholder); list.setMinWidth(0); list.setPrefHeight(220);
        list.setCellFactory(view -> new ListCell<>() {
            private final Label name = new Label();
            private final Label folder = new Label();
            private final VBox lines = new VBox(2, name, folder);
            {
                name.setMaxWidth(Double.MAX_VALUE); folder.setMaxWidth(Double.MAX_VALUE);
                folder.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
                folder.setStyle("-fx-opacity: 0.8;");
                lines.prefWidthProperty().bind(view.widthProperty().subtract(40));
                lines.setMinWidth(0); lines.setMaxWidth(Region.USE_PREF_SIZE);
            }
            @Override protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty || item == null) { setGraphic(null); setAccessibleText(null); return; }
                name.setText(shortText(item.getFileName() == null ? item.toString() : item.getFileName().toString(), 120));
                folder.setText(item.getParent() == null ? item.toString() : item.getParent().toString());
                setAccessibleText(item.toString()); setGraphic(lines);
            }
        });
        var pathPreview = new TextArea(); pathPreview.setId("recent-sql-path");
        pathPreview.setAccessibleText("所选 SQL 文件完整路径，只读");
        pathPreview.setEditable(false); pathPreview.setWrapText(true); pathPreview.setPrefRowCount(2);
        pathPreview.setMinWidth(0); pathPreview.setPromptText("选择文件后核对完整路径；不预览 SQL 正文");
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) ->
                pathPreview.setText(selected == null ? "" : selected.toString()));
        Runnable updateCount = () -> {
            count.setText("显示 " + filtered.size() + " / " + all.size() + " 个最近文件");
            placeholder.setText(all.isEmpty() ? "暂无最近 SQL 文件" : "没有匹配的文件，请修改或清除筛选");
        };
        query.textProperty().addListener((obs, old, text) -> {
            Path previous = list.getSelectionModel().getSelectedItem();
            String term = text.strip().toLowerCase(Locale.ROOT);
            filtered.setPredicate(path -> path.toString().toLowerCase(Locale.ROOT).contains(term));
            list.getSelectionModel().clearSelection();
            if (previous != null && filtered.contains(previous)) list.getSelectionModel().select(previous);
            pathPreview.setText(list.getSelectionModel().getSelectedItem() == null ? "" : previous.toString());
            updateCount.run();
        });
        if (!filtered.isEmpty()) list.getSelectionModel().selectFirst();
        updateCount.run();
        var hint = new Label("仅查找最近索引（最多 10 条），不扫描磁盘或读取正文。\n确认后才读取文件；不自动连接或执行 SQL。\nCtrl+F 筛选 · ↓ 选择候选 · Enter 打开 · Esc 取消");
        hint.setWrapText(true); hint.setMinHeight(Region.USE_PREF_SIZE);
        var content = new VBox(8, search, count, list, pathPreview, hint);
        content.setPadding(new Insets(12)); content.setPrefSize(640, 420); content.setMinWidth(0);
        VBox.setVgrow(list, Priority.ALWAYS); dialog.getDialogPane().setContent(content);
        var openType = new ButtonType("打开", ButtonBar.ButtonData.OK_DONE);
        var cancelType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(openType, cancelType);
        var open = (Button) dialog.getDialogPane().lookupButton(openType); open.setId("recent-sql-open");
        var cancel = (Button) dialog.getDialogPane().lookupButton(cancelType); cancel.setId("recent-sql-cancel");
        open.setDefaultButton(false);
        open.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        var closed = new AtomicBoolean();
        BooleanSupplier candidateAllowed = () -> !closed.get() && allowed.getAsBoolean()
                && !dialog.getDialogPane().isDisabled() && filtered.contains(list.getSelectionModel().getSelectedItem());
        open.addEventFilter(ActionEvent.ACTION, event -> { if (!candidateAllowed.getAsBoolean()) event.consume(); });
        query.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (modified(event)) return;
            if (event.getCode() == KeyCode.DOWN) {
                if (!filtered.isEmpty()) {
                    if (list.getSelectionModel().isEmpty()) list.getSelectionModel().selectFirst();
                    list.requestFocus(); list.scrollTo(list.getSelectionModel().getSelectedIndex());
                }
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER) { open.fire(); event.consume(); }
        });
        list.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!modified(event) && event.getCode() == KeyCode.ENTER) { open.fire(); event.consume(); }
        });
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && !event.isAltDown() && !event.isMetaDown() && !event.isShiftDown()
                    && event.getCode() == KeyCode.F) {
                query.requestFocus(); query.selectAll(); event.consume();
            } else if (!modified(event) && event.getCode() == KeyCode.ESCAPE) { cancel.fire(); event.consume(); }
        });
        dialog.setResultConverter(button -> button == openType && candidateAllowed.getAsBoolean()
                ? list.getSelectionModel().getSelectedItem() : null);
        dialog.setOnShown(event -> query.requestFocus());
        dialog.setOnHidden(event -> closed.set(true));
        return dialog;
    }

    private static String shortText(String value, int maximum) {
        String line = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return line.length() <= maximum ? line : line.substring(0, maximum) + "…";
    }
    private static boolean modified(KeyEvent event) {
        return event.isControlDown() || event.isAltDown() || event.isMetaDown() || event.isShiftDown();
    }
    private RecentSqlFilesDialog() { }
}
