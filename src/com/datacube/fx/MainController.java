package com.datacube.fx;

import com.datacube.cli.ConsoleLogger;
import com.datacube.core.ConnectionHelper;
import com.datacube.source.OracleExporter;
import com.datacube.target.PgImporter;
import com.datacube.target.PgVerifier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.sql.*;

public class MainController {

    // 连接输入
    private TextField oraUrlField, oraUserField, pgUrlField, pgUserField, pgSchemaField;
    private PasswordField oraPassField, pgPassField;

    // 配置
    private Spinner<Integer> concurrencySpinner;
    private CheckBox boolCheck;

    // 状态
    private ProgressBar progressBar;
    private Label statusLabel;
    private TextArea logArea;
    private Button[] actionButtons;
    private Button cancelBtn;

    // 业务逻辑
    private FxLogger fxLogger;
    private OracleExporter exporter;
    private PgImporter importer;
    private PgVerifier verifier;

    // SQL 窗口（解耦依赖：通过 setConnectionInfo/setSchema 注入）
    private final SqlEditorController sqlEditor = new SqlEditorController();

    private Connection oraConn;
    private String oraUrl, oraUser, oraPass, pgUrl, pgUser, pgPass, pgSchema;
    private volatile boolean shuttingDown = false;

    public VBox createUI() {
        VBox root = new VBox();
        root.setStyle("-fx-font-family: 'Microsoft YaHei', 'Segoe UI', sans-serif; -fx-font-size: 13px;");

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab migrationTab = new Tab("数据迁移", createMigrationContent());
        Tab sqlTab = new Tab("SQL 窗口", sqlEditor.createUI());
        tabPane.getTabs().addAll(migrationTab, sqlTab);

        root.getChildren().add(tabPane);
        VBox.setVgrow(tabPane, Priority.ALWAYS);
        return root;
    }

    /** 迁移 Tab 内容：原 UI 拆出。 */
    private VBox createMigrationContent() {
        VBox content = new VBox(10);
        content.setPadding(new Insets(15));

        TitledPane oraPane = createOraclePane();
        TitledPane pgPane = createPgPane();
        HBox configBox = createConfigBox();
        FlowPane buttonPane = createButtonPane();
        VBox progressBox = createProgressBox();
        VBox logBox = createLogBox();

        content.getChildren().addAll(oraPane, pgPane, configBox, buttonPane, progressBox, logBox);
        VBox.setVgrow(logBox, Priority.ALWAYS);

        // 初始化 logger（日志区域创建后）
        fxLogger = new FxLogger(logArea, progressBar, statusLabel);
        return content;
    }

