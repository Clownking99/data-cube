package com.datacube.fx;

import com.datacube.export.*;
import com.datacube.export.QueryResultFileWriter.Format;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.sqleditor.InsertSqlGenerator;
import com.datacube.sqleditor.result.*;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;

final class SqlResultExportCoordinator implements AutoCloseable {
    interface Ui {
        Optional<ResultExportOptionsDialog.Selection> chooseScope(ResultExportSnapshot snapshot, boolean sql);
        Path chooseFile(Format format);
        String chooseTable(String originalSql);
        boolean confirmOverwrite(Path path);
    }
    record Request(SafeResultFilePublisher.Target target, Format format, ResultExportSnapshot snapshot,
                   ResultExportOptionsDialog.Selection selection, String table) {
        @Override public String toString() { return "ResultExportRequest[" + format + "]"; }
    }
    @FunctionalInterface interface FileJob {
        Path write(Request request, ResultExportOperation operation) throws Exception;
    }
    private final FxTaskScope tasks;
    private final Supplier<ResultExportSnapshot> capture;
    private final LongSupplier revision;
    private final BiConsumer<String, Boolean> status;
    private final Predicate<String> clipboard;
    private final Ui ui;
    private final FileJob job;
    private final ResultExportSession session = new ResultExportSession();
    private ResultExportOperation latest;

    SqlResultExportCoordinator(FxTaskScope tasks, Supplier<ResultExportSnapshot> capture,
            LongSupplier revision, BiConsumer<String, Boolean> status, Predicate<String> clipboard,
            Supplier<Window> owner) {
        this(tasks, capture, revision, status, clipboard, new Dialogs(owner),
                (request, operation) -> new SafeResultFilePublisher().publish(request.target(), operation,
                        (temporary, token) -> QueryResultFileWriter.write(temporary, request.format(),
                                request.snapshot(), request.selection().scope(),
                                request.selection().displayConfirmed(), request.table(), token)));
    }
    SqlResultExportCoordinator(FxTaskScope tasks, Supplier<ResultExportSnapshot> capture,
            LongSupplier revision, BiConsumer<String, Boolean> status, Predicate<String> clipboard,
            Ui ui, FileJob job) {
        this.tasks = Objects.requireNonNull(tasks);
        this.capture = Objects.requireNonNull(capture);
        this.revision = Objects.requireNonNull(revision);
        this.status = Objects.requireNonNull(status);
        this.clipboard = Objects.requireNonNull(clipboard);
        this.ui = Objects.requireNonNull(ui);
        this.job = Objects.requireNonNull(job);
    }
    private boolean open() { return !session.isClosed() && !tasks.isClosed(); }
    private boolean ownsStatus(ResultExportOperation operation, long stamp) {
        return open() && latest == operation && revision.getAsLong() == stamp;
    }
    private static boolean permitted(ResultExportSnapshot snapshot,
            ResultExportOptionsDialog.Selection selection, boolean sql) {
        var rows = snapshot.rows(selection.scope());
        if (rows.isEmpty() || snapshot.columns().isEmpty()) return false;
        boolean scalar = ResultExportValuePolicy.assess(rows).sqlAllowed();
        return scalar || (!sql && selection.displayConfirmed());
    }
    Future<?> export(Format format) {
        if (!open()) return null;
        ResultExportOperation operation = session.begin();
        if (operation == null) return null;
        latest = operation;
        boolean submitted = false;
        long ownerRevision = revision.getAsLong();
        try {
            ResultExportSnapshot snapshot = capture.get();
            ownerRevision = revision.getAsLong();
            if (snapshot == null) {
                status.accept("没有可导出的查询结果", true);
                return null;
            }
            var selection = ui.chooseScope(snapshot, format == Format.SQL);
            if (selection.isEmpty() || !open()) return null;
            if (!permitted(snapshot, selection.get(), format == Format.SQL)) {
                if (ownsStatus(operation, ownerRevision)) status.accept("当前范围或值类型不能导出", true);
                return null;
            }
            String table = format == Format.SQL ? ui.chooseTable(snapshot.originalSql()) : null;
            if ((format == Format.SQL && table == null) || !open()) return null;
            Path chosen = ui.chooseFile(format);
            if (chosen == null || !open()) return null;
            var target = SafeResultFilePublisher.capture(chosen);
            if (target.existed() && !ui.confirmOverwrite(target.path())) return null;
            if (!open()) return null;
            operation.check();
            var request = new Request(target, format, snapshot, selection.get(), table);
            boolean statusOwned = ownsStatus(operation, ownerRevision);
            if (statusOwned) status.accept("导出中...", false);
            final long completionRevision = statusOwned ? revision.getAsLong() : Long.MIN_VALUE;
            Future<?> future = tasks.submit(() -> {
                try { return job.write(request, operation); }
                finally { session.finish(operation); }
            }, published -> {
                if (ownsStatus(operation, completionRevision))
                    status.accept("已导出: " + published, false);
            }, failure -> {
                if (ownsStatus(operation, completionRevision))
                    status.accept(failureMessage(failure), true);
            });
            submitted = true;
            return future;
        } catch (RejectedExecutionException rejected) {
            if (open() && latest == operation) status.accept("导出任务未能启动，请重试", true);
            return null;
        } catch (CancellationException cancelled) {
            return null;
        } catch (Exception failure) {
            if (ownsStatus(operation, ownerRevision)) status.accept(failureMessage(failure), true);
            return null;
        } finally {
            if (!submitted) {
                operation.cancel();
                session.finish(operation);
            }
        }
    }
    boolean copyInsert() {
        if (!open()) return false;
        long ownerRevision = revision.getAsLong();
        try {
            var snapshot = capture.get();
            ownerRevision = revision.getAsLong();
            if (snapshot == null) return false;
            var selection = ui.chooseScope(snapshot, true);
            if (selection.isEmpty() || !open()) return false;
            if (!permitted(snapshot, selection.get(), true)) {
                if (revision.getAsLong() == ownerRevision) status.accept("当前范围或值类型不能生成 INSERT", true);
                return false;
            }
            String table = ui.chooseTable(snapshot.originalSql());
            if (table == null || !open()) return false;
            String script = QueryResultFileWriter.insert(snapshot, selection.get().scope(), table);
            if (!open()) return false;
            boolean written = clipboard.test(script);
            if (open() && revision.getAsLong() == ownerRevision)
                status.accept(written ? "已复制 " + snapshot.rows(selection.get().scope()).size()
                        + " 条 INSERT 语句" : "复制失败：无法写入系统剪贴板", !written);
            return written;
        } catch (RuntimeException failure) {
            if (open() && revision.getAsLong() == ownerRevision)
                status.accept("复制失败：无法生成或写入 INSERT", true);
            return false;
        }
    }
    private static String failureMessage(Throwable failure) {
        if (failure instanceof SafeResultFilePublisher.Failure safe) {
            return switch (safe.stage()) {
                case PREPARE -> "无法安全保存：请选择本地普通文件";
                case TARGET_CHANGED -> "目标文件已改变，请重新选择并确认";
                case TARGET_BUSY -> "目标文件正在导出，请稍后重试";
                case WRITE -> "导出写入失败，原目标文件未修改";
                case PUBLISH -> "无法原子发布导出文件，原目标文件未修改";
                case CLEANUP -> "导出未完成，临时文件清理失败，请手动处理: " + safe.temporaryPath();
            };
        }
        return "导出失败，未发布结果文件";
    }
    @Override public void close() { session.close(); }

