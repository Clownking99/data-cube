package com.datacube.fx;

import com.datacube.cli.ConsoleLogger;
import com.datacube.core.ConnectionHelper;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.migration.MigrationCancellation;
import com.datacube.migration.OracleExporter;
import com.datacube.migration.PgImporter;
import com.datacube.migration.PgVerifier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.sql.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class MainController {

    private final FxTaskScope tasks;
    private final Executor cleanupExecutor;

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

    private Connection oraConn;
    private String oraUrl, oraUser, oraPass, pgUrl, pgUser, pgPass, pgSchema;
    private volatile boolean shuttingDown = false;
    private final AtomicReference<MigrationCancellation> activeOperation = new AtomicReference<>();

    MainController(FxTaskScope tasks) {
        this(tasks, Runnable::run);
    }

    MainController(FxTaskScope tasks, Executor cleanupExecutor) {
        this.tasks = java.util.Objects.requireNonNull(tasks, "tasks");
        this.cleanupExecutor = java.util.Objects.requireNonNull(cleanupExecutor, "cleanupExecutor");
    }

    /** 迁移 Tab 内容：原 UI 拆出。作为独立面板嵌入 AppShell。 */
    public VBox createMigrationContent() {
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
        fxLogger = new FxLogger(logArea, progressBar, statusLabel, tasks::dispatch);
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

        testBtn.setOnAction(e -> startAsync(false, this::onTestConnection));
        ddlBtn.setOnAction(e -> startAsync(true, this::onExportDDL));
        dataBtn.setOnAction(e -> startAsync(true, this::onExportData));
        fullBtn.setOnAction(e -> startAsync(true, operation -> onImport(operation, false)));
        incrBtn.setOnAction(e -> startAsync(true, operation -> onImport(operation, true)));
        allBtn.setOnAction(e -> startAsync(true, this::onAll));
        verifyBtn.setOnAction(e -> startAsync(true, this::onVerify));

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

    private boolean connect(MigrationCancellation cancellation) {
        ConnectionHelper.loadDrivers(fxLogger);

        Connection opened = null;
        try {
            opened = cancellation.register(
                    ConnectionHelper.openAndTest(oraUrl, oraUser, oraPass, "Oracle", fxLogger));
            oraConn = opened;
            cancellation.checkCancelled();
        } catch (SQLException | CancellationException e) {
            cancellation.release(opened);
            oraConn = null;
            return false;
        }

        Connection pgConn = null;
        try {
            pgConn = cancellation.register(
                    ConnectionHelper.openAndTest(pgUrl, pgUser, pgPass, "PostgreSQL", fxLogger));
            ConnectionHelper.ensureSchema(pgConn, pgSchema, fxLogger);
            cancellation.checkCancelled();
        } catch (SQLException | CancellationException e) {
            closeOraConn(cancellation);
            return false;
        } finally {
            cancellation.release(pgConn);
        }

        return true;
    }

    private void initModules(MigrationCancellation cancellation) {
        // Spinner setEditable(true) 后用户可能输入越界值，需 clamp
        int concurrency = concurrencySpinner.getValue();
        if (concurrency < 1) concurrency = 1;
        if (concurrency > 100) concurrency = 100;
        boolean convertBool = boolCheck.isSelected();

        exporter = new OracleExporter(fxLogger, cancellation);
        exporter.setMaxConcurrency(concurrency);
        exporter.setConvertBool(convertBool);

        importer = new PgImporter(fxLogger, cancellation);
        importer.setMaxConcurrency(concurrency);

        verifier = new PgVerifier(fxLogger, cancellation);
    }

    private void onTestConnection(MigrationCancellation cancellation) {
        fxLogger.logSection("测试连接");
        ConnectionHelper.loadDrivers(fxLogger);
        testConnection(cancellation, oraUrl, oraUser, oraPass, "Oracle");
        cancellation.checkCancelled();
        testConnection(cancellation, pgUrl, pgUser, pgPass, "PostgreSQL");
    }

    private void testConnection(MigrationCancellation cancellation, String url, String user,
                                String password, String label) {
        Connection connection = null;
        try {
            connection = cancellation.register(
                    ConnectionHelper.openAndTest(url, user, password, label, fxLogger));
        } catch (SQLException ignored) {
        } finally {
            cancellation.release(connection);
        }
    }

    private void onExportDDL(MigrationCancellation cancellation) {
        if (!connect(cancellation)) return;
        try {
            exporter.exportDDL(oraConn, oraUser, pgSchema);
        } catch (CancellationException ignored) {
        } catch (Exception e) {
            if (!cancellation.isCancelled()) fxLogger.logErr("导出 DDL 失败: " + e.getMessage());
        } finally {
            closeOraConn(cancellation);
        }
    }

    private void onExportData(MigrationCancellation cancellation) {
        if (!connect(cancellation)) return;
        try {
            exporter.exportData(oraConn, oraUrl, oraUser, oraPass, pgSchema);
        } catch (CancellationException ignored) {
        } catch (Exception e) {
            if (!cancellation.isCancelled()) fxLogger.logErr("导出数据失败: " + e.getMessage());
        } finally {
            closeOraConn(cancellation);
        }
    }

    private void onImport(MigrationCancellation cancellation, boolean incremental) {
        if (!connect(cancellation)) return;
        closeOraConn(cancellation);
        try {
            importer.importToPg(pgUrl, pgUser, pgPass, oraUser, pgSchema, incremental);
        } catch (CancellationException ignored) {
        } catch (Exception e) {
            if (!cancellation.isCancelled()) fxLogger.logErr("导入失败: " + e.getMessage());
        }
    }

    private void onAll(MigrationCancellation cancellation) {
        if (!connect(cancellation)) return;
        try {
            exporter.exportDDL(oraConn, oraUser, pgSchema);
            cancellation.checkCancelled();
            exporter.exportData(oraConn, oraUrl, oraUser, oraPass, pgSchema);
            cancellation.checkCancelled();
            closeOraConn(cancellation);
            importer.importToPg(pgUrl, pgUser, pgPass, oraUser, pgSchema, true);
            cancellation.checkCancelled();
            verifier.verify(pgUrl, pgUser, pgPass, pgSchema);
        } catch (CancellationException ignored) {
        } catch (Exception e) {
            if (!cancellation.isCancelled()) fxLogger.logErr("操作失败: " + e.getMessage());
        } finally {
            closeOraConn(cancellation);
        }
    }

    private void onVerify(MigrationCancellation cancellation) {
        try {
            verifier.verify(pgUrl, pgUser, pgPass, pgSchema);
        } catch (CancellationException ignored) {
        } catch (Exception e) {
            if (!cancellation.isCancelled()) fxLogger.logErr("验证失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private void startAsync(boolean initializeModules, Consumer<MigrationCancellation> task) {
        if (shuttingDown || !readInputs()) return;
        MigrationCancellation cancellation = new MigrationCancellation();
        if (initializeModules) initModules(cancellation);
        runAsync(cancellation, task);
    }

    private void runAsync(MigrationCancellation cancellation,
                          Consumer<MigrationCancellation> task) {
        if (!activeOperation.compareAndSet(null, cancellation)) return;
        // 禁用其他按钮，显示取消按钮
        for (Button btn : actionButtons) {
            if (btn == cancelBtn) continue;
            btn.setDisable(true);
        }
        cancelBtn.setVisible(true);
        cancelBtn.setDisable(false);
        progressBar.setProgress(-1);
        statusLabel.setText("执行中...");

        try {
            tasks.submit(() -> {
                try {
                    task.accept(cancellation);
                    return null;
                } catch (CancellationException ignored) {
                    return null;
                } finally {
                    cancellation.close();
                    try {
                        cancellation.awaitCleanup();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    activeOperation.compareAndSet(cancellation, null);
                }
            }, ignored -> finishTask(), error -> {
                fxLogger.logErr("异常: " + error.getMessage());
                // 保留完整堆栈到日志文件，便于事后排查
                fxLogger.logToFile(ConsoleLogger.stackTrace(error));
                finishTask();
            });
        } catch (RuntimeException rejected) {
            cancellation.close();
            activeOperation.compareAndSet(cancellation, null);
            if (!shuttingDown) finishTask();
            throw rejected;
        }
    }

    private void finishTask() {
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
        // 不再在此处关闭日志文件，避免连续任务日志丢失；
        // 统一在窗口关闭时由 shutdown() 关闭。
    }

    private void onCancel() {
        fxLogger.logWarn("收到取消请求，正在停止...");
        cancelBtn.setDisable(true);
        MigrationCancellation operation = activeOperation.get();
        if (operation != null) operation.cancelAsync(cleanupExecutor);
    }

    /**
     * 关闭资源（仅由窗口关闭事件调用）
     */
    public void shutdown() {
        shuttingDown = true;
        MigrationCancellation operation = activeOperation.getAndSet(null);
        if (operation != null) operation.cancelAsync(cleanupExecutor);
        tasks.close();
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

    private void closeOraConn(MigrationCancellation cancellation) {
        Connection connection = oraConn;
        oraConn = null;
        cancellation.release(connection);
    }

    private void showAlert(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
