package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

final class SqlDraftConnectionChooser {
    private SqlDraftConnectionChooser() { }

    record Choice(ConnConfig config) {
        @Override public String toString() {
            return SqlDraftManagerPane.preview(config.name(), 80) + " · " + config.type()
                    + " · " + SqlDraftManagerPane.preview(config.id(), 80);
        }
    }

    static List<Choice> choices(List<ConnConfig> configs) {
        return configs.stream().filter(config -> config != null && config.id() != null
                && !config.id().isBlank()
                && (config.type() == DbType.POSTGRESQL || config.type() == DbType.ORACLE))
                .map(Choice::new).toList();
    }

    static Optional<ConnConfig> show(List<ConnConfig> configs, Window owner) {
        return show(configs, owner, "选择草稿连接");
    }

    static Optional<ConnConfig> show(List<ConnConfig> configs, Window owner, String title) {
        return create(configs, owner, title).showAndWait();
    }

    static Dialog<ConnConfig> create(List<ConnConfig> configs, Window owner, String title) {
        List<Choice> available = choices(configs);
        FilteredList<Choice> filtered = new FilteredList<>(FXCollections.observableArrayList(available));
        Dialog<ConnConfig> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setResizable(true);
        dialog.setHeaderText(available.isEmpty() ? "没有可用连接，请先新建 PostgreSQL 或 Oracle 连接。"
                : "仅选择目标，不连接或执行 SQL；首次执行或会话操作将锁定连接。");

        TextField query = new TextField();
        query.setId("sql-connection-query");
        query.setPromptText("筛选连接：名称 / 类型 / ID（最多 256 字符）");
        query.setAccessibleText("筛选连接名称、类型或 ID，最多 256 UTF-16 单元");
        query.setMinWidth(0);
        query.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().length() <= 256 ? change : null));
        Button clear = new Button("清除筛选");
        clear.setId("sql-connection-clear");
        clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.disableProperty().bind(query.textProperty().isEmpty());
        clear.setOnAction(event -> { query.clear(); query.requestFocus(); });
        HBox search = new HBox(8, query, clear);
        HBox.setHgrow(query, Priority.ALWAYS);
        ListView<Choice> list = new ListView<>(filtered);
        list.setId("sql-connection-list");
        list.setAccessibleText("可用连接：名称、类型、ID；请选择后按 Enter 确认");
        Label empty = new Label();
        empty.setWrapText(true);
        empty.setPadding(new Insets(12));
        list.setPlaceholder(empty);
        Label count = new Label();
        count.setId("sql-connection-count");
        count.setMinHeight(Region.USE_PREF_SIZE);
        Runnable summary = () -> {
            count.setText("显示 " + filtered.size() + " / " + available.size() + " 个连接");
            empty.setText(available.isEmpty() ? "暂无可用 PostgreSQL / Oracle 连接"
                    : "没有匹配的连接，请修改或清除筛选");
        };
        query.textProperty().addListener((obs, old, value) -> {
            Choice previous = list.getSelectionModel().getSelectedItem();
            // Clear before changing indexes: filtering must never choose a different target.
            list.getSelectionModel().clearSelection();
            String term = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            filtered.setPredicate(choice -> term.isEmpty() || matches(choice.config(), term));
            list.getSelectionModel().clearSelection();
            if (previous != null && filtered.contains(previous)) {
                list.getSelectionModel().select(previous);
                list.scrollTo(previous);
            }
            summary.run();
        });
        summary.run();
        Label hint = new Label("输入不会自动选择目标。↓ 选择候选 · Enter 确认 · Ctrl+F 筛选 · Esc 取消");
        hint.setId("sql-connection-hint");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);
        VBox content = new VBox(8, search, count, list, hint);
        content.setPadding(new Insets(12));
        content.setPrefSize(600, 360);
        VBox.setVgrow(list, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Button confirm = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        confirm.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        confirm.setDefaultButton(false);
        Button cancel = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        query.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!modified(event) && event.getCode() == KeyCode.DOWN) {
                if (!filtered.isEmpty()) {
                    if (list.getSelectionModel().isEmpty()) list.getSelectionModel().selectFirst();
                    list.requestFocus();
                    list.scrollTo(list.getSelectionModel().getSelectedIndex());
                }
                event.consume();
            }
        });
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && !event.isAltDown() && !event.isMetaDown()
                    && !event.isShiftDown() && event.getCode() == KeyCode.F) {
                query.requestFocus(); query.selectAll(); event.consume();
            } else if (!modified(event) && event.getCode() == KeyCode.ESCAPE) {
                cancel.fire(); event.consume();
            } else if (!modified(event) && event.getCode() == KeyCode.ENTER
                    && (query.isFocused() || list.isFocused())) {
                confirm.fire(); event.consume();
            }
        });
        dialog.setOnShown(event -> query.requestFocus());
        dialog.setResultConverter(button -> {
            Choice selected = list.getSelectionModel().getSelectedItem();
            return button == ButtonType.OK && selected != null && filtered.contains(selected) ? selected.config() : null;
        });
        return dialog;
    }

    private static boolean matches(ConnConfig config, String term) {
        return contains(config.name(), term) || contains(config.id(), term) || contains(config.type().name(), term);
    }

    private static boolean contains(String value, String term) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(term);
    }

    private static boolean modified(KeyEvent event) {
        return event.isControlDown() || event.isAltDown() || event.isMetaDown() || event.isShiftDown();
    }
}