    private static final class Dialogs implements Ui {
        private final Supplier<Window> owner;
        private Dialogs(Supplier<Window> owner) { this.owner = owner; }
        @Override public Optional<ResultExportOptionsDialog.Selection> chooseScope(
                ResultExportSnapshot snapshot, boolean sql) {
            return ResultExportOptionsDialog.create(owner.get(), snapshot, sql).showAndWait();
        }
        @Override public Path chooseFile(Format format) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("导出结果 - " + format.label);
            File directory = FxFiles.defaultSaveDir();
            if (directory != null) chooser.setInitialDirectory(directory);
            chooser.setInitialFileName(format.defaultName);
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(format.filterDesc, format.filterExt));
            File chosen = chooser.showSaveDialog(owner.get());
            return chosen == null ? null : chosen.toPath();
        }
        @Override public String chooseTable(String originalSql) {
            String table = InsertSqlGenerator.singleTableName(originalSql);
            if (table != null) return table;
            TextInputDialog dialog = new TextInputDialog();
            if (owner.get() != null) dialog.initOwner(owner.get());
            dialog.setTitle("指定目标表");
            dialog.setHeaderText("无法确定单一来源表，请输入 INSERT 目标表名（可带 schema 前缀）");
            dialog.setContentText("表名:");
            return dialog.showAndWait().map(String::trim).filter(value -> !value.isEmpty()).orElse(null);
        }
        @Override public boolean confirmOverwrite(Path path) {
            Alert dialog = new Alert(Alert.AlertType.CONFIRMATION,
                    "导出成功后替换此文件；失败时保留原文件。\n" + path, ButtonType.OK, ButtonType.CANCEL);
            if (owner.get() != null) dialog.initOwner(owner.get());
            dialog.setTitle("确认替换文件");
            dialog.setHeaderText("目标文件已存在");
            return dialog.showAndWait().filter(ButtonType.OK::equals).isPresent();
        }
    }
}
