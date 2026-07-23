package com.datacube.fx;

import com.datacube.service.DdlService;
import com.datacube.spi.model.DbType;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.spi.model.SequenceDraft;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.util.List;

/**
 * 序列设计器面板：以 PL/SQL Developer 风格的表单编辑序列属性
 * （最小值/最大值/下一个数字/递增值/缓存大小/循环/顺序），diff 生成
 * {@code ALTER SEQUENCE} 预览，确认后执行。
 *
 * <p>所有者/名称只读；PG 无 ORDER 概念时隐藏“顺序”。DDL 生成与执行委托
 * {@link DdlService}（方言封闭在 provider 层）；载入/执行走后台线程，
 * 回 {@link Platform#runLater} 更新 UI。
 */
public final class SequenceDesignerPane {

    private final DdlService svc;
    private final String connId;
    private final String connName;
    private final String schema;
    private final String name;
    private final boolean supportsOrder;

    private final VBox root = new VBox(8);
    private final TextField ownerField = new TextField();
    private final TextField nameField = new TextField();
    private final TextField minField = new TextField();
    private final TextField maxField = new TextField();
    private final TextField nextField = new TextField();
    private final TextField incField = new TextField();
    private final TextField cacheField = new TextField();
    private final CheckBox cycleBox = new CheckBox("循环");
    private final CheckBox orderBox = new CheckBox("顺序");
    private final CodeArea previewArea = HighlightedSqlArea.create(false);
    private final Label statusLabel = new Label("加载中...");
    private final Button applyBtn = new Button("应用");
    private final Button refreshBtn = new Button("刷新");
    private final Button previewBtn = new Button("预览");

    private volatile SequenceDraft original;
    private volatile boolean running = false;

    public SequenceDesignerPane(DdlService svc, String connId, String connName,
                                String schema, String name, DbType dbType) {
        this.svc = svc;
        this.connId = connId;
        this.connName = connName;
        this.schema = schema;
        this.name = name;
        this.supportsOrder = dbType == DbType.ORACLE;
        build();
        reload();
    }

    public Node getNode() {
        return root;
    }

    // ---------- 构建 ----------

    private void build() {
        root.setPadding(new Insets(10));
        root.setStyle("-fx-font-family: 'Microsoft YaHei', 'Segoe UI', sans-serif; -fx-font-size: 13px;");
        root.getChildren().addAll(toolbar(), formGrid(), previewSection(), statusLabel);
    }

    private Node toolbar() {
        applyBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        applyBtn.setOnAction(e -> onApply());
        refreshBtn.setOnAction(e -> reload());
        previewBtn.setOnAction(e -> refreshPreview());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label connLabel = new Label();
        connLabel.setStyle("-fx-text-fill: -brand-fg-muted;");
        if (connName != null && !connName.isEmpty()) connLabel.setText("🔗 " + connName);

        HBox box = new HBox(8, new Label("序列: " + name), applyBtn, previewBtn, refreshBtn, spacer, connLabel);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Node formGrid() {
        ownerField.setEditable(false);
        ownerField.setText(schema == null ? "" : schema);
        nameField.setEditable(false);
        nameField.setText(name);

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(6, 0, 6, 0));

        // 左列
        g.add(new Label("所有者"), 0, 0);
        g.add(ownerField, 1, 0);
        g.add(new Label("名称"), 0, 1);
        g.add(nameField, 1, 1);
        g.add(new Label("最小值"), 0, 2);
        g.add(minField, 1, 2);
        g.add(new Label("最大值"), 0, 3);
        g.add(maxField, 1, 3);

        // 右列
        g.add(new Label("下一个数字"), 2, 0);
        g.add(nextField, 3, 0);
        g.add(new Label("递增值"), 2, 1);
        g.add(incField, 3, 1);
        g.add(new Label("缓存大小"), 2, 2);
        g.add(cacheField, 3, 2);
        HBox flags = new HBox(16, cycleBox);
        if (supportsOrder) flags.getChildren().add(orderBox);
        g.add(flags, 3, 3);

        return g;
    }

