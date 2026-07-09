package com.datacube.fx;

import com.datacube.core.ConnectionHelper;
import com.datacube.sqleditor.QueryResult;
import com.datacube.sqleditor.SqlExecutor;
import com.datacube.sqleditor.SqlFormatter;
import com.datacube.sqleditor.SqlScriptSplitter;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/**
 * SQL 窗口 UI 控制器。
 *
 * <p>解耦特性：
 * <ul>
 *   <li>连接信息通过 {@link #setConnectionInfo} 注入，不直接访问 MainController</li>
 *   <li>UI 仅依赖 JavaFX，业务逻辑全部调用 {@code com.datacube.sqleditor} 包</li>
 *   <li>Connection 由本类按需 lazy 创建，使用后立即关闭（每个 execute 调用）</li>
 * </ul>
 */
public class SqlEditorController {

    // 连接信息（外部注入）
    private String pgUrl;
    private String pgUser;
    private String pgPass;
    private String pgSchema;

    // UI 组件
    private TextArea editorArea;
    private TableView<ObservableList<String>> resultTable;
    private Label statusLabel;
    private Button executeBtn, formatBtn, clearBtn, cancelBtn;
    private TextField schemaField;

    // 状态
    private volatile boolean running = false;
    private Thread workerThread;

    public Node createUI() {
        VBox root = new VBox(8);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-font-family: 'Microsoft YaHei', 'Segoe UI', sans-serif; -fx-font-size: 13px;");

        root.getChildren().addAll(
                createToolbar(),
                createEditorArea(),
                resultAreaContainer(),
                createStatusBar()
        );

        VBox.setVgrow(editorArea, Priority.NEVER);
        VBox.setVgrow(resultAreaContainer(), Priority.ALWAYS);

        return root;
    }

    private Node createToolbar() {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);

        Label schemaLabel = new Label("Schema:");
        schemaField = new TextField();
        schemaField.setPromptText("迁移 Tab 中的 schema");
        schemaField.setPrefWidth(180);

        executeBtn = new Button("执行 (F5)");
        executeBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        executeBtn.setOnAction(e -> onExecute());

        formatBtn = new Button("美化 SQL");
        formatBtn.setOnAction(e -> onFormat());

        clearBtn = new Button("清空");
        clearBtn.setOnAction(e -> {
            editorArea.clear();
            resultTable.getItems().clear();
            resultTable.getColumns().clear();
            statusLabel.setText("就绪");
        });

        cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-background-color: #f44336; -fx-text-fill: white;");
        cancelBtn.setVisible(false);
        cancelBtn.setOnAction(e -> onCancel());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        box.getChildren().addAll(schemaLabel, schemaField, executeBtn, formatBtn, clearBtn, cancelBtn, spacer);
        return box;
    }

    private Node createEditorArea() {
        editorArea = new TextArea();
        editorArea.setPromptText("-- 在此输入 SQL，支持多条以分号分隔\nSELECT 1;\nSELECT * FROM pg_tables;");
        editorArea.setFont(Font.font("Consolas", 14));
        editorArea.setWrapText(false);
        editorArea.setPrefRowCount(10);
        editorArea.setStyle(
                "-fx-font-family: 'Consolas', 'Courier New', monospace;" +
                "-fx-font-size: 14px;" +
                "-fx-background-color: #fafafa;"
        );

        // F5 快捷键执行
        editorArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.F5) {
                e.consume();
                onExecute();
            }
        });

        TitledPane pane = new TitledPane("SQL 编辑器", editorArea);
        pane.setCollapsible(true);
        pane.setExpanded(true);
        pane.setPrefHeight(260);
        VBox.setVgrow(pane, Priority.NEVER);
        return pane;
    }

    private VBox resultAreaContainer() {
        resultTable = new TableView<>();
        resultTable.setPlaceholder(new Label("（无结果）"));
        resultTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TitledPane pane = new TitledPane("结果", resultTable);
        pane.setCollapsible(false);
        pane.setExpanded(true);

        VBox box = new VBox(pane);
        VBox.setVgrow(pane, Priority.ALWAYS);
        return box;
    }

    private Node createStatusBar() {
        statusLabel = new Label("就绪");
        statusLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");
        HBox box = new HBox(statusLabel);
        box.setPadding(new Insets(4, 0, 0, 0));
        return box;
    }

    // ==================== 业务方法 ====================

    /**
     * 由 MainController 调用：注入连接信息。
     * Schema 可单独通过 {@link #setSchema} 覆盖。
     */
    public void setConnectionInfo(String url, String user, String pass, String schema) {
        this.pgUrl = url;
        this.pgUser = user;
        this.pgPass = pass;
        this.pgSchema = schema;
        if (schemaField != null && schema != null) {
            schemaField.setText(schema);
        }
    }

    /**
     * 由 MainController 调用：当迁移 Tab 的 schema 字段变化时同步过来。
     */
    public void setSchema(String schema) {
        this.pgSchema = schema;
        if (schemaField != null) {
            schemaField.setText(schema);
        }
    }

    private void onFormat() {
        String sql = editorArea.getText();
        if (sql.trim().isEmpty()) return;
        try {
            String formatted = SqlFormatter.format(sql);
            editorArea.setText(formatted);
            statusLabel.setText("已美化");
        } catch (Exception e) {
            showAlert("美化失败：" + e.getMessage());
        }
    }

    private void onExecute() {
        if (running) return;

        String sql = editorArea.getText();
        if (sql.trim().isEmpty()) {
            showAlert("请输入 SQL");
            return;
        }
        if (pgUrl == null || pgUrl.isEmpty()) {
            showAlert("请先在迁移 Tab 配置 PostgreSQL 连接");
            return;
        }

        final String schema = schemaField.getText().trim();
        final String effectiveSchema = schema.isEmpty() ? pgSchema : schema;

        List<String> stmts = SqlScriptSplitter.split(sql);
        if (stmts.isEmpty()) {
            statusLabel.setText("未发现可执行语句");
            return;
        }

        // 异步执行
        running = true;
        setButtonsRunning(true);
        statusLabel.setText("执行中...");

        workerThread = new Thread(() -> doExecute(sql, stmts, effectiveSchema), "SqlEditor-Worker");
        workerThread.start();
    }

    private void doExecute(String originalSql, List<String> stmts, String schema) {
        long totalStart = System.currentTimeMillis();
        final List<SqlExecutor.ScriptOutcome>[] outcomesRef = new List[]{null};
        final String[] errMsgRef = new String[]{null};

        try (Connection conn = DriverManager.getConnection(pgUrl, pgUser, pgPass)) {
            ConnectionHelper.loadDrivers(null); // 触发驱动加载（无 logger 不输出）
            outcomesRef[0] = SqlExecutor.executeScript(conn, originalSql, schema);
        } catch (SQLException e) {
            errMsgRef[0] = e.getMessage();
        }

        long totalElapsed = System.currentTimeMillis() - totalStart;
        final List<SqlExecutor.ScriptOutcome> outcomes = outcomesRef[0];
        final String errMsg = errMsgRef[0];
        Platform.runLater(() -> {
            running = false;
            setButtonsRunning(false);

            if (errMsg != null) {
                showResultError(errMsg, totalElapsed);
                statusLabel.setText("连接失败");
            } else {
                showScriptResults(outcomes, totalElapsed);
            }
        });
    }

    private void showResultError(String msg, long elapsed) {
        resultTable.getColumns().clear();
        resultTable.getItems().clear();

        TableColumn<ObservableList<String>, String> col = new TableColumn<>("错误");
        col.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().get(0)));
        resultTable.getColumns().add(col);

        ObservableList<ObservableList<String>> singleRow = FXCollections.observableArrayList();
        singleRow.add(FXCollections.observableArrayList(msg));
        resultTable.setItems(singleRow);

        statusLabel.setText("ERROR - " + elapsed + "ms");
        statusLabel.setStyle("-fx-text-fill: #d32f2f; -fx-font-size: 12px;");
    }

    private void showScriptResults(List<SqlExecutor.ScriptOutcome> outcomes, long totalElapsed) {
        resultTable.getColumns().clear();
        resultTable.getItems().clear();

        if (outcomes.isEmpty()) {
            statusLabel.setText("无结果");
            return;
        }

        // 多语句：每个语句一行摘要
        if (outcomes.size() > 1) {
            TableColumn<ObservableList<String>, String> c1 = new TableColumn<>("#");
            c1.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get(0)));
            TableColumn<ObservableList<String>, String> c2 = new TableColumn<>("类型");
            c2.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get(1)));
            TableColumn<ObservableList<String>, String> c3 = new TableColumn<>("耗时");
            c3.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get(2)));
            TableColumn<ObservableList<String>, String> c4 = new TableColumn<>("结果");
            c4.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get(3)));
            resultTable.getColumns().addAll(c1, c2, c3, c4);

            ObservableList<ObservableList<String>> data = FXCollections.observableArrayList();
            for (SqlExecutor.ScriptOutcome o : outcomes) {
                QueryResult r = o.result;
                String kind = r.kind.name();
                String time = r.elapsedMillis + "ms";
                String result;
                switch (r.kind) {
                    case QUERY:  result = r.rows.size() + " rows"; break;
                    case UPDATE: result = r.updateCount + " affected"; break;
                    case ERROR:  result = "ERR: " + truncate(r.errorMessage, 80); break;
                    default:     result = "";
                }
                data.add(FXCollections.observableArrayList(
                        String.valueOf(o.index), kind, time, result));
            }
            resultTable.setItems(data);
            statusLabel.setText("共 " + outcomes.size() + " 条语句 - " + totalElapsed + "ms");
        } else {
            // 单语句：完整结果显示
            SqlExecutor.ScriptOutcome o = outcomes.get(0);
            QueryResult r = o.result;
            switch (r.kind) {
                case QUERY:
                    showQueryResult(r);
                    statusLabel.setText("OK - " + r.rows.size() + " rows - " + r.elapsedMillis + "ms");
                    statusLabel.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 12px;");
                    break;
                case UPDATE:
                    statusLabel.setText("OK - " + r.updateCount + " rows affected - " + r.elapsedMillis + "ms");
                    statusLabel.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 12px;");
                    break;
                case ERROR:
                    showResultError(r.errorMessage, r.elapsedMillis);
                    break;
            }
        }
    }

    private void showQueryResult(QueryResult r) {
        ObservableList<ObservableList<String>> data = FXCollections.observableArrayList();
        for (List<Object> row : r.rows) {
            ObservableList<String> rowData = FXCollections.observableArrayList();
            for (Object cell : row) {
                rowData.add(cell == null ? "" : cell.toString());
            }
            data.add(rowData);
        }
        resultTable.setItems(data);

        resultTable.getColumns().clear();
        for (int i = 0; i < r.columns.size(); i++) {
            final int colIdx = i;
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(r.columns.get(i));
            col.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                    colIdx < d.getValue().size() ? d.getValue().get(colIdx) : ""));
            resultTable.getColumns().add(col);
        }
    }

    private void onCancel() {
        if (workerThread != null) {
            workerThread.interrupt();
            statusLabel.setText("已请求取消");
        }
    }

    private void setButtonsRunning(boolean isRunning) {
        executeBtn.setDisable(isRunning);
        formatBtn.setDisable(isRunning);
        clearBtn.setDisable(isRunning);
        cancelBtn.setVisible(isRunning);
        cancelBtn.setDisable(!isRunning);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private void showAlert(String msg) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
            alert.setHeaderText(null);
            alert.showAndWait();
        });
    }
}