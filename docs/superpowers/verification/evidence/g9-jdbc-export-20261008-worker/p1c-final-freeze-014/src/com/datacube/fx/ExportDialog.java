package com.datacube.fx;

import com.datacube.export.ExportContent;
import com.datacube.export.ExportFormat;
import com.datacube.export.TableExporter;
import com.datacube.export.SafeResultFilePublisher;
import com.datacube.export.ResultExportOperation;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.model.TableRef;

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
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;

/**
 * 单表导出对话框：选择内容（结构/数据/两者）与格式（SQL/Excel/pg_dump），
 * 再经 {@link FileChooser} 选目标文件，受管虚拟线程调用 {@link TableExporter}。
 */
public final class ExportDialog {

    private ExportDialog() {
    }

    public static void show(ConnectionManager conns, String connId, TableRef table, Window owner,
                            FxTaskRunner runner) {
        show(conns, connId, table, owner, runner, null);
    }
    static void show(ConnectionManager conns, String connId, TableRef table, Window owner,
                     FxTaskRunner runner, TableExportTasks exports) {
        if (exports != null && !exports.admitting()) return;
        var selection = TableExporter.capture(conns, connId);
        boolean handedOff = false;
        try {
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
        boolean isPg = selection.snapshot().config().type() == com.datacube.spi.model.DbType.POSTGRESQL;
        RadioButton fDump = isPg ? radio(formatGroup, "pg_dump 备份 (.sql，需 pg_dump/libpq 16+)", ExportFormat.PG_DUMP) : null;

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
        File initDir = FxFiles.defaultSaveDir();
        if (initDir != null) chooser.setInitialDirectory(initDir);
        String ext = format == ExportFormat.XLSX ? "xlsx" : "sql";
        chooser.setInitialFileName(table.name() + "." + ext);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                ext.toUpperCase() + " 文件", "*." + ext));
        File out = chooser.showSaveDialog(owner);
        if (out == null) return;