    private TitledPane createOraclePane() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));

        oraUrlField = new TextField("jdbc:oracle:thin:@127.0.0.1:1521/orcl");
        oraUrlField.setPrefWidth(400);
        oraUserField = new TextField("scott");
        oraPassField = new PasswordField();

        grid.add(new Label("JDBC URL:"), 0, 0);
        grid.add(oraUrlField, 1, 0);
        grid.add(new Label("用户名:"), 0, 1);
        grid.add(oraUserField, 1, 1);
        grid.add(new Label("密码:"), 0, 2);
        grid.add(oraPassField, 1, 2);

        ColumnConstraints col = new ColumnConstraints();
        col.setMinWidth(70);
        grid.getColumnConstraints().add(col);

        TitledPane pane = new TitledPane("Oracle 连接", grid);
        pane.setCollapsible(false);
        return pane;
    }

    private TitledPane createPgPane() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));

        pgUrlField = new TextField("jdbc:postgresql://127.0.0.1:5432/postgres");
        pgUrlField.setPrefWidth(400);
        pgUserField = new TextField("postgres");
        pgPassField = new PasswordField();
        pgSchemaField = new TextField("scott");

        grid.add(new Label("JDBC URL:"), 0, 0);
        grid.add(pgUrlField, 1, 0);
        grid.add(new Label("用户名:"), 0, 1);
        grid.add(pgUserField, 1, 1);
        grid.add(new Label("密码:"), 0, 2);
        grid.add(pgPassField, 1, 2);
        grid.add(new Label("Schema:"), 0, 3);
        grid.add(pgSchemaField, 1, 3);

        ColumnConstraints col = new ColumnConstraints();
        col.setMinWidth(70);
        grid.getColumnConstraints().add(col);

        TitledPane pane = new TitledPane("PostgreSQL 连接", grid);
        pane.setCollapsible(false);
        return pane;
    }

    private HBox createConfigBox() {
        HBox box = new HBox(15);
        box.setPadding(new Insets(5));
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        concurrencySpinner = new Spinner<>(1, 100, 20);
        concurrencySpinner.setPrefWidth(80);
        concurrencySpinner.setEditable(true);

        boolCheck = new CheckBox("布尔值转换 (0/1→TRUE/FALSE)");

        box.getChildren().addAll(new Label("并发上限:"), concurrencySpinner, boolCheck);
        return box;
    }

    private FlowPane createButtonPane() {
        FlowPane pane = new FlowPane(10, 10);
        pane.setPadding(new Insets(5, 0, 5, 0));

        Button testBtn = new Button("测试连接");
        Button ddlBtn = new Button("导出 DDL");
        Button dataBtn = new Button("导出数据");
        Button fullBtn = new Button("完整导入");
        Button incrBtn = new Button("增量导入");
        Button allBtn = new Button("一键全部");
        Button verifyBtn = new Button("验证");
        cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-weight: bold;");
        cancelBtn.setVisible(false);
        cancelBtn.setOnAction(e -> onCancel());

        // 一键全部按钮突出显示
        allBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");

        actionButtons = new Button[]{testBtn, ddlBtn, dataBtn, fullBtn, incrBtn, allBtn, verifyBtn, cancelBtn};

        testBtn.setOnAction(e -> onTestConnection());
        ddlBtn.setOnAction(e -> runAsync(() -> onExportDDL()));
        dataBtn.setOnAction(e -> runAsync(() -> onExportData()));
        fullBtn.setOnAction(e -> runAsync(() -> onImport(false)));
        incrBtn.setOnAction(e -> runAsync(() -> onImport(true)));
        allBtn.setOnAction(e -> runAsync(() -> onAll()));
        verifyBtn.setOnAction(e -> runAsync(() -> onVerify()));

        pane.getChildren().addAll(actionButtons);
        return pane;
    }

    private VBox createProgressBox() {
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        statusLabel = new Label("就绪");
        VBox box = new VBox(5, progressBar, statusLabel);
        return box;
    }

    private VBox createLogBox() {
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;");
        logArea.setPrefRowCount(15);

        TitledPane pane = new TitledPane("日志输出", logArea);
        pane.setCollapsible(false);

        VBox box = new VBox(pane);
        VBox.setVgrow(pane, Priority.ALWAYS);
        return box;
    }

    // ==================== 业务逻辑 ====================

    private boolean readInputs() {
        oraUrl = oraUrlField.getText().trim();
        oraUser = oraUserField.getText().trim().toUpperCase();
        oraPass = oraPassField.getText();
        pgUrl = pgUrlField.getText().trim();
        pgUser = pgUserField.getText().trim();
        pgPass = pgPassField.getText();
        pgSchema = pgSchemaField.getText().trim();

        if (oraUrl.isEmpty() || oraUser.isEmpty()) {
            showAlert("请输入 Oracle 连接信息");
            return false;
        }
        if (pgUrl.isEmpty() || pgUser.isEmpty() || pgSchema.isEmpty()) {
            showAlert("请输入 PostgreSQL 连接信息");
            return false;
        }
        return true;
    }

    private boolean connect() {
        ConnectionHelper.loadDrivers(fxLogger);

        try {
            oraConn = ConnectionHelper.openAndTest(oraUrl, oraUser, oraPass, "Oracle", fxLogger);
        } catch (SQLException e) {
            return false;
        }

        try (Connection pgConn = ConnectionHelper.openAndTest(pgUrl, pgUser, pgPass, "PostgreSQL", fxLogger)) {
            ConnectionHelper.ensureSchema(pgConn, pgSchema, fxLogger);
        } catch (SQLException e) {
            try { oraConn.close(); } catch (Exception ignored) {}
            oraConn = null;
            return false;
        }

        // 连接验证成功后同步给 SQL 窗口（解耦注入）
        sqlEditor.setConnectionInfo(pgUrl, pgUser, pgPass, pgSchema);
        return true;
    }

    private void initModules() {
        // Spinner setEditable(true) 后用户可能输入越界值，需 clamp
        int concurrency = concurrencySpinner.getValue();
        if (concurrency < 1) concurrency = 1;
        if (concurrency > 100) concurrency = 100;
        boolean convertBool = boolCheck.isSelected();

        exporter = new OracleExporter(fxLogger);
        exporter.setMaxConcurrency(concurrency);
        exporter.setConvertBool(convertBool);

        importer = new PgImporter(fxLogger);
        importer.setMaxConcurrency(concurrency);

        verifier = new PgVerifier(fxLogger);
    }

    private void onTestConnection() {
        if (!readInputs()) return;
        runAsync(() -> {
            fxLogger.logSection("测试连接");
            ConnectionHelper.loadDrivers(fxLogger);
            try {
                try (Connection c = ConnectionHelper.openAndTest(oraUrl, oraUser, oraPass, "Oracle", fxLogger)) {}
            } catch (SQLException ignored) {}
            try {
                try (Connection c = ConnectionHelper.openAndTest(pgUrl, pgUser, pgPass, "PostgreSQL", fxLogger)) {}
            } catch (SQLException ignored) {}
        });
    }

    private void onExportDDL() {
        if (!readInputs() || !connect()) return;
        initModules();
        try {
            exporter.exportDDL(oraConn, oraUser, pgSchema);
        } catch (Exception e) {
            fxLogger.logErr("导出 DDL 失败: " + e.getMessage());
        } finally {
            closeOraConn();
        }
    }

    private void onExportData() {
        if (!readInputs() || !connect()) return;
        initModules();
        try {
            exporter.exportData(oraConn, oraUrl, oraUser, oraPass, pgSchema);
        } catch (Exception e) {
            fxLogger.logErr("导出数据失败: " + e.getMessage());
        } finally {
            closeOraConn();
        }
    }

    private void onImport(boolean incremental) {
        if (!readInputs() || !connect()) return;
        initModules();
        closeOraConn();
        try {
            importer.importToPg(pgUrl, pgUser, pgPass, oraUser, pgSchema, incremental);
        } catch (Exception e) {
            fxLogger.logErr("导入失败: " + e.getMessage());
        }
    }

    private void onAll() {
        if (!readInputs() || !connect()) return;
        initModules();
        try {
            exporter.exportDDL(oraConn, oraUser, pgSchema);
            exporter.exportData(oraConn, oraUrl, oraUser, oraPass, pgSchema);
            closeOraConn();
            importer.importToPg(pgUrl, pgUser, pgPass, oraUser, pgSchema, true);
            verifier.verify(pgUrl, pgUser, pgPass, pgSchema);
        } catch (Exception e) {
            fxLogger.logErr("操作失败: " + e.getMessage());
        } finally {
            closeOraConn();
        }
    }

    private void onVerify() {
        if (!readInputs()) return;
        initModules();
        try {
            verifier.verify(pgUrl, pgUser, pgPass, pgSchema);
        } catch (Exception e) {
            fxLogger.logErr("验证失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private void runAsync(Runnable task) {
        // 禁用其他按钮，显示取消按钮
        for (Button btn : actionButtons) {
            if (btn == cancelBtn) continue;
            btn.setDisable(true);
        }
        cancelBtn.setVisible(true);
        cancelBtn.setDisable(false);
        progressBar.setProgress(-1);
        statusLabel.setText("执行中...");

        new Thread(() -> {
            try {
                task.run();
            } catch (Exception e) {
                fxLogger.logErr("异常: " + e.getMessage());
                // 保留完整堆栈到日志文件，便于事后排查
                fxLogger.logToFile(ConsoleLogger.stackTrace(e));
            } finally {
                Platform.runLater(() -> {
                    setButtonsDisabled(false);
                    cancelBtn.setVisible(false);
                    progressBar.setProgress(1.0);
                    statusLabel.setText("完成");
                    // 延迟 1.5s 重置进度条，避免视觉“突然消失”
                    Timeline delay = new Timeline(new KeyFrame(Duration.seconds(1.5), ev -> {
                        if (!controller_shutting_down()) {
                            progressBar.setProgress(0);
                            statusLabel.setText("就绪");
                        }
                    }));
                    delay.play();
                });
                // 不再在此处关闭日志文件，避免连续任务日志丢失；
                // 统一在窗口关闭时由 shutdown() 关闭。
            }
        }, "DataCube-Worker").start();
    }

    private void onCancel() {
        fxLogger.logWarn("收到取消请求，正在停止...");
        cancelBtn.setDisable(true);
        if (exporter != null) exporter.cancel();
        if (importer != null) importer.cancel();
    }

    /**
     * 关闭资源（仅由窗口关闭事件调用）
     */
    public void shutdown() {
        shuttingDown = true;
        try {
            if (oraConn != null && !oraConn.isClosed()) oraConn.close();
        } catch (Exception ignored) {}
        if (fxLogger != null) fxLogger.closeLog();
    }

    /** 供 Timeline 延迟回调查询是否正在关闭 */
    private boolean controller_shutting_down() {
        return shuttingDown;
    }

    /**
     * 当前是否有任务在运行（供窗口关闭事件查询）
     */
    public boolean isRunning() {
        return progressBar.getProgress() < 0;
    }

    private void setButtonsDisabled(boolean disabled) {
        for (Button btn : actionButtons) {
            btn.setDisable(disabled);
        }
    }

    private void closeOraConn() {
        if (oraConn != null) {
            try { oraConn.close(); } catch (Exception ignored) {}
            oraConn = null;
        }
    }

    private void showAlert(String msg) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
            alert.setHeaderText(null);
            alert.showAndWait();
        });
    }
}
