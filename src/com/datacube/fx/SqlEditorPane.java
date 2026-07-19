package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.AppSettings.CommentMode;
import com.datacube.export.XlsxWriter;
import com.datacube.service.ConnectionManager;
import com.datacube.service.ObjectTreeService;
import com.datacube.sqleditor.SqlFormatter;
import com.datacube.sqleditor.SqlScriptSplitter;
import com.datacube.spi.SqlRunner;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.SchemaInfo;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.spi.model.TableInfo;
import com.datacube.spi.model.ViewInfo;

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
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SQL 编辑器面板：绑定 {@link SessionContext} 活动连接，经 {@link SqlRunner} 执行。
 *
 * <p>升级自原 {@code SqlEditorController}：连接不再手工注入，而是取自活动连接；
 * 执行委托 provider 的 {@link SqlRunner}，方言差异（schema 切换）由 provider 处理。
 */
public final class SqlEditorPane {

    /** 常见 SQL 关键字（大写形式，补全时展示）。 */
    private static final List<String> SQL_KEYWORDS = Arrays.asList(
            "SELECT", "FROM", "WHERE", "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "OFFSET",
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "ALTER", "DROP",
            "TABLE", "VIEW", "INDEX", "SEQUENCE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL",
            "OUTER", "CROSS", "ON", "AS", "AND", "OR", "NOT", "NULL", "IS", "IN", "EXISTS",
            "BETWEEN", "LIKE", "ILIKE", "DISTINCT", "UNION", "ALL", "CASE", "WHEN", "THEN",
            "ELSE", "END", "ASC", "DESC", "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE",
            "CAST", "WITH", "RETURNING", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "DEFAULT",
            "CONSTRAINT", "UNIQUE", "CHECK", "TRUE", "FALSE", "BEGIN", "COMMIT", "ROLLBACK");

    private final SessionContext session;
    private final ConnectionManager connections;
    private final ObjectTreeService treeSvc;
    private final AppSettings settings;