        startExport(selection, connId, table, content, format, out.toPath(), runner, new DialogUi(owner, table),
                (request, operation) -> TableExporter.export(conns, request, operation), exports);
        handedOff = true;
        } finally { if (!handedOff) selection.close(); }
    }

    enum Status { SUCCEEDED, FAILED, CANCELLED }
    record Outcome(Status status, Path path, String message, java.util.List<Path> cleanupResidues) {
        Outcome(Status status, Path path, String message) { this(status, path, message, java.util.List.of()); }
    }
    interface ExportUi {
        boolean confirmOverwrite(Path target);
        void running(ExportTask task);
        void cancelling(boolean accepted);
        default void cleanupPending() { cancelling(true); }
        void finished(Outcome outcome);
    }
    @FunctionalInterface interface ExportJob {
        Path write(TableExporter.Request request, ResultExportOperation operation) throws Exception;
    }

    /** Local lifecycle seam, also used by the actual dialog after the native file chooser returns. */
    static ExportTask startExport(ConnectionManager conns, String connId, TableRef table,
                                  ExportContent content, ExportFormat format, Path chosen,
                                  FxTaskRunner runner, ExportUi ui) {
        return startExport(TableExporter.capture(conns, connId), connId, table, content, format, chosen, runner, ui,
                (request, operation) -> TableExporter.export(conns, request, operation), null);
    }

    static ExportTask startExport(String connId, TableRef table, ExportContent content, ExportFormat format,
                                  Path chosen, FxTaskRunner runner, ExportUi ui, ExportJob job) {
        return startExport(connId, table, content, format, chosen, runner, ui, job, null);
    }
    static ExportTask startExport(String connId, TableRef table, ExportContent content, ExportFormat format,
                                  Path chosen, FxTaskRunner runner, ExportUi ui, ExportJob job, TableExportTasks exports) {
        return startExport(null, connId, table, content, format, chosen, runner, ui, job, exports);
    }
    static ExportTask startExport(TableExporter.Selection selection, String connId, TableRef table,
                                  ExportContent content, ExportFormat format, Path chosen,
                                  FxTaskRunner runner, ExportUi ui, ExportJob job, TableExportTasks exports) {
        ExportTask task = null;
        boolean handedOff = false;
        try {
            if (chosen == null || exports != null && !exports.admitting()) return null;
            if (selection != null) selection.validate();
            var target = SafeResultFilePublisher.capture(chosen);
            if (target.existed() && !ui.confirmOverwrite(target.path())) return null;
            if (selection != null) selection.validate();
            var request = new TableExporter.Request(connId, table, content, format, target,
                    selection == null ? null : selection.snapshot());
            task = new ExportTask(runner.scope(), ui, selection == null ? new ResultExportOperation() : selection.operation(),
                    selection == null ? () -> {} : selection::close);
            handedOff = true;
            if (exports != null && !exports.register(task)) {
                task.finish(new Outcome(Status.FAILED, null, "应用正在退出，导出任务未启动"));
                return task;
            }
            ExportTask accepted = task;
            ui.running(accepted);
            runner.submit(() -> accepted.run(() -> job.write(request, accepted.operation)));
            return accepted;
        } catch (Exception failure) {
            if (task != null) task.finish(new Outcome(Status.FAILED, null, message(failure)));
            else ui.finished(new Outcome(Status.FAILED, null, message(failure)));
            return task;
        } catch (Error fatal) {
            // No producer started if admission/UI/submission failed. Release this queued owner's physical promise.
            if (task != null) task.finish(new Outcome(Status.FAILED, null, "导出失败，未发布结果文件"));
            throw fatal;
        } finally { if (!handedOff && selection != null) selection.close(); }
    }

    static final class ExportTask {
        private final ResultExportOperation operation;
        private final AtomicInteger phase = new AtomicInteger(); // queued, running, settled
        private final AtomicBoolean cancellationAccepted = new AtomicBoolean();
        private final FxTaskScope callbacks;
        private final ExportUi ui;
        private final Runnable releaseSelection;
        final CompletableFuture<Outcome> completion = new CompletableFuture<>();
        final CompletableFuture<Void> physicalCompletion = new CompletableFuture<>();
        private ExportTask(FxTaskScope callbacks, ExportUi ui, ResultExportOperation operation, Runnable releaseSelection) {
            this.operation = operation;
            this.releaseSelection = releaseSelection;
            this.callbacks = callbacks; this.ui = ui;
            operation.onCleanupPending(() -> callbacks.dispatch(ui::cleanupPending));
            completion.thenAccept(outcome -> callbacks.dispatch(() -> {
                try { ui.finished(outcome); } finally { callbacks.close(); }
            }));
        }
        boolean cancel() {
            return requestCancellation(true);
        }
        void requestStop() { requestCancellation(false); }
        private boolean requestCancellation(boolean onFx) {
            if (phase.get() == 2) return false;
            boolean accepted = cancellationAccepted.get() || operation.cancelled() || operation.cancel();
            if (accepted) cancellationAccepted.set(true);
            if (onFx) ui.cancelling(accepted);
            else callbacks.dispatch(() -> ui.cancelling(accepted));
            if (accepted && phase.compareAndSet(0, 2)) {
                releaseSelection.run();
                finish(new Outcome(Status.CANCELLED, null, "取消请求已生效，未发布结果文件"));
                physicalCompletion.complete(null);
            }
            return accepted;
        }
        void ownerClosed() { cancel(); callbacks.close(); }
        private void run(java.util.concurrent.Callable<Path> job) {
            if (!phase.compareAndSet(0, 1)) return;
            try { runStarted(job); }
            finally { releaseSelection.run(); physicalCompletion.complete(null); }
        }
        private void runStarted(java.util.concurrent.Callable<Path> job) {
            Outcome outcome;
            try {
                operation.check();
                Path published = job.call();
                if (!operation.published()) throw new IllegalStateException("Export was not published");
                var residues = operation.cleanupResidues();
                outcome = new Outcome(Status.SUCCEEDED, published, residues.isEmpty() ? "导出完成"
                        : "已发布，但辅助临时文件清理失败，请核对:\n" + residues, residues);
            } catch (CancellationException cancelled) {
                outcome = new Outcome(Status.CANCELLED, null, "取消请求已生效，未发布结果文件");
            } catch (Exception failure) {
                outcome = new Outcome(Status.FAILED, null, message(failure));
            } catch (Error fatal) {
                try {
                    finish(new Outcome(Status.FAILED, null, operation.cleanupResidues().isEmpty()
                            ? "导出失败，未发布结果文件" : "导出失败，未发布结果文件；临时文件未清理，请核对: " + operation.cleanupResidues()));
                } finally {
                    // submit captures thrown Error in its Future; explicitly preserve the scope's application handler semantics.
                    Thread worker = Thread.currentThread();
                    worker.getUncaughtExceptionHandler().uncaughtException(worker, fatal);
                }
                return;
            }
            finish(outcome);
        }
        private void finish(Outcome outcome) {
            int previous = phase.getAndSet(2);
            if (outcome.status() != Status.SUCCEEDED) operation.cancel();
            completion.complete(outcome);
            if (previous == 0) { releaseSelection.run(); physicalCompletion.complete(null); }
        }
    }

    private static final class DialogUi implements ExportUi {
        private final Window owner;
        private final TableRef table;
        private Alert progress;
        private boolean finishing;
        private DialogUi(Window owner, TableRef table) { this.owner = owner; this.table = table; }
        public boolean confirmOverwrite(Path target) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "导出成功后替换此文件；失败时保留原文件。\n" + target, ButtonType.OK, ButtonType.CANCEL);
            if (owner != null) confirm.initOwner(owner);
            confirm.setTitle("确认替换文件");
            confirm.setHeaderText("目标文件已存在");
            return confirm.showAndWait().filter(ButtonType.OK::equals).isPresent();
        }
        public void running(ExportTask task) {
            progress = new Alert(Alert.AlertType.INFORMATION);
            if (owner != null) progress.initOwner(owner);
            progress.setTitle("导出"); progress.setHeaderText(null);
            progress.setContentText("正在导出 " + table.qualified() + " ...");
            progress.getButtonTypes().setAll(ButtonType.CANCEL);
            ((Button) progress.getDialogPane().lookupButton(ButtonType.CANCEL)).addEventFilter(ActionEvent.ACTION,
                    event -> { event.consume(); task.cancel(); });
            progress.setOnCloseRequest(event -> {
                if (!finishing) { event.consume(); task.cancel(); }
            });
            progress.setOnHidden(event -> { if (!finishing) task.ownerClosed(); });
            progress.show();
        }
        public void cancelling(boolean accepted) {
            if (progress != null) progress.setContentText(accepted
                    ? "正在取消，等待导出任务返回..." : "正在发布文件，请等待结果...");
        }
        public void cleanupPending() {
            if (progress != null) progress.setContentText("导出尚未结束，正在等待自有进程、连接和输出资源停止，请勿重试或强制退出...");
        }
        public void finished(Outcome outcome) {
            finishing = true;
            if (progress != null) progress.close();
            Alert done = new Alert(outcome.status() == Status.FAILED ? Alert.AlertType.ERROR
                    : !outcome.cleanupResidues().isEmpty() ? Alert.AlertType.WARNING : Alert.AlertType.INFORMATION);
            if (owner != null) done.initOwner(owner);
            done.setTitle("导出"); done.setHeaderText(null);
            done.setContentText(outcome.status() == Status.SUCCEEDED
                    ? outcome.message() + ":\n" + outcome.path() : outcome.message());
            done.showAndWait();
        }
    }

    private static String message(Throwable failure) {
        if (failure instanceof com.datacube.export.TableExportFailure safe) return safe.getMessage();
        if (failure instanceof com.datacube.export.PgDumpRunner.Failure dump) return dump.userMessage();
        if (failure instanceof SafeResultFilePublisher.Failure safe) return switch (safe.stage()) {
            case PREPARE -> "无法安全保存：请选择本地普通文件";
            case TARGET_CHANGED -> "目标文件已改变，请重新选择并确认";
            case TARGET_BUSY -> "目标文件正在导出，请稍后重试";
            case WRITE, XML_CHARACTER -> "导出写入失败，原目标文件未修改";
            case PUBLISH -> "无法原子发布导出文件，原目标文件未修改";
            case CLEANUP -> "导出未完成，临时文件清理失败，请核对: " + safe.residualPaths();
        };
        return "导出任务未完成，未发布结果文件";
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
