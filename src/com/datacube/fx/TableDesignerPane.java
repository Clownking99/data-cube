package com.datacube.fx;

import com.datacube.service.TableDesignService;
import com.datacube.spi.ScriptErrorPolicy;
import com.datacube.spi.model.ColumnDraft;
import com.datacube.spi.model.DbType;
import com.datacube.spi.model.IndexDraft;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.spi.model.TableDraft;
import com.datacube.spi.model.TableRef;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 表设计器面板：新建或编辑表结构（列/主键/索引/表注释），生成方言 DDL 预览，
 * 经确认后执行。
 *
 * <p>编辑态用可变行模型（JavaFX property）承载，{@link #snapshot()} 转 {@link TableDraft}。
 * DDL 生成与执行委托 {@link TableDesignService}（方言封闭在 provider 层）；
 * 载入/执行走后台线程，回 {@link Platform#runLater} 更新 UI。
 */
public final class TableDesignerPane {

    /** PG 常见类型候选（可编辑下拉，允许自定义文本）。 */
    private static final List<String> PG_TYPES = Arrays.asList(
            "integer", "bigint", "smallint", "serial", "bigserial", "numeric(10,2)",
            "real", "double precision", "varchar(255)", "text", "char(1)", "boolean",
            "date", "timestamp", "timestamptz", "time", "uuid", "json", "jsonb", "bytea");

    /** Oracle 常见类型候选。 */
    private static final List<String> ORACLE_TYPES = Arrays.asList(
            "NUMBER", "NUMBER(10)", "NUMBER(10,2)", "INTEGER", "FLOAT",
            "VARCHAR2(255)", "VARCHAR2(50)", "CHAR(1)", "NVARCHAR2(255)",
            "CLOB", "NCLOB", "BLOB", "RAW(16)", "DATE", "TIMESTAMP");

    private final TableDesignService svc;
    private final String connId;
    private final TableRef table;   // null = 新建
    private final String schema;
    private final DbType dbType;
    private final boolean isNew;

    private final VBox root = new VBox(8);
    private TextField nameField;
    private TextField tableCommentField;
    private CodeArea previewArea;
    private Label statusLabel;
    private Button applyBtn;
    private Button refreshBtn;
    private TabPane tabPane;
    private Tab previewTab;

    private final ObservableList<ColumnRow> columnRows = FXCollections.observableArrayList();
    private final ObservableList<IndexRow> indexRows = FXCollections.observableArrayList();
    private TableView<ColumnRow> columnTable;
    private TableView<IndexRow> indexTable;

    /** 编辑现有表时的原始态（用于 alter diff 与刷新）；新建为 null。 */
    private volatile TableDraft original;
    private volatile boolean running = false;

    public TableDesignerPane(TableDesignService svc, String connId, TableRef table,
                             String schema, DbType dbType) {
        this.svc = svc;
        this.connId = connId;
        this.table = table;
        this.schema = schema;
        this.dbType = dbType;
        this.isNew = table == null;
        build();
        if (!isNew) reload();
    }

    public Node getNode() {
        return root;
    }

    // ---------- 构建 ----------

    private void build() {
        root.setPadding(new Insets(10));
        root.setStyle("-fx-font-family: 'Microsoft YaHei', 'Segoe UI', sans-serif; -fx-font-size: 13px;");
        root.getChildren().addAll(header(), tabs(), statusBar());
        VBox.setVgrow(tabPane, Priority.ALWAYS);
        if (isNew) addColumnRow();  // 新建先给一空行
    }

    private Node header() {
        Label schemaLabel = new Label("Schema: " + (schema == null || schema.isEmpty() ? "(默认)" : schema));
        Label nameLabel = new Label("表名:");
        nameField = new TextField(isNew ? "" : table.name());
        nameField.setPromptText("表名");
        nameField.setEditable(isNew);
        nameField.setPrefWidth(200);

        applyBtn = new Button("应用");
        applyBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        applyBtn.setOnAction(e -> onApply());

        refreshBtn = new Button("刷新");
        refreshBtn.setOnAction(e -> reload());
        refreshBtn.setDisable(isNew);

        HBox box = new HBox(8, schemaLabel, nameLabel, nameField, applyBtn, refreshBtn);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Node tabs() {
        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab columnsTab = new Tab("列", columnsPane());
        Tab indexesTab = new Tab("索引", indexesPane());
        Tab commentTab = new Tab("表注释", commentPane());
        previewTab = new Tab("DDL 预览", previewPane());

        tabPane.getTabs().addAll(columnsTab, indexesTab, commentTab, previewTab);
        // 切到预览页时刷新 DDL（纯文本生成，无需后台）
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, o, sel) -> {
            if (sel == previewTab) refreshPreview();
        });
        return tabPane;
    }

    private Node columnsPane() {
        columnTable = new TableView<>(columnRows);
        columnTable.setEditable(true);
        columnTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<ColumnRow, String> nameCol = new TableColumn<>("列名");
        nameCol.setCellValueFactory(cd -> cd.getValue().name);
        nameCol.setCellFactory(TextFieldTableCell.forTableColumn());

        TableColumn<ColumnRow, String> typeCol = new TableColumn<>("类型");
        typeCol.setCellValueFactory(cd -> cd.getValue().typeText);
        ObservableList<String> types = FXCollections.observableArrayList(
                dbType == DbType.ORACLE ? ORACLE_TYPES : PG_TYPES);
        typeCol.setCellFactory(tc -> {
            ComboBoxTableCell<ColumnRow, String> cell = new ComboBoxTableCell<>(types);
            cell.setComboBoxEditable(true);
            return cell;
        });

        TableColumn<ColumnRow, Boolean> nullableCol = new TableColumn<>("可空");
        nullableCol.setCellValueFactory(cd -> cd.getValue().nullable);
        nullableCol.setCellFactory(CheckBoxTableCell.forTableColumn(nullableCol));
        nullableCol.setEditable(true);

        TableColumn<ColumnRow, Boolean> pkCol = new TableColumn<>("主键");
        pkCol.setCellValueFactory(cd -> cd.getValue().pk);
        pkCol.setCellFactory(CheckBoxTableCell.forTableColumn(pkCol));
        pkCol.setEditable(true);

        TableColumn<ColumnRow, String> defCol = new TableColumn<>("默认值");
        defCol.setCellValueFactory(cd -> cd.getValue().defaultValue);
        defCol.setCellFactory(TextFieldTableCell.forTableColumn());

        TableColumn<ColumnRow, String> commentCol = new TableColumn<>("注释");
        commentCol.setCellValueFactory(cd -> cd.getValue().comment);
        commentCol.setCellFactory(TextFieldTableCell.forTableColumn());

        columnTable.getColumns().setAll(List.of(nameCol, typeCol, nullableCol, pkCol, defCol, commentCol));

        Button add = new Button("增行");
        add.setOnAction(e -> addColumnRow());
        Button del = new Button("删行");
        del.setOnAction(e -> removeSelected(columnTable, columnRows));
        Button up = new Button("上移");
        up.setOnAction(e -> move(columnTable, columnRows, -1));
        Button down = new Button("下移");
        down.setOnAction(e -> move(columnTable, columnRows, 1));
        HBox toolbar = new HBox(6, add, del, up, down);

        VBox box = new VBox(6, toolbar, columnTable);
        VBox.setVgrow(columnTable, Priority.ALWAYS);
        return box;
    }

    private Node indexesPane() {
        indexTable = new TableView<>(indexRows);
        indexTable.setEditable(true);
        indexTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<IndexRow, String> nameCol = new TableColumn<>("索引名");
        nameCol.setCellValueFactory(cd -> cd.getValue().name);
        nameCol.setCellFactory(TextFieldTableCell.forTableColumn());

        TableColumn<IndexRow, Boolean> uniqueCol = new TableColumn<>("唯一");
        uniqueCol.setCellValueFactory(cd -> cd.getValue().unique);
        uniqueCol.setCellFactory(CheckBoxTableCell.forTableColumn(uniqueCol));
        uniqueCol.setEditable(true);

        TableColumn<IndexRow, String> colsCol = new TableColumn<>("列（逗号分隔）");
        colsCol.setCellValueFactory(cd -> cd.getValue().columns);
        colsCol.setCellFactory(TextFieldTableCell.forTableColumn());

        indexTable.getColumns().setAll(List.of(nameCol, uniqueCol, colsCol));

        Button add = new Button("增行");
        add.setOnAction(e -> indexRows.add(new IndexRow("", false, "")));
        Button del = new Button("删行");
        del.setOnAction(e -> removeSelected(indexTable, indexRows));
        HBox toolbar = new HBox(6, add, del);

        VBox box = new VBox(6, toolbar, indexTable);
        VBox.setVgrow(indexTable, Priority.ALWAYS);
        return box;
    }

    private Node commentPane() {
        tableCommentField = new TextField();
        tableCommentField.setPromptText("表注释（可选）");
        VBox box = new VBox(6, new Label("表注释："), tableCommentField);
        box.setPadding(new Insets(6));
        return box;
    }

    private Node previewPane() {
        previewArea = new CodeArea();
        previewArea.setEditable(false);
        previewArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");
        previewArea.textProperty().addListener((obs, o, n) ->
                previewArea.setStyleSpans(0, SqlHighlighter.compute(n)));
        var css = getClass().getResource("/com/datacube/fx/sql-highlight.css");
        if (css != null) previewArea.getStylesheets().add(css.toExternalForm());
        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(previewArea);
        VBox box = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return box;
    }

    private Node statusBar() {
        statusLabel = new Label("就绪");
        statusLabel.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");
        HBox box = new HBox(statusLabel);
        box.setPadding(new Insets(4, 0, 0, 0));
        return box;
    }

    // ---------- 行操作 ----------

    private void addColumnRow() {
        columnRows.add(new ColumnRow("", dbType == DbType.ORACLE ? "VARCHAR2(255)" : "varchar(255)",
                true, false, "", ""));
    }

    private static <T> void removeSelected(TableView<T> tv, ObservableList<T> rows) {
        int i = tv.getSelectionModel().getSelectedIndex();
        if (i >= 0) rows.remove(i);
    }

    private static <T> void move(TableView<T> tv, ObservableList<T> rows, int delta) {
        int i = tv.getSelectionModel().getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= rows.size()) return;
        T tmp = rows.get(i);
        rows.set(i, rows.get(j));
        rows.set(j, tmp);
        tv.getSelectionModel().select(j);
    }

    // ---------- 载入现有表 ----------

    private void reload() {
        setStatus("载入中...", "-brand-fg-muted");
        new Thread(() -> {
            TableDraft d = null;
            String err = null;
            try {
                d = svc.load(connId, table);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final TableDraft fd = d;
            final String fErr = err;
            Platform.runLater(() -> {
                if (fErr != null) {
                    setStatus("载入失败: " + fErr, "-status-error");
                    return;
                }
                original = fd;
                populate(fd);
                setStatus("就绪", "-status-ok");
            });
        }, "TableDesigner-Load").start();
    }

    private void populate(TableDraft d) {
        Set<String> pk = new LinkedHashSet<>(d.primaryKey());
        columnRows.clear();
        for (ColumnDraft c : d.columns()) {
            columnRows.add(new ColumnRow(c.name(), c.typeText(), c.nullable(), pk.contains(c.name()),
                    c.defaultValue() == null ? "" : c.defaultValue(),
                    c.comment() == null ? "" : c.comment()));
        }
        indexRows.clear();
        for (IndexDraft ix : d.indexes()) {
            indexRows.add(new IndexRow(ix.name(), ix.unique(), String.join(", ", ix.columns())));
        }
        tableCommentField.setText(d.tableComment() == null ? "" : d.tableComment());
    }

    // ---------- 快照 / 校验 ----------

    private TableDraft snapshot() {
        List<ColumnDraft> cols = new ArrayList<>();
        List<String> pk = new ArrayList<>();
        for (ColumnRow r : columnRows) {
            String name = trim(r.name.get());
            cols.add(new ColumnDraft(name, trim(r.typeText.get()), r.nullable.get(),
                    blankToNull(r.defaultValue.get()), blankToNull(r.comment.get())));
            if (r.pk.get()) pk.add(name);
        }
        List<IndexDraft> ix = new ArrayList<>();
        for (IndexRow r : indexRows) {
            ix.add(new IndexDraft(trim(r.name.get()), r.unique.get(), splitColumns(r.columns.get())));
        }
        String tname = isNew ? trim(nameField.getText()) : table.name();
        String tschema = isNew ? schema : table.schema();
        String pkName = original == null ? null : original.primaryKeyName();
        return new TableDraft(tschema, tname, blankToNull(tableCommentField.getText()),
                cols, pk, pkName, ix);
    }

    /** 生成前校验：返回错误消息；null 表示通过。 */
    private String validate() {
        if (isNew && trim(nameField.getText()).isEmpty()) return "请输入表名";
        if (columnRows.isEmpty()) return "至少需要一列";
        Set<String> names = new LinkedHashSet<>();
        for (ColumnRow r : columnRows) {
            String name = trim(r.name.get());
            if (name.isEmpty()) return "存在空列名";
            if (trim(r.typeText.get()).isEmpty()) return "列 " + name + " 的类型为空";
            if (!names.add(name)) return "列名重复: " + name;
        }
        for (IndexRow r : indexRows) {
            if (trim(r.name.get()).isEmpty()) return "存在空索引名";
            if (splitColumns(r.columns.get()).isEmpty()) return "索引 " + r.name.get() + " 未指定列";
        }
        return null;
    }

    // ---------- 预览 / 应用 ----------

    private void refreshPreview() {
        String err = validate();
        if (err != null) {
            previewArea.replaceText("-- " + err);
            return;
        }
        try {
            TableDraft snap = snapshot();
            String ddl = isNew ? svc.previewCreate(connId, snap)
                    : svc.previewAlter(connId, original, snap);
            previewArea.replaceText(ddl.isEmpty() ? "-- 无变更" : ddl);
        } catch (Exception e) {
            previewArea.replaceText("-- 生成预览失败: " + e.getMessage());
        }
    }

    private void onApply() {
        if (running) return;
        String err = validate();
        if (err != null) {
            showAlert(err);
            return;
        }
        if (!isNew && original == null) {
            showAlert("原始表结构尚未载入完成，请稍候");
            return;
        }
        final String ddl;
        try {
            TableDraft snap = snapshot();
            ddl = isNew ? svc.previewCreate(connId, snap) : svc.previewAlter(connId, original, snap);
        } catch (Exception e) {
            showAlert("生成 DDL 失败: " + e.getMessage());
            return;
        }
        if (ddl.isBlank()) {
            showAlert("无变更，无需执行");
            return;
        }
        if (!confirmExecute(ddl)) return;

        running = true;
        applyBtn.setDisable(true);
        setStatus("执行中...", "-brand-fg-muted");
        new Thread(() -> {
            List<ScriptOutcome> outcomes = null;
            String execErr = null;
            try {
                outcomes = svc.execute(connId, ddl, this::askScriptError);
            } catch (Exception e) {
                execErr = e.getMessage();
            }
            final List<ScriptOutcome> fOut = outcomes;
            final String fErr = execErr;
            Platform.runLater(() -> {
                running = false;
                applyBtn.setDisable(false);
                if (fErr != null) {
                    setStatus("执行失败: " + fErr, "-status-error");
                    return;
                }
                String failed = firstError(fOut);
                if (failed != null) {
                    setStatus("执行完成但有失败: " + failed, "-status-error");
                } else {
                    setStatus("执行成功（" + (fOut == null ? 0 : fOut.size()) + " 条语句）", "-status-ok");
                    if (!isNew) reload();  // 编辑现有表：重载原始态
                }
            });
        }, "TableDesigner-Exec").start();
    }

    /** 预览确认对话框：展示完整 DDL，用户点「执行」返回 true。 */
    private boolean confirmExecute(String ddl) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("确认执行 DDL");
        dialog.setHeaderText((isNew ? "将创建表 " : "将变更表 ") + (isNew ? trim(nameField.getText()) : table.name()));
        ButtonType exec = new ButtonType("执行", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(exec, cancel);

        TextArea area = new TextArea(ddl);
        area.setEditable(false);
        area.setWrapText(false);
        area.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");
        area.setPrefRowCount(18);
        area.setPrefColumnCount(72);
        dialog.getDialogPane().setContent(area);
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        if (owner != null) dialog.initOwner(owner);
        return dialog.showAndWait().orElse(cancel) == exec;
    }

    /**
     * 脚本遇错处置回调：在 worker 线程被 runner 调用，切到 FX 线程弹三按钮框
     * （继续 / 全部继续 / 取消）并以 {@link java.util.concurrent.CountDownLatch} 阻塞等待选择。
     */
    private ScriptErrorPolicy.Decision askScriptError(int index, String sql, String message) {
        final java.util.concurrent.atomic.AtomicReference<ScriptErrorPolicy.Decision> ref =
                new java.util.concurrent.atomic.AtomicReference<>(ScriptErrorPolicy.Decision.ABORT);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ButtonType cont = new ButtonType("继续");
                ButtonType contAll = new ButtonType("全部继续");
                ButtonType abort = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
                Alert a = new Alert(Alert.AlertType.ERROR,
                        "第 " + index + " 条语句失败：\n" + truncate(message, 300)
                                + "\n\n是否继续执行剩余语句？",
                        cont, contAll, abort);
                a.setHeaderText(null);
                a.setTitle("执行遇错");
                ButtonType chosen = a.showAndWait().orElse(abort);
                if (chosen == cont) ref.set(ScriptErrorPolicy.Decision.CONTINUE);
                else if (chosen == contAll) ref.set(ScriptErrorPolicy.Decision.CONTINUE_ALL);
                else ref.set(ScriptErrorPolicy.Decision.ABORT);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ScriptErrorPolicy.Decision.ABORT;
        }
        return ref.get();
    }

    private static String firstError(List<ScriptOutcome> outcomes) {
        if (outcomes == null) return null;
        for (ScriptOutcome o : outcomes) {
            if (o.result() != null && o.result().kind == com.datacube.spi.model.QueryResult.Kind.ERROR) {
                return truncate(o.result().errorMessage, 120);
            }
        }
        return null;
    }

    // ---------- 工具 ----------

    private void setStatus(String text, String color) {
        statusLabel.setText(text);
        statusLabel.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px;");
    }

    private void showAlert(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private static List<String> splitColumns(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String p : text.split(",")) {
            String s = p.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ---------- 可变行模型 ----------

    /** 列编辑行：JavaFX property 承载可编辑单元格。 */
    static final class ColumnRow {
        final SimpleStringProperty name;
        final SimpleStringProperty typeText;
        final SimpleBooleanProperty nullable;
        final SimpleBooleanProperty pk;
        final SimpleStringProperty defaultValue;
        final SimpleStringProperty comment;

        ColumnRow(String name, String typeText, boolean nullable, boolean pk,
                  String defaultValue, String comment) {
            this.name = new SimpleStringProperty(name);
            this.typeText = new SimpleStringProperty(typeText);
            this.nullable = new SimpleBooleanProperty(nullable);
            this.pk = new SimpleBooleanProperty(pk);
            this.defaultValue = new SimpleStringProperty(defaultValue);
            this.comment = new SimpleStringProperty(comment);
        }
    }

    /** 索引编辑行。 */
    static final class IndexRow {
        final SimpleStringProperty name;
        final SimpleBooleanProperty unique;
        final SimpleStringProperty columns;

        IndexRow(String name, boolean unique, String columns) {
            this.name = new SimpleStringProperty(name);
            this.unique = new SimpleBooleanProperty(unique);
            this.columns = new SimpleStringProperty(columns);
        }
    }
}