    private Node previewSection() {
        Label caption = new Label("ALTER 预览");
        caption.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");
        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(previewArea);
        VBox box = new VBox(4, caption, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox.setVgrow(box, Priority.ALWAYS);
        statusLabel.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");
        return box;
    }

    // ---------- 载入 / 快照 ----------

    private void reload() {
        setStatus("加载中...", "-brand-fg-muted");
        setBusy(true);
        new Thread(() -> {
            SequenceDraft d = null;
            String err = null;
            try {
                d = svc.loadSequence(connId, schema, name);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final SequenceDraft fd = d;
            final String fErr = err;
            Platform.runLater(() -> {
                setBusy(false);
                if (fErr != null) {
                    setStatus("载入失败: " + fErr, "-status-error");
                    return;
                }
                original = fd;
                populate(fd);
                previewArea.replaceText("-- 修改上方属性后点“预览”或“应用”");
                setStatus("就绪", "-status-ok");
            });
        }, "SequenceDesigner-Load").start();
    }

    private void populate(SequenceDraft d) {
        minField.setText(nz(d.minValue()));
        maxField.setText(nz(d.maxValue()));
        nextField.setText(nz(d.nextValue()));
        incField.setText(nz(d.incrementBy()));
        cacheField.setText(Integer.toString(d.cacheSize()));
        cycleBox.setSelected(d.cycle());
        orderBox.setSelected(d.order());
    }

    private SequenceDraft snapshot() {
        int cache;
        try {
            cache = Integer.parseInt(trim(cacheField.getText()));
        } catch (NumberFormatException e) {
            cache = original == null ? 0 : original.cacheSize();
        }
        return new SequenceDraft(schema, name,
                trim(minField.getText()),
                trim(maxField.getText()),
                trim(incField.getText()),
                trim(nextField.getText()),
                cache,
                cycleBox.isSelected(),
                supportsOrder && orderBox.isSelected());
    }

    // ---------- 预览 / 应用 ----------

    private void refreshPreview() {
        if (original == null) {
            previewArea.replaceText("-- 原始属性尚未载入完成，请稍候");
            return;
        }
        try {
            String ddl = svc.previewAlterSequence(connId, original, snapshot());
            previewArea.replaceText(ddl.isBlank() ? "-- 无变更" : ddl);
        } catch (Exception e) {
            previewArea.replaceText("-- 生成预览失败: " + e.getMessage());
        }
    }

    private void onApply() {
        if (running) return;
        if (original == null) {
            showAlert("原始属性尚未载入完成，请稍候");
            return;
        }
        final String ddl;
        try {
            ddl = svc.previewAlterSequence(connId, original, snapshot());
        } catch (Exception e) {
            showAlert("生成 DDL 失败: " + e.getMessage());
            return;
        }
        previewArea.replaceText(ddl.isBlank() ? "-- 无变更" : ddl);
        if (ddl.isBlank()) {
            showAlert("无变更，无需执行");
            return;
        }
        if (!confirmExecute(ddl)) return;

        running = true;
        setBusy(true);
        setStatus("执行中...", "-brand-fg-muted");
        new Thread(() -> {
            List<ScriptOutcome> outcomes = null;
            String err = null;
            try {
                outcomes = svc.executeDdl(connId, ddl);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final List<ScriptOutcome> fOut = outcomes;
            final String fErr = err;
            Platform.runLater(() -> {
                running = false;
                setBusy(false);
                if (fErr != null) {
                    setStatus("执行失败: " + fErr, "-status-error");
                    return;
                }
                String failed = firstError(fOut);
                if (failed != null) {
                    setStatus("执行完成但有失败: " + failed, "-status-error");
                } else {
                    setStatus("执行成功（" + (fOut == null ? 0 : fOut.size()) + " 条语句）", "-status-ok");
                    reload();  // 重载最新属性作为新的原始态
                }
            });
        }, "SequenceDesigner-Exec").start();
    }

    /** 预览确认对话框：展示完整 DDL，用户点「执行」返回 true。 */
    private boolean confirmExecute(String ddl) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("确认执行 DDL");
        dialog.setHeaderText("将变更序列 " + name);
        ButtonType exec = new ButtonType("执行", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(exec, cancel);

        TextArea area = new TextArea(ddl);
        area.setEditable(false);
        area.setWrapText(false);
        area.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");
        area.setPrefRowCount(8);
        area.setPrefColumnCount(64);
        dialog.getDialogPane().setContent(area);
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        if (owner != null) dialog.initOwner(owner);
        return dialog.showAndWait().orElse(cancel) == exec;
    }

    private static String firstError(List<ScriptOutcome> outcomes) {
        if (outcomes == null) return null;
        for (ScriptOutcome o : outcomes) {
            if (o.result() != null && o.result().kind == QueryResult.Kind.ERROR) {
                return truncate(o.result().errorMessage, 120);
            }
        }
        return null;
    }

    // ---------- 工具 ----------

    private void setBusy(boolean busy) {
        applyBtn.setDisable(busy);
        refreshBtn.setDisable(busy);
        previewBtn.setDisable(busy);
    }

    private void setStatus(String text, String color) {
        statusLabel.setText(text);
        statusLabel.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px;");
    }

    private void showAlert(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
