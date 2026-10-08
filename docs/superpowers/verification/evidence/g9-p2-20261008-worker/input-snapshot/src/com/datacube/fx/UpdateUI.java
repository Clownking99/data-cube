package com.datacube.fx;

import com.datacube.update.ReleaseInfo;
import com.datacube.update.UpdateService;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
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
 * <p>{@link UpdateService} 通过应用注入的 JavaFX 分发器触发回调，
 * 因此本类直接更新 UI；应用退出仍由主窗口正常关闭流程负责。
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

        ButtonType update = new ButtonType(svc.canAutomaticallyUpdate(info) ? "验证并准备更新" : "手动获取更新");
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
        Button cancel = new Button("取消");
        cancel.setOnAction(event -> { svc.cancelDownload(); cancel.setDisable(true); pct.setText("正在取消..."); });
        VBox box = new VBox(10, new Label("正在验证并下载新版本 " + info.tag() + " ..."), bar, pct, cancel);
        box.setPadding(new Insets(16));
        dialog.setScene(new Scene(box));
        dialog.setOnCloseRequest(event -> { svc.cancelDownload(); event.consume(); });
        dialog.show();

        svc.downloadAndApply(info, new UpdateService.ApplyCallback() {
            @Override
            public void onProgress(long bytesRead, long total) {
                if (total > 0) {
                    double r = (double) bytesRead / total;
                    bar.setProgress(r);
                    pct.setText(String.format("%.0f%%  (%.1f / %.1f MB)",
                            r * 100, bytesRead / 1048576.0, total / 1048576.0));
                } else {
                    bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    pct.setText(String.format("%.1f MB", bytesRead / 1048576.0));
                }
            }

            @Override
            public void onReadyToRestart() {
                dialog.close();
                Alert done = new Alert(Alert.AlertType.INFORMATION,
                        "更新已验证并交接，尚未确认安装或重启成功。\n请处理未提交事务后正常关闭主窗口；5 分钟内未关闭则取消替换。\n便携版会保留旧版备份；安装版由安装程序处理。", ButtonType.OK);
                done.setTitle("更新");
                done.setHeaderText(null);
                if (owner != null) done.initOwner(owner);
                done.showAndWait();
                // The normal main-window close path owns transaction and resource cleanup.
            }

            @Override
            public void onOpenPage(String url) {
                dialog.close();
                openUrl(url);
                info(owner, "已在浏览器打开下载页，请手动下载安装。");
            }

            @Override
            public void onManualRequired(String url, String reason) {
                dialog.close();
                info(owner, reason);
                openUrl(url);
            }

            @Override
            public void onCancelled() { dialog.close(); info(owner, "更新已取消，当前版本保持可用。"); }

            @Override
            public void onError(Exception e) {
                dialog.close();
                error(owner, "更新失败：" + msg(e));
            }
        });
    }

    /** 手动检查：结果始终反馈。 */
    static void checkManually(UpdateService svc, Window owner) {
        svc.checkManually(new UpdateService.CheckCallback() {
            @Override
            public void onUpdateAvailable(ReleaseInfo info) {
                promptUpdate(svc, info, owner);
            }

            @Override
            public void onUpToDate() {
                info(owner, "已是最新版本。");
            }

            @Override
            public void onError(Exception e) {
                error(owner, "检查失败，请稍后重试。\n" + msg(e));
            }
        });
    }

    /** 用系统默认浏览器打开地址（Windows，避免引入 java.desktop 模块）。 */
    static void openUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) return;
            new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", uri.toASCIIString()).start();
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