    /** 预热的元数据名称（表/视图/schema），线程安全。 */
    private final Set<String> metaNames = ConcurrentHashMap.newKeySet();
    /** 已预热的 connId（每连接只预热一次）。 */
    private final Set<String> prewarmed = ConcurrentHashMap.newKeySet();
    private final ExecutorService metaPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SqlMeta-Prewarm");
        t.setDaemon(true);
        return t;
    });

    private final VBox root = new VBox(8);
    private TextArea editorArea;
    private TableView<ObservableList<String>> resultTable;
    private TextArea planArea;
    private TitledPane resultPane;
    private Label statusLabel;
    private TextField schemaField;
    private Button executeBtn, explainBtn, formatBtn, clearBtn;
    private Button exportResultBtn;
    private CheckBox analyzeCheck;

    private volatile boolean running = false;
    /** 最近一次单条查询结果（用于注释显示模式切换后即时重渲染表头）；非查询视图时为 null。 */
    private QueryResult lastQueryResult;

    public SqlEditorPane(SessionContext session, ConnectionManager connections, ObjectTreeService treeSvc,
                         AppSettings settings) {
        this.session = session;
        this.connections = connections;
        this.treeSvc = treeSvc;
        this.settings = settings;
        build();
        // 注释显示模式变化 → 对当前查询结果即时重渲染表头（不重跑 SQL）
        settings.commentModeProperty().addListener((obs, o, n) -> {
            if (lastQueryResult != null) showQueryResult(lastQueryResult);
        });
    }

    public Node getNode() {
        return root;
    }

    private void build() {
        root.setPadding(new Insets(10));
        root.setStyle("-fx-font-family: 'Microsoft YaHei', 'Segoe UI', sans-serif; -fx-font-size: 13px;");
        VBox container = resultContainer();
        root.getChildren().addAll(toolbar(), editor(), container, statusBar());
        VBox.setVgrow(container, Priority.ALWAYS);
    }

    private Node toolbar() {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);

        schemaField = new TextField();
        schemaField.setPromptText("schema（可选）");
        schemaField.setPrefWidth(160);

        executeBtn = new Button("执行 (F5)");
        executeBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        executeBtn.setOnAction(e -> onExecute());

        explainBtn = new Button("执行计划");
        explainBtn.setOnAction(e -> onExplain());
        analyzeCheck = new CheckBox("ANALYZE(实际执行)");

        formatBtn = new Button("美化 SQL");
        formatBtn.setOnAction(e -> onFormat());

        clearBtn = new Button("清空");
        clearBtn.setOnAction(e -> {
            editorArea.clear();
            resultTable.getItems().clear();
            resultTable.getColumns().clear();
            planArea.clear();
            useTable();
            lastQueryResult = null;
            exportResultBtn.setDisable(true);
            statusLabel.setText("就绪");
        });

        exportResultBtn = new Button("导出结果");
        exportResultBtn.setDisable(true);
        exportResultBtn.setOnAction(e -> onExportResult());

        box.getChildren().addAll(new Label("Schema:"), schemaField, executeBtn, explainBtn, analyzeCheck, formatBtn, exportResultBtn, clearBtn);
        return box;
    }

    private Node editor() {
        editorArea = new TextArea();
        editorArea.setPromptText("-- 在此输入 SQL，支持多条以分号分隔\nSELECT 1;");
        editorArea.setFont(Font.font("Consolas", 14));
        editorArea.setWrapText(false);
        editorArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 14px; -fx-background-color: #fafafa;");
        editorArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.F5) {
                e.consume();
                onExecute();
            }
        });
        // 自动补全：关键字 + 预热的元数据名称（Ctrl+Space 强制触发）。
        new SqlAutoComplete(editorArea, this::completionCandidates);
        installMetadataPrewarm();
        TitledPane pane = new TitledPane("SQL 编辑器", editorArea);
        pane.setCollapsible(true);
        pane.setExpanded(true);
        pane.setPrefHeight(240);
        return pane;
    }

    private VBox resultContainer() {
        resultTable = new TableView<>();
        resultTable.setPlaceholder(new Label("（无结果）"));
        // UNCONSTRAINED：保留列自然宽度与底部横向滚动条（宽表友好）。
        resultTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        // 执行计划文本区（等宽、只读、不换行）；与结果表格共用同一 TitledPane，按需切换。
        planArea = new TextArea();
        planArea.setEditable(false);
        planArea.setWrapText(false);
        planArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");
        resultPane = new TitledPane("结果", resultTable);
        resultPane.setCollapsible(false);
        VBox box = new VBox(resultPane);
        VBox.setVgrow(resultPane, Priority.ALWAYS);
        return box;
    }

    private Node statusBar() {
        statusLabel = new Label("就绪");
        statusLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");
        HBox box = new HBox(statusLabel);
        box.setPadding(new Insets(4, 0, 0, 0));
        return box;
    }

    private void onFormat() {
        String sql = editorArea.getText();
        if (sql.trim().isEmpty()) return;
        try {
            editorArea.setText(SqlFormatter.format(sql));
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
        ConnConfig active = session.getActiveConnection();
        if (active == null) {
            showAlert("请先在左侧选择一个活动连接");
            return;
        }
        final String connId = active.id();
        final String schema = schemaField.getText().trim();

        running = true;
        setButtonsRunning(true);
        statusLabel.setText("执行中...");
        statusLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        new Thread(() -> doExecute(connId, sql, schema), "SqlEditor-Worker").start();
    }

    private void doExecute(String connId, String sql, String schema) {
        long t0 = System.currentTimeMillis();
        List<ScriptOutcome> outcomes = null;
        String errMsg = null;
        try {
            Connection conn = connections.acquire(connId);
            SqlRunner runner = connections.provider(connId).sqlRunner();
            outcomes = runner.executeScript(conn, sql, schema.isEmpty() ? null : schema);
        } catch (Exception e) {
            errMsg = e.getMessage();
        }
        long elapsed = System.currentTimeMillis() - t0;
        final List<ScriptOutcome> fOutcomes = outcomes;
        final String fErr = errMsg;
        Platform.runLater(() -> {
            running = false;
            setButtonsRunning(false);
            if (fErr != null) {
                showError(fErr, elapsed);
            } else {
                showScriptResults(fOutcomes, elapsed);
            }
        });
    }

    // ---------- 执行计划（EXPLAIN / EXPLAIN ANALYZE） ----------

    private void onExplain() {
        if (running) return;
        String text = editorArea.getText();
        if (text.trim().isEmpty()) {
            showAlert("请输入 SQL");
            return;
        }
        ConnConfig active = session.getActiveConnection();
        if (active == null) {
            showAlert("请先在左侧选择一个活动连接");
            return;
        }
        List<String> stmts = SqlScriptSplitter.split(text);
        if (stmts.isEmpty()) {
            showAlert("请输入 SQL");
            return;
        }
        final String sql = stmts.get(0);
        final int total = stmts.size();
        final boolean analyze = analyzeCheck.isSelected();
        // 写类语句勾 ANALYZE 会实际执行，二次确认
        if (analyze && isWriteStatement(sql)) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "EXPLAIN ANALYZE 会实际执行该语句（可能产生写入）。确定继续？",
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }
        final String connId = active.id();
        final String schema = schemaField.getText().trim();

        running = true;
        setButtonsRunning(true);
        statusLabel.setText(analyze ? "执行计划(ANALYZE)中..." : "生成执行计划中...");
        statusLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        new Thread(() -> doExplain(connId, sql, schema, analyze, total), "SqlEditor-Explain").start();
    }

    private void doExplain(String connId, String sql, String schema, boolean analyze, int total) {
        QueryResult result = null;
        String errMsg = null;
        try {
            Connection conn = connections.acquire(connId);
            var provider = connections.provider(connId);
            String explainSql = provider.dialect().explainSql(sql, analyze);
            result = provider.sqlRunner().execute(conn, explainSql, schema.isEmpty() ? null : schema);
        } catch (Exception e) {
            errMsg = e.getMessage();
        }
        final QueryResult fResult = result;
        final String fErr = errMsg;
        Platform.runLater(() -> {
            running = false;
            setButtonsRunning(false);
            if (fErr != null) {
                showError(fErr, 0);
            } else if (fResult.kind == QueryResult.Kind.ERROR) {
                showError(fResult.errorMessage, fResult.elapsedMillis);
            } else if (fResult.kind == QueryResult.Kind.QUERY) {
                StringBuilder sb = new StringBuilder();
                for (List<Object> row : fResult.rows) {
                    if (!row.isEmpty() && row.get(0) != null) sb.append(row.get(0));
                    sb.append('\n');
                }
                showPlan(sb.toString(), fResult.elapsedMillis, total);
            } else {
                showError("未返回执行计划", fResult.elapsedMillis);
            }
        });
    }

    private void showPlan(String planText, long elapsed, int totalStmts) {
        lastQueryResult = null;
        exportResultBtn.setDisable(true);
        planArea.setText(planText);
        usePlan();
        String status = "执行计划 - " + elapsed + "ms";
        if (totalStmts > 1) status += "（已对第 1 条语句，共 " + totalStmts + " 条）";
        statusLabel.setText(status);
        statusLabel.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 12px;");
    }

    /** 将结果区切回表格视图。 */
    private void useTable() {
        if (resultPane.getContent() != resultTable) resultPane.setContent(resultTable);
        resultPane.setText("结果");
    }

    /** 将结果区切到执行计划文本视图。 */
    private void usePlan() {
        if (resultPane.getContent() != planArea) resultPane.setContent(planArea);
        resultPane.setText("结果（执行计划）");
    }

    /** 启发式判断是否写类语句（非 SELECT/WITH/VALUES/SHOW/TABLE/EXPLAIN 开头）。 */
    private static boolean isWriteStatement(String sql) {
        String s = sql.trim();
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '(' || Character.isWhitespace(s.charAt(i)))) i++;
        s = s.substring(i).toUpperCase();
        return !(s.startsWith("SELECT") || s.startsWith("WITH") || s.startsWith("VALUES")
                || s.startsWith("SHOW") || s.startsWith("TABLE") || s.startsWith("EXPLAIN"));
    }

    private void showError(String msg, long elapsed) {
        lastQueryResult = null;
        useTable();
        exportResultBtn.setDisable(true);
        resultTable.getColumns().clear();
        resultTable.getItems().clear();
        TableColumn<ObservableList<String>, String> col = new TableColumn<>("错误");
        col.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().get(0)));
        resultTable.getColumns().add(col);
        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        rows.add(FXCollections.observableArrayList(msg));
        resultTable.setItems(rows);
        statusLabel.setText("ERROR - " + elapsed + "ms");
        statusLabel.setStyle("-fx-text-fill: #d32f2f; -fx-font-size: 12px;");
    }

    private void showScriptResults(List<ScriptOutcome> outcomes, long totalElapsed) {
        lastQueryResult = null;
        useTable();
        exportResultBtn.setDisable(true);
        resultTable.getColumns().clear();
        resultTable.getItems().clear();
        if (outcomes == null || outcomes.isEmpty()) {
            statusLabel.setText("无结果");
            return;
        }
        if (outcomes.size() > 1) {
            addColumn("#", 0);
            addColumn("类型", 1);
            addColumn("耗时", 2);
            addColumn("结果", 3);
            ObservableList<ObservableList<String>> data = FXCollections.observableArrayList();
            for (ScriptOutcome o : outcomes) {
                QueryResult r = o.result();
                data.add(FXCollections.observableArrayList(
                        String.valueOf(o.index()), r.kind.name(), r.elapsedMillis + "ms", summarize(r)));
            }
            resultTable.setItems(data);
            statusLabel.setText("共 " + outcomes.size() + " 条语句 - " + totalElapsed + "ms");
            statusLabel.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 12px;");
        } else {
            QueryResult r = outcomes.get(0).result();
            switch (r.kind) {
                case QUERY -> {
                    showQueryResult(r);
                    statusLabel.setText("OK - " + r.rows.size() + " rows - " + r.elapsedMillis + "ms");
                    statusLabel.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 12px;");
                }
                case UPDATE -> {
                    statusLabel.setText("OK - " + r.updateCount + " rows affected - " + r.elapsedMillis + "ms");
                    statusLabel.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 12px;");
                }
                case ERROR -> showError(r.errorMessage, r.elapsedMillis);
            }
        }
    }

    private static String summarize(QueryResult r) {
        return switch (r.kind) {
            case QUERY -> r.rows.size() + " rows";
            case UPDATE -> r.updateCount + " affected";
            case ERROR -> "ERR: " + truncate(r.errorMessage, 80);
        };
    }

    private void addColumn(String title, int idx) {
        TableColumn<ObservableList<String>, String> c = new TableColumn<>(title);
        c.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                idx < d.getValue().size() ? d.getValue().get(idx) : ""));
        resultTable.getColumns().add(c);
    }

    private void showQueryResult(QueryResult r) {
        lastQueryResult = r;
        ObservableList<ObservableList<String>> data = FXCollections.observableArrayList();
        for (List<Object> row : r.rows) {
            ObservableList<String> rowData = FXCollections.observableArrayList();
            for (Object cell : row) {
                rowData.add(cell == null ? "" : cell.toString());
            }
            data.add(rowData);
        }
        resultTable.getColumns().clear();
        List<String> comments = r.columnComments;
        for (int i = 0; i < r.columns.size(); i++) {
            String name = r.columns.get(i);
            String comment = (comments != null && i < comments.size()) ? comments.get(i) : null;
            TableColumn<ObservableList<String>, String> col = buildQueryColumn(name, comment, i);
            col.setPrefWidth(estimateColumnWidth(name, r.rows, i));
            resultTable.getColumns().add(col);
        }
        resultTable.setItems(data);
        exportResultBtn.setDisable(r.rows.isEmpty());
    }

    /** 将当前查询结果导出为 Excel(.xlsx)。 */
    private void onExportResult() {
        QueryResult r = lastQueryResult;
        if (r == null || r.kind != QueryResult.Kind.QUERY || r.rows.isEmpty()) {
            showAlert("没有可导出的查询结果");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出结果到 Excel");
        chooser.setInitialFileName("query_result.xlsx");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel 文件", "*.xlsx"));
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        File out = chooser.showSaveDialog(owner);
        if (out == null) return;

        final List<String> columns = r.columns;
        final List<List<Object>> rows = r.rows;
        statusLabel.setText("导出中...");
        statusLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");
        new Thread(() -> {
            String err = null;
            try {
                XlsxWriter.write(out, columns, sink -> {
                    for (List<Object> row : rows) sink.row(row);
                });
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                if (out.exists()) out.delete();
            }
            final String fErr = err;
            Platform.runLater(() -> {
                if (fErr == null) {
                    statusLabel.setText("已导出: " + out.getAbsolutePath());
                    statusLabel.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 12px;");
                } else {
                    statusLabel.setText("导出失败: " + fErr);
                    statusLabel.setStyle("-fx-text-fill: #d32f2f; -fx-font-size: 12px;");
                }
            });
        }, "Result-Export").start();
    }

    /** 构建带注释表头的查询列，表头展现方式由当前 {@link CommentMode} 决定。 */
    private TableColumn<ObservableList<String>, String> buildQueryColumn(String name, String comment, int idx) {
        TableColumn<ObservableList<String>, String> c = new TableColumn<>();
        c.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                idx < d.getValue().size() ? d.getValue().get(idx) : ""));
        applyColumnHeader(c, name, comment);
        return c;
    }

    /** 根据当前注释显示模式设置列头（纯文本 / 悬停 Tooltip / 固定两行）。 */
    private void applyColumnHeader(TableColumn<ObservableList<String>, String> c, String name, String comment) {
        boolean hasComment = comment != null && !comment.isEmpty();
        CommentMode mode = settings.getCommentMode();
        if (!hasComment || mode == CommentMode.OFF) {
            c.setGraphic(null);
            c.setText(name);
            return;
        }
        if (mode == CommentMode.INLINE) {
            Label nameLabel = new Label(name);
            Label commentLabel = new Label(comment);
            commentLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");
            VBox box = new VBox(1, nameLabel, commentLabel);
            c.setText("");
            c.setGraphic(box);
        } else { // HOVER
            Label nameLabel = new Label(name);
            Tooltip tip = new Tooltip(name + "\n" + comment);
            tip.setWrapText(true);
            tip.setMaxWidth(360);
            Tooltip.install(nameLabel, tip);
            c.setText("");
            c.setGraphic(nameLabel);
        }
    }

    /** 估算列宽：取表头与前若干行内容的最大字符数，换算像素并裁剪到 [60, 360]。 */
    private static double estimateColumnWidth(String header, List<List<Object>> rows, int idx) {
        int maxLen = header == null ? 0 : header.length();
        int sample = Math.min(rows.size(), 100);
        for (int r = 0; r < sample; r++) {
            List<Object> row = rows.get(r);
            if (idx < row.size() && row.get(idx) != null) {
                int len = row.get(idx).toString().length();
                if (len > maxLen) maxLen = len;
            }
        }
        double px = maxLen * 8.0 + 24;
        return Math.max(60, Math.min(360, px));
    }

    private void setButtonsRunning(boolean isRunning) {
        executeBtn.setDisable(isRunning);
        explainBtn.setDisable(isRunning);
        formatBtn.setDisable(isRunning);
        clearBtn.setDisable(isRunning);
    }

    // ---------- 自动补全：候选词 + 元数据预热 ----------

    /** 补全候选：SQL 关键字 + 已预热的元数据名称。 */
    private Collection<String> completionCandidates() {
        List<String> all = new ArrayList<>(SQL_KEYWORDS.size() + metaNames.size());
        all.addAll(SQL_KEYWORDS);
        all.addAll(metaNames);
        return all;
    }

    /** 监听活动连接变化，在后台预热元数据名称（每连接一次）。 */
    private void installMetadataPrewarm() {
        session.activeConnectionProperty().addListener((obs, o, c) -> {
            if (c != null) prewarm(c);
        });
        ConnConfig cur = session.getActiveConnection();
        if (cur != null) prewarm(cur);
    }

    /**
     * 后台加载 schema/表/视图名称并入库。best-effort：与连接树共享同一 JDBC 连接，
     * 若并发冲突或失败则静默跳过并允许下次重试，不影响关键字补全。
     */
    private void prewarm(ConnConfig cfg) {
        final String connId = cfg.id();
        final String database = cfg.database();
        if (!prewarmed.add(connId)) return;
        metaPool.submit(() -> {
            try {
                List<String> schemas = new ArrayList<>();
                if (treeSvc.hasSchemaLevel(connId)) {
                    for (SchemaInfo s : treeSvc.schemas(connId, database)) schemas.add(s.name());
                } else {
                    schemas.add(null);
                }
                List<String> collected = new ArrayList<>();
                for (String s : schemas) {
                    if (s != null) collected.add(s);
                    try {
                        for (TableInfo t : treeSvc.tables(connId, s)) collected.add(t.name());
                        for (ViewInfo v : treeSvc.views(connId, s)) collected.add(v.name());
                    } catch (Exception ignore) {
                        // 单个 schema 读取失败不阻断其余
                    }
                    if (collected.size() > 5000) break;
                }
                metaNames.addAll(collected);
            } catch (Exception e) {
                prewarmed.remove(connId); // 允许下次重试
            }
        });
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private void showAlert(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
