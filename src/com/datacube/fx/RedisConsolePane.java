package com.datacube.fx;

import com.datacube.redis.RedisConsoleSupport;
import com.datacube.redis.RedisSession;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.model.ConnConfig;
import com.datacube.fx.task.FxSerialTaskQueue;
import com.datacube.fx.task.FxTaskRunner;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** redis-cli 风格的非阻塞 Redis 命令控制台。 */
public final class RedisConsolePane implements AutoCloseable {

    private final ConnConfig config;
    private final ConnectionManager manager;
    private final RedisSession session;
    private final FxSerialTaskQueue io;
    private final VBox root = new VBox(8);
    private final VBox output = new VBox(3);
    private final TextField input = new TextField();
    private final List<String> history = new ArrayList<>();
    private int historyIndex;

    public RedisConsolePane(ConnectionManager manager, ConnConfig config, FxTaskRunner runner) {
        this.manager = manager;
        this.config = config;
        int database = parseDatabase(config.database());
        ConstructionOwner construction = new ConstructionOwner();
        try {
            this.session = manager.openRedisSession(config.id(), database);
            construction.ownBlocking(() -> manager.closeRedisSession(session));
            this.io = new FxSerialTaskQueue(runner);
            construction.own(io::close);
            build(database);
            construction.commit();
        } catch (Throwable failure) {
            throw construction.close(failure).failure();
        }
    }

    public Node getNode() {
        return root;
    }

    private void build(int database) {
        root.setPadding(new Insets(10));
        Label title = new Label("Redis 控制台 · " + config.name() + " · db" + database);
        title.setStyle("-fx-font-weight: bold;");

        ScrollPane scroll = new ScrollPane(output);
        scroll.setFitToWidth(true);
        output.setPadding(new Insets(8));
        VBox.setVgrow(scroll, Priority.ALWAYS);

        input.setPromptText("输入 Redis 命令，支持单/双引号；Enter 执行，↑/↓ 浏览历史");
        input.setOnAction(e -> execute());
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.UP) {
                showHistory(-1);
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN) {
                showHistory(1);
                e.consume();
            }
        });
        root.getChildren().addAll(title, scroll, input);
        append("已连接，输入 PING 开始。", false);
    }

    private void execute() {
        String line = input.getText();
        final List<String> args;
        try {
            args = RedisConsoleSupport.tokenize(line);
        } catch (IllegalArgumentException error) {
            append(error.getMessage(), true);
            return;
        }
        if (args.isEmpty()) return;
        RedisConsoleSupport.CommandPolicy policy = RedisConsoleSupport.policy(args);
        if (policy == RedisConsoleSupport.CommandPolicy.BLOCKED) {
            append("一期不支持可能长期阻塞连接的命令: " + args.getFirst(), true);
            return;
        }
        if (policy == RedisConsoleSupport.CommandPolicy.CONFIRM) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "该命令可能造成数据丢失或服务中断，确定执行？", ButtonType.YES, ButtonType.NO);
            confirm.setHeaderText(String.join(" ", args));
            confirm.showAndWait();
            if (confirm.getResult() != ButtonType.YES) return;
        }

        history.add(line);
        historyIndex = history.size();
        input.clear();
        input.setDisable(true);
        append("> " + line, false);
        io.submit(() -> session.raw(args.toArray(String[]::new)), response -> {
            append(RedisConsoleSupport.format(response), false);
            enableInput();
        }, error -> {
            append(message(error), true);
            enableInput();
        });
    }

    private void showHistory(int delta) {
        if (history.isEmpty()) return;
        historyIndex = Math.max(0, Math.min(history.size(), historyIndex + delta));
        input.setText(historyIndex == history.size() ? "" : history.get(historyIndex));
        input.positionCaret(input.getText().length());
    }

    private void append(String text, boolean error) {
        Label line = new Label(text == null ? "" : text);
        line.setWrapText(true);
        line.setStyle(error
                ? "-fx-text-fill: -status-error; -fx-font-family: Consolas, monospace;"
                : "-fx-font-family: Consolas, monospace;");
        output.getChildren().add(line);
    }

    private void enableInput() {
        input.setDisable(false);
        input.requestFocus();
    }

    @Override
    public void close() {
        RedisPaneCloseSequence.close(
                io::close,
                () -> manager.closeRedisSession(session));
    }

    private static int parseDatabase(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
