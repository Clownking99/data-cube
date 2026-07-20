package com.datacube.fx;

import com.datacube.update.ReleaseInfo;
import com.datacube.update.UpdateService;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * 自动更新相关的 UI 呈现：更新提示弹窗、下载进度、结果反馈。
 *
 * <p>{@link UpdateService} 的回调在后台线程触发，本类统一包装到
 * {@code Platform.runLater} 再操作 UI。
 */
final class UpdateUI {

    private UpdateUI() {
    }

    /**
     * 弹出"发现新版本"提示：展示版本与 Release Notes；用户点"立即更新"则下载并应用。
     */
    static void promptUpdate(UpdateService svc, ReleaseInfo info, Window owner) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("发现新版本");
        alert.setHeaderText("新版本 " + info.tag() + " 可用");
        if (owner != null) alert.initOwner(owner);

        String notes = info.releaseNotes();
        if (notes != null && !notes.isBlank()) {
            TextArea area = new TextArea(notes);
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(12);
            area.setPrefColumnCount(48);
            alert.getDialogPane().setExpandableContent(area);
            alert.getDialogPane().setExpanded(true);
        }

        ButtonType update = new ButtonType("立即更新");
        ButtonType later = new ButtonType("稍后", ButtonType.CANCEL.getButtonData());
        alert.getButtonTypes().setAll(update, later);

        if (alert.showAndWait().orElse(later) == update) {
            downloadAndApply(svc, info, owner);
        }
    }

    /** 展示下载进度并驱动更新应用流程。 */
    private static void downloadAndApply(UpdateService svc, ReleaseInfo info, Window owner) {
        Stage dialog = new Stage();
        if (owner != null) {
            dialog.initOwner(owner);
            dialog.initModality(Modality.WINDOW_MODAL);
        }
        dialog.setTitle("正在下载更新");
        dialog.setResizable(false);

        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(320);
        Label pct = new Label("准备下载...");
        VBox box = new VBox(10, new Label("正在下载新版本 " + info.tag() + " ..."), bar, pct);
        box.setPadding(new Insets(16));
        dialog.setScene(new Scene(box));
        // 下载中禁止手动关闭，避免中断留下半成品
        dialog.setOnCloseRequest(Event::consume);
        dialog.show();

        svc.downloadAndApply(info, new UpdateService.ApplyCallback() {
            @Override
            public void onProgress(long bytesRead, long total) {
                Platform.runLater(() -> {
                    if (total > 0) {
                        double r = (double) bytesRead / total;
                        bar.setProgress(r);
                        pct.setText(String.format("%.0f%%  (%.1f / %.1f MB)",
                                r * 100, bytesRead / 1048576.0, total / 1048576.0));
                    } else {
                        bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                        pct.setText(String.format("%.1f MB", bytesRead / 1048576.0));
                    }
                });
            }

            @Override
            public void onReadyToRestart() {
                Platform.runLater(() -> {
                    dialog.close();
                    Alert done = new Alert(Alert.AlertType.INFORMATION,
                            "更新包已就绪，应用将关闭以完成更新。", ButtonType.OK);
                    done.setTitle("更新");
                    done.setHeaderText(null);
                    if (owner != null) done.initOwner(owner);
                    done.showAndWait();
                    Platform.exit();
                });
            }

            @Override
            public void onOpenPage(String url) {
                Platform.runLater(() -> {
                    dialog.close();
                    openUrl(url);
                    info(owner, "已在浏览器打开下载页，请手动下载安装。");
                });
            }

            @Override
            public void onError(Exception e) {
                Platform.runLater(() -> {
                    dialog.close();
                    error(owner, "更新失败：" + msg(e));
                });
            }
        });
    }

    /** 手动检查：结果始终反馈。 */
    static void checkManually(UpdateService svc, Window owner) {
        svc.checkManually(new UpdateService.CheckCallback() {
            @Override
            public void onUpdateAvailable(ReleaseInfo info) {
                Platform.runLater(() -> promptUpdate(svc, info, owner));
            }

            @Override
            public void onUpToDate() {
                Platform.runLater(() -> info(owner, "已是最新版本。"));
            }

            @Override
            public void onError(Exception e) {
                Platform.runLater(() -> error(owner, "检查失败，请稍后重试。\n" + msg(e)));
            }
        });
    }

    /** 用系统默认浏览器打开地址（Windows，避免引入 java.desktop 模块）。 */
    static void openUrl(String url) {
        try {
            new ProcessBuilder("cmd.exe", "/c", "start", "", url).start();
        } catch (Exception ignored) {
        }
    }

    private static void info(Window owner, String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        a.setHeaderText(null);
        if (owner != null) a.initOwner(owner);
        a.showAndWait();
    }

    private static void error(Window owner, String text) {
        Alert a = new Alert(Alert.AlertType.ERROR, text, ButtonType.OK);
        a.setHeaderText(null);
        if (owner != null) a.initOwner(owner);
        a.showAndWait();
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
