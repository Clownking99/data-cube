package com.datacube.update;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 自动更新编排：聚合 {@link UpdateChecker} / {@link InstallMode} / {@link UpdateApplier}，
 * 对 UI 暴露"检查"与"下载并应用"两组入口。
 *
 * <p>所有网络/IO 在后台守护线程执行；本类不依赖 JavaFX，回调在后台线程触发，
 * 由 UI 层自行包装到 {@code Platform.runLater}。
 */
public final class UpdateService {

    private final UpdateChecker checker = new UpdateChecker();
    private final UpdateApplier applier = new UpdateApplier();

    /** 手动检查结果回调（三选一触发）。 */
    public interface CheckCallback {
        void onUpdateAvailable(ReleaseInfo info);
        void onUpToDate();
        void onError(Exception e);
    }

    /** 下载并应用过程回调。 */
    public interface ApplyCallback {
        void onProgress(long bytesRead, long total);
        /** 下载完成、替换者已拉起——UI 应提示并退出应用。 */
        void onReadyToRestart();
        /** 形态未知或对应资产缺失——UI 应打开该地址让用户手动下载。 */
        void onOpenPage(String url);
        void onError(Exception e);
    }

    /** 启动时后台静默检查：仅在发现新版时回调；dev 构建与任何失败均静默跳过。 */
    public void checkInBackground(Consumer<ReleaseInfo> onNewVersion) {
        if (AppVersion.isDev()) return;
        Thread t = new Thread(() -> {
            try {
                checker.checkForUpdate().ifPresent(onNewVersion);
            } catch (Exception ignored) {
                // 启动自检失败保持静默，不打扰用户
            }
        }, "Update-Check");
        t.setDaemon(true);
        t.start();
    }

    /** 手动检查：结果始终回调（有新版 / 已最新 / 失败）。 */
    public void checkManually(CheckCallback cb) {
        Thread t = new Thread(() -> {
            try {
                Optional<ReleaseInfo> up = checker.checkForUpdate();
                if (up.isPresent()) {
                    cb.onUpdateAvailable(up.get());
                } else {
                    cb.onUpToDate();
                }
            } catch (Exception e) {
                cb.onError(e);
            }
        }, "Update-Check-Manual");
        t.setDaemon(true);
        t.start();
    }

    /** 按运行形态下载对应资产并应用；UNKNOWN 或资产缺失时改为打开网页。 */
    public void downloadAndApply(ReleaseInfo info, ApplyCallback cb) {
        Thread t = new Thread(() -> {
            try {
                InstallMode mode = InstallMode.detect();
                if (mode == InstallMode.INSTALLED && info.setupExeUrl() != null) {
                    Path dest = applier.tempFile("DataCube-" + safeTag(info) + "-setup.exe");
                    applier.download(info.setupExeUrl(), dest, cb::onProgress);
                    applier.launchInstaller(dest);
                    cb.onReadyToRestart();
                } else if (mode == InstallMode.PORTABLE && info.portableZipUrl() != null) {
                    Path appDir = InstallMode.appDir()
                            .orElseThrow(() -> new IllegalStateException("无法定位应用目录"));
                    Path dest = applier.tempFile("DataCube-" + safeTag(info) + "-portable.zip");
                    applier.download(info.portableZipUrl(), dest, cb::onProgress);
                    applier.launchPortableUpdate(dest, appDir);
                    cb.onReadyToRestart();
                } else {
                    String url = info.htmlUrl() != null ? info.htmlUrl() : UpdateChecker.releasesPage();
                    cb.onOpenPage(url);
                }
            } catch (Exception e) {
                cb.onError(e);
            }
        }, "Update-Apply");
        t.setDaemon(true);
        t.start();
    }

    private static String safeTag(ReleaseInfo info) {
        String tag = info.tag() != null ? info.tag() : info.version();
        if (tag == null) return "latest";
        return tag.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
