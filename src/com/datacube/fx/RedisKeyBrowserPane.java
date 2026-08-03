package com.datacube.fx;

import com.datacube.redis.KeyTreeBuilder;
import com.datacube.redis.RedisSession;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.model.ConnConfig;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Redis SCAN 键浏览器与 String/Hash/List/Set/ZSet 编辑器。 */
public final class RedisKeyBrowserPane implements AutoCloseable {

    private static final int SCAN_COUNT = 500;
    private static final int COLLECTION_PAGE = 200;
    private static final long LARGE_VALUE = 1024L * 1024L;
    private static final int PREVIEW_BYTES = 4096;

    private final ConnectionManager manager;
    private final ConnConfig config;
    private final ExecutorService io;
    private final BorderPane root = new BorderPane();
    private final TreeView<TreeEntry> tree = new TreeView<>();
    private final VBox details = new VBox(8);
    private final TextField pattern = new TextField("*");
    private final TextField separator = new TextField(":");
    private final ComboBox<Integer> database = new ComboBox<>();
    private final Button loadMore = new Button();
    private final Label status = new Label("就绪");
    private final Set<String> loadedKeys = new LinkedHashSet<>();

    private volatile RedisSession session;
    private long scanCursor;
    private boolean busy;

    public RedisKeyBrowserPane(ConnectionManager manager, ConnConfig config, int initialDatabase) {
        this.manager = manager;
        this.config = config;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Redis-Keys-" + config.id());
            thread.setDaemon(true);
            return thread;
        });
        build(initialDatabase);
        restartSession(initialDatabase);
    }

    public Node getNode() {
        return root;
    }

    private void build(int initialDatabase) {
        root.setPadding(new Insets(10));
        for (int i = 0; i < 16; i++) database.getItems().add(i);
        database.setValue(Math.max(0, Math.min(15, initialDatabase)));
        database.setPrefWidth(82);
        pattern.setPromptText("SCAN MATCH glob");
        separator.setPromptText("分隔符");
        separator.setPrefWidth(55);

        Button refresh = new Button("刷新");
        refresh.setOnAction(e -> refreshKeys());
        Button create = new Button("＋ 新建键");
        create.setOnAction(e -> createKey());
        HBox controls = new HBox(6, new Label("DB"), database, pattern, new Label("分隔"), separator, refresh, create);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pattern, Priority.ALWAYS);

        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<>(new TreeEntry("root", null)));
        tree.setCellFactory(tv -> new KeyCell());
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null && selected.getValue().key() != null) loadKey(selected.getValue().key());
        });

        loadMore.setOnAction(e -> loadNextKeys());
        VBox left = new VBox(6, controls, tree, loadMore, status);
        VBox.setVgrow(tree, Priority.ALWAYS);

        details.setPadding(new Insets(0, 0, 0, 8));
        details.getChildren().add(new Label("选择一个键查看和编辑值"));
        ScrollPane detailScroll = new ScrollPane(details);
        detailScroll.setFitToWidth(true);

        SplitPane split = new SplitPane(left, detailScroll);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.38);
        root.setCenter(split);

        database.valueProperty().addListener((obs, old, value) -> {
            if (value != null && !value.equals(old)) restartSession(value);
        });
        separator.setOnAction(e -> rebuildTree());
        pattern.setOnAction(e -> refreshKeys());
    }

    private void restartSession(int db) {
        setBusy(true, "连接 db" + db + "...");
        io.submit(() -> {
            try {
                RedisSession old = session;
                if (old != null) manager.closeRedisSession(old);
                session = manager.openRedisSession(config.id(), db);
                if (!session.ping()) throw new IllegalStateException("PING 返回异常");
                Platform.runLater(() -> {
                    setBusy(false, "就绪");
                    refreshKeys();
                });
            } catch (Exception error) {
                Platform.runLater(() -> showError(error));
            }
        });
    }

    private void refreshKeys() {
        if (busy || session == null) return;
        loadedKeys.clear();
        scanCursor = 0;
        tree.getRoot().getChildren().clear();
        loadNextKeys();
    }

    private void loadNextKeys() {
        if (busy || session == null) return;
        setBusy(true, "扫描键...");
        long cursor = scanCursor;
        String match = pattern.getText().isBlank() ? "*" : pattern.getText().trim();
        runIo(() -> session.scan(cursor, match, SCAN_COUNT), page -> {
            scanCursor = page.cursor();
            for (byte[] key : page.values()) loadedKeys.add(text(key));
            rebuildTree();
            loadMore.setVisible(scanCursor != 0);
            loadMore.setManaged(scanCursor != 0);
            loadMore.setText("加载更多（已加载 " + loadedKeys.size() + "）");
            setBusy(false, "已加载 " + loadedKeys.size() + " 个键");
        });
    }

    private void rebuildTree() {
        KeyTreeBuilder.Node model = KeyTreeBuilder.build(new ArrayList<>(loadedKeys), separator.getText());
        tree.getRoot().getChildren().setAll(model.children().stream().map(this::treeItem).toList());
    }

    private TreeItem<TreeEntry> treeItem(KeyTreeBuilder.Node node) {
        String label = node.segment();
        if (!node.children().isEmpty()) label += " (" + node.keyCount() + ")";
        if (node.fullKey() != null && !node.children().isEmpty()) label += " •";
        TreeItem<TreeEntry> item = new TreeItem<>(new TreeEntry(label, node.fullKey()));
        item.getChildren().addAll(node.children().stream().map(this::treeItem).toList());
        return item;
    }

    private void loadKey(String key) {
        setBusy(true, "读取 " + key + "...");
        runIo(() -> new KeyMeta(session.type(key), session.ttl(key)), meta -> {
            renderHeader(key, meta);
            switch (meta.type().toLowerCase(Locale.ROOT)) {
                case "string" -> renderString(key, false);
                case "hash" -> renderHash(key, 0);
                case "list" -> renderList(key, 0);
                case "set" -> renderSet(key, 0);
                case "zset" -> renderZSet(key, 0);
                default -> details.getChildren().add(new Label("暂不支持的类型: " + meta.type()));
            }
            setBusy(false, "已读取 " + key);
        });
    }

    private void renderHeader(String key, KeyMeta meta) {
        details.getChildren().clear();
        TextField keyField = new TextField(key);
        Label type = new Label(meta.type().toUpperCase(Locale.ROOT));
        type.setStyle("-fx-background-color: -brand-accent; -fx-text-fill: white; -fx-padding: 3 7; -fx-background-radius: 4;");
        Button rename = new Button("重命名");
        rename.setOnAction(e -> {
            String target = keyField.getText().trim();
            if (target.isEmpty() || target.equals(key)) return;
            mutate(() -> { session.rename(key, target); return null; }, this::refreshKeys);
        });
        Button delete = new Button("删除");
        delete.setStyle("-fx-text-fill: -status-error;");
        delete.setOnAction(e -> confirmDelete(key));
        Button reload = new Button("刷新");
        reload.setOnAction(e -> loadKey(key));
        HBox keyBar = new HBox(6, keyField, type, rename, reload, delete);
        keyBar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(keyField, Priority.ALWAYS);

        TextField ttl = new TextField(meta.ttl() >= 0 ? Long.toString(meta.ttl()) : "");
        ttl.setPromptText(meta.ttl() == -1 ? "持久化" : "秒");
        ttl.setPrefWidth(100);
        Button applyTtl = new Button("设置 TTL");
        applyTtl.setOnAction(e -> {
            try {
                long seconds = Long.parseLong(ttl.getText().trim());
                mutate(() -> session.expire(key, seconds), () -> loadKey(key));
            } catch (NumberFormatException error) {
                warn("TTL 必须是整数秒");
            }
        });
        Button persist = new Button("持久化");
        persist.setOnAction(e -> mutate(() -> session.persist(key), () -> loadKey(key)));
        HBox ttlBar = new HBox(6, new Label("TTL:"), ttl, applyTtl, persist);
        ttlBar.setAlignment(Pos.CENTER_LEFT);
        details.getChildren().addAll(keyBar, ttlBar, new Separator());
    }

    private void renderString(String key, boolean forceFull) {
        runIo(() -> {
            long length = session.strlen(key);
            byte[] value = length > LARGE_VALUE && !forceFull
                    ? session.getrange(key, 0, PREVIEW_BYTES - 1)
                    : session.get(key);
            return new StringValue(length, value == null ? new byte[0] : value, length > LARGE_VALUE && !forceFull);
        }, value -> {
            VBox box = new VBox(6);
            TextArea editor = new TextArea();
            editor.setPrefRowCount(18);
            ComboBox<String> view = new ComboBox<>(FXCollections.observableArrayList("文本", "JSON 美化", "十六进制"));
            view.setValue(printable(value.bytes()) ? "文本" : "十六进制");
            Runnable render = () -> editor.setText(switch (view.getValue()) {
                case "十六进制" -> HexFormat.ofDelimiter(" ").formatHex(value.bytes());
                case "JSON 美化" -> prettyJson(text(value.bytes()));
                default -> text(value.bytes());
            });
            view.setOnAction(e -> render.run());
            render.run();
            Button save = new Button("保存");
            save.setDisable(value.preview());
            save.setOnAction(e -> {
                try {
                    byte[] bytes = "十六进制".equals(view.getValue())
                            ? parseHex(editor.getText()) : editor.getText().getBytes(StandardCharsets.UTF_8);
                    mutate(() -> { session.set(key, bytes); return null; }, () -> loadKey(key));
                } catch (IllegalArgumentException error) {
                    warn("十六进制格式无效: " + error.getMessage());
                }
            });
            HBox tools = new HBox(6, view, save, new Label("大小: " + value.length() + " 字节"));
            box.getChildren().addAll(tools, editor);
            if (value.preview()) {
                Button full = new Button("加载完整值（超过 1 MiB）");
                full.setOnAction(e -> renderString(key, true));
                box.getChildren().add(1, full);
            }
            while (details.getChildren().size() > 3) details.getChildren().removeLast();
            details.getChildren().add(box);
        });
    }

    private void renderHash(String key, long cursor) {
        runIo(() -> session.hscan(key, cursor, COLLECTION_PAGE), page -> {
            List<ValueRow> rows = page.entries().stream()
                    .map(e -> new ValueRow(display(e.field()), display(e.value()), e.field(), e.value())).toList();
            TableView<ValueRow> table = table("Field", "Value", rows);
            Button add = new Button("新增");
            add.setOnAction(e -> promptValue("Field", field -> promptBytes("Value", value ->
                    mutate(() -> session.hset(key, utf8(field), value), () -> renderHash(key, 0)))));
            Button update = new Button("修改值");
            update.setOnAction(e -> {
                ValueRow row = table.getSelectionModel().getSelectedItem();
                if (row != null) promptBytes("Value", value ->
                        mutate(() -> session.hset(key, row.rawA(), value), () -> renderHash(key, 0)));
            });
            Button remove = new Button("删除");
            remove.setOnAction(e -> {
                ValueRow row = table.getSelectionModel().getSelectedItem();
                if (row != null) mutate(() -> session.hdel(key, row.rawA()), () -> renderHash(key, 0));
            });
            Button more = new Button("加载更多");
            more.setDisable(page.cursor() == 0);
            more.setOnAction(e -> renderHash(key, page.cursor()));
            replaceValueArea(new HBox(6, add, update, remove, more), table);
        });
    }

    private void renderList(String key, long offset) {
        runIo(() -> new ListPage(session.llen(key), session.lrange(key, offset, offset + COLLECTION_PAGE - 1)), page -> {
            List<ValueRow> rows = new ArrayList<>();
            for (int i = 0; i < page.values().size(); i++) {
                byte[] value = page.values().get(i);
                rows.add(new ValueRow(Long.toString(offset + i), display(value), null, value));
            }
            TableView<ValueRow> table = table("Index", "Value", rows);
            Button head = new Button("头部插入");
            head.setOnAction(e -> promptBytes("值", value -> mutate(() -> session.lpush(key, value), () -> renderList(key, 0))));
            Button tail = new Button("尾部插入");
            tail.setOnAction(e -> promptBytes("值", value -> mutate(() -> session.rpush(key, value), () -> renderList(key, offset))));
            Button update = new Button("修改");
            update.setOnAction(e -> {
                ValueRow row = table.getSelectionModel().getSelectedItem();
                if (row != null) promptBytes("新值", value -> mutate(() -> {
                    session.lset(key, Long.parseLong(row.a()), value); return null;
                }, () -> renderList(key, offset)));
            });
            Button remove = new Button("按值删除");
            remove.setOnAction(e -> {
                ValueRow row = table.getSelectionModel().getSelectedItem();
                if (row != null) mutate(() -> session.lrem(key, 1, row.rawB()), () -> renderList(key, offset));
            });
            Button prev = new Button("上一页");
            prev.setDisable(offset == 0);
            prev.setOnAction(e -> renderList(key, Math.max(0, offset - COLLECTION_PAGE)));
            Button next = new Button("下一页");
            next.setDisable(offset + page.values().size() >= page.length());
            next.setOnAction(e -> renderList(key, offset + COLLECTION_PAGE));
            replaceValueArea(new HBox(6, head, tail, update, remove, prev, next), table);
        });
    }

    private void renderSet(String key, long cursor) {
        runIo(() -> session.sscan(key, cursor, COLLECTION_PAGE), page -> {
            List<ValueRow> rows = page.values().stream().map(v -> new ValueRow(display(v), "", v, null)).toList();
            TableView<ValueRow> table = table("Member", "", rows);
            Button add = new Button("新增");
            add.setOnAction(e -> promptBytes("Member", value -> mutate(() -> session.sadd(key, value), () -> renderSet(key, 0))));
            Button remove = new Button("删除");
            remove.setOnAction(e -> {
                ValueRow row = table.getSelectionModel().getSelectedItem();
                if (row != null) mutate(() -> session.srem(key, row.rawA()), () -> renderSet(key, 0));
            });
            Button more = new Button("加载更多");
            more.setDisable(page.cursor() == 0);
            more.setOnAction(e -> renderSet(key, page.cursor()));
            replaceValueArea(new HBox(6, add, remove, more), table);
        });
    }

    private void renderZSet(String key, long cursor) {
        runIo(() -> session.zscan(key, cursor, COLLECTION_PAGE), page -> {
            List<ValueRow> rows = page.entries().stream().map(v ->
                    new ValueRow(display(v.member()), Double.toString(v.score()), v.member(), null)).toList();
            TableView<ValueRow> table = table("Member", "Score", rows);
            Button add = new Button("新增");
            add.setOnAction(e -> promptBytes("Member", member -> promptValue("Score", score ->
                    updateScore(key, member, score))));
            Button update = new Button("修改分数");
            update.setOnAction(e -> {
                ValueRow row = table.getSelectionModel().getSelectedItem();
                if (row != null) promptValue("Score", score -> updateScore(key, row.rawA(), score));
            });
            Button remove = new Button("删除");
            remove.setOnAction(e -> {
                ValueRow row = table.getSelectionModel().getSelectedItem();
                if (row != null) mutate(() -> session.zrem(key, row.rawA()), () -> renderZSet(key, 0));
            });
            Button more = new Button("加载更多");
            more.setDisable(page.cursor() == 0);
            more.setOnAction(e -> renderZSet(key, page.cursor()));
            replaceValueArea(new HBox(6, add, update, remove, more), table);
        });
    }

    private void updateScore(String key, byte[] member, String score) {
        try {
            double number = Double.parseDouble(score);
            mutate(() -> session.zadd(key, number, member), () -> renderZSet(key, 0));
        } catch (NumberFormatException error) {
            warn("Score 必须是数字");
        }
    }

    private TableView<ValueRow> table(String first, String second, List<ValueRow> rows) {
        TableView<ValueRow> table = new TableView<>(FXCollections.observableArrayList(rows));
        TableColumn<ValueRow, String> a = new TableColumn<>(first);
        a.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().a()));
        a.setPrefWidth(220);
        table.getColumns().add(a);
        if (!second.isEmpty()) {
            TableColumn<ValueRow, String> b = new TableColumn<>(second);
            b.setCellValueFactory(v -> new ReadOnlyStringWrapper(v.getValue().b()));
            b.setPrefWidth(360);
            table.getColumns().add(b);
        }
        table.setPrefHeight(430);
        return table;
    }

    private void replaceValueArea(Node tools, Node content) {
        while (details.getChildren().size() > 3) details.getChildren().removeLast();
        details.getChildren().addAll(tools, content);
    }

    private void createKey() {
        ChoiceDialog<String> type = new ChoiceDialog<>("String", "String", "Hash", "List", "Set", "ZSet");
        type.setTitle("新建 Redis 键");
        type.setHeaderText("选择值类型");
        type.showAndWait().ifPresent(kind -> promptPair("键名", "初始值", (key, value) -> {
            if (key.isBlank()) { warn("键名不能为空"); return; }
            mutate(() -> {
                byte[] bytes = utf8(value);
                switch (kind) {
                    case "String" -> session.set(key, bytes);
                    case "Hash" -> session.hset(key, utf8("field"), bytes);
                    case "List" -> session.rpush(key, bytes);
                    case "Set" -> session.sadd(key, bytes);
                    case "ZSet" -> session.zadd(key, 0, bytes);
                    default -> throw new IllegalArgumentException("未知类型: " + kind);
                }
                return null;
            }, this::refreshKeys);
        }));
    }

    private void confirmDelete(String key) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "确定删除键 \"" + key + "\"？", ButtonType.YES, ButtonType.NO);
        alert.setHeaderText(null);
        alert.showAndWait();
        if (alert.getResult() == ButtonType.YES) mutate(() -> session.del(key), this::refreshKeys);
    }

    private void promptValue(String label, Consumer<String> action) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(label);
        dialog.setHeaderText(null);
        dialog.setContentText(label + ":");
        dialog.showAndWait().ifPresent(action);
    }

    private void promptBytes(String label, Consumer<byte[]> action) {
        Dialog<byte[]> dialog = new Dialog<>();
        dialog.setTitle(label);
        ButtonType ok = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        ComboBox<String> mode = new ComboBox<>(FXCollections.observableArrayList("文本", "十六进制"));
        mode.setValue("文本");
        TextArea value = new TextArea();
        value.setPrefRowCount(5);
        VBox content = new VBox(6, mode, value);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(button -> {
            if (button != ok) return null;
            try {
                return "十六进制".equals(mode.getValue()) ? parseHex(value.getText()) : utf8(value.getText());
            } catch (IllegalArgumentException error) {
                warn("十六进制格式无效: " + error.getMessage());
                return null;
            }
        });
        dialog.showAndWait().ifPresent(action);
    }

    private void promptPair(String first, String second, PairConsumer action) {
        Dialog<List<String>> dialog = new Dialog<>();
        dialog.setTitle(first + " / " + second);
        ButtonType ok = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        TextField a = new TextField();
        TextArea b = new TextArea();
        b.setPrefRowCount(4);
        GridPane grid = new GridPane();
        grid.setHgap(8); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.addRow(0, new Label(first + ":"), a);
        grid.addRow(1, new Label(second + ":"), b);
        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(button -> button == ok ? List.of(a.getText(), b.getText()) : null);
        dialog.showAndWait().ifPresent(values -> action.accept(values.get(0), values.get(1)));
    }

    private <T> void mutate(IoSupplier<T> operation, Runnable after) {
        setBusy(true, "保存中...");
        runIo(operation, ignored -> {
            setBusy(false, "保存成功");
            after.run();
        });
    }

    private <T> void runIo(IoSupplier<T> operation, Consumer<T> success) {
        io.submit(() -> {
            try {
                T value = operation.get();
                Platform.runLater(() -> success.accept(value));
            } catch (Exception error) {
                Platform.runLater(() -> showError(error));
            }
        });
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        loadMore.setDisable(value);
        status.setText(message);
    }

    private void showError(Exception error) {
        setBusy(false, "错误: " + message(error));
        warn(message(error));
    }

    private static void warn(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    @Override
    public void close() {
        io.shutdownNow();
        RedisSession current = session;
        session = null;
        if (current != null) manager.closeRedisSession(current);
    }

    private final class KeyCell extends TreeCell<TreeEntry> {
        @Override
        protected void updateItem(TreeEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null); setContextMenu(null); return;
            }
            setText(item.label());
            if (item.key() == null) { setContextMenu(null); return; }
            MenuItem copy = new MenuItem("复制键名");
            copy.setOnAction(e -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(item.key());
                Clipboard.getSystemClipboard().setContent(content);
            });
            MenuItem rename = new MenuItem("重命名");
            rename.setOnAction(e -> promptValue("新键名", value ->
                    mutate(() -> { session.rename(item.key(), value); return null; }, RedisKeyBrowserPane.this::refreshKeys)));
            MenuItem delete = new MenuItem("删除");
            delete.setOnAction(e -> confirmDelete(item.key()));
            setContextMenu(new ContextMenu(copy, rename, delete));
        }
    }

    private static byte[] parseHex(String value) {
        return HexFormat.of().parseHex(value.replaceAll("\\s+", ""));
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }

    private static String display(byte[] value) {
        return printable(value) ? text(value) : "0x" + HexFormat.of().formatHex(value);
    }

    private static boolean printable(byte[] value) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
            return text.chars().noneMatch(c -> Character.isISOControl(c) && c != '\r' && c != '\n' && c != '\t');
        } catch (CharacterCodingException error) {
            return false;
        }
    }

    private static String prettyJson(String value) {
        StringBuilder out = new StringBuilder();
        int indent = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (char c : value.toCharArray()) {
            if (quoted) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
                continue;
            }
            switch (c) {
                case '"' -> { quoted = true; out.append(c); }
                case '{', '[' -> { out.append(c).append('\n'); indent++; out.append("  ".repeat(indent)); }
                case '}', ']' -> { out.append('\n'); indent = Math.max(0, indent - 1); out.append("  ".repeat(indent)).append(c); }
                case ',' -> out.append(c).append('\n').append("  ".repeat(indent));
                case ':' -> out.append(": ");
                default -> { if (!Character.isWhitespace(c)) out.append(c); }
            }
        }
        return out.toString();
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @FunctionalInterface private interface IoSupplier<T> { T get() throws Exception; }
    @FunctionalInterface private interface PairConsumer { void accept(String first, String second); }
    private record TreeEntry(String label, String key) { @Override public String toString() { return label; } }
    private record KeyMeta(String type, long ttl) {}
    private record StringValue(long length, byte[] bytes, boolean preview) {}
    private record ListPage(long length, List<byte[]> values) {}
    private record ValueRow(String a, String b, byte[] rawA, byte[] rawB) {}
}
