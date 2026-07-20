package com.datacube.fx;

import com.datacube.export.ExportContent;
import com.datacube.export.ExportFormat;
import com.datacube.export.TableExporter;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.model.TableRef;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;

/**
 * 单表导出对话框：选择内容（结构/数据/两者）与格式（SQL/Excel/pg_dump），
 * 再经 {@link FileChooser} 选目标文件，后台线程调用 {@link TableExporter}。
 */
public final class ExportDialog {

    private ExportDialog() {
    }

    public static void show(ConnectionManager conns, String connId, TableRef table, Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("导出表: " + table.qualified());
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // 内容
        ToggleGroup contentGroup = new ToggleGroup();
        RadioButton rStructure = radio(contentGroup, "仅结构", ExportContent.STRUCTURE);
        RadioButton rData = radio(contentGroup, "仅数据", ExportContent.DATA);
        RadioButton rBoth = radio(contentGroup, "结构 + 数据", ExportContent.BOTH);
        rBoth.setSelected(true);

        // 格式
        ToggleGroup formatGroup = new ToggleGroup();
        RadioButton fSql = radio(formatGroup, "SQL 脚本 (.sql)", ExportFormat.SQL);
        RadioButton fXlsx = radio(formatGroup, "Excel (.xlsx)", ExportFormat.XLSX);
        fSql.setSelected(true);
        // pg_dump 备份仅 PostgreSQL 可用；其它库不提供该选项
        boolean isPg = conns.config(connId).type() == com.datacube.spi.model.DbType.POSTGRESQL;
        RadioButton fDump = isPg ? radio(formatGroup, "pg_dump 备份 (.sql)", ExportFormat.PG_DUMP) : null;

        Label xlsxHint = new Label("提示: Excel 仅导出数据。");
        xlsxHint.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");
        xlsxHint.setVisible(false);

        // Excel 只导数据：置灰结构/两者，强制数据
        formatGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            boolean xlsx = n != null && n.getUserData() == ExportFormat.XLSX;
            rStructure.setDisable(xlsx);
            rBoth.setDisable(xlsx);
            xlsxHint.setVisible(xlsx);
            if (xlsx) rData.setSelected(true);
        });

        VBox box = new VBox(6,
                bold("导出内容"), rStructure, rData, rBoth,
                new Label(" "),
                bold("导出格式"), fSql, fXlsx,
                xlsxHint);
        if (fDump != null) {
            box.getChildren().add(box.getChildren().indexOf(xlsxHint), fDump);
        }
        box.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(box);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        ExportContent content = (ExportContent) contentGroup.getSelectedToggle().getUserData();
        ExportFormat format = (ExportFormat) formatGroup.getSelectedToggle().getUserData();

        // 选择输出文件
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存到");
        String ext = format == ExportFormat.XLSX ? "xlsx" : "sql";
        chooser.setInitialFileName(table.name() + "." + ext);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                ext.toUpperCase() + " 文件", "*." + ext));
        File out = chooser.showSaveDialog(owner);
        if (out == null) return;

        runExport(conns, connId, table, content, format, out);
    }

    private static void runExport(ConnectionManager conns, String connId, TableRef table,
                                  ExportContent content, ExportFormat format, File out) {
        Alert progress = new Alert(Alert.AlertType.INFORMATION);
        progress.setTitle("导出");
        progress.setHeaderText(null);
        progress.setContentText("正在导出 " + table.qualified() + " ...");
        progress.getButtonTypes().setAll(ButtonType.CLOSE);
        progress.show();

        new Thread(() -> {
            String err = null;
            try {
                TableExporter.export(conns, connId, table, content, format, out);
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                // 失败时清理半成品文件
                if (out.exists()) out.delete();
            }
            final String fErr = err;
            Platform.runLater(() -> {
                progress.close();
                Alert done = new Alert(fErr == null ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR);
                done.setTitle("导出");
                done.setHeaderText(null);
                done.setContentText(fErr == null
                        ? "导出完成:\n" + out.getAbsolutePath()
                        : "导出失败:\n" + fErr);
                done.showAndWait();
            });
        }, "Table-Export").start();
    }

    private static RadioButton radio(ToggleGroup group, String text, Object userData) {
        RadioButton rb = new RadioButton(text);
        rb.setToggleGroup(group);
        rb.setUserData(userData);
        return rb;
    }

    private static Label bold(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }
}
