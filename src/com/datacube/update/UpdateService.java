package com.datacube.update;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** I/O and callbacks use injected executors; one cancellable update may be active. */
public final class UpdateService implements AutoCloseable {
    private final UpdateChecker checker;
    private final UpdateApplier applier;
    private final UpdateTaskDispatcher tasks;
    private final Supplier<InstallMode> mode;
    private final Supplier<Optional<Path>> appDir;
    private final Supplier<String> currentVersion;
    private final BooleanSupplier supportedPlatform;
    private UpdateCancellation active;
    private boolean closed, handedOff;

    public UpdateService(Executor background, Consumer<Runnable> callbacks) {
        this(background, callbacks, new UpdateChecker(), new UpdateApplier(), InstallMode::detect,
                InstallMode::appDir, AppVersion::current,
                () -> System.getProperty("os.name", "").startsWith("Windows")
                        && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch", "")));
    }
    UpdateService(Executor background, Consumer<Runnable> callbacks, UpdateChecker checker, UpdateApplier applier,
                  Supplier<InstallMode> mode, Supplier<Optional<Path>> appDir, Supplier<String> version,
                  BooleanSupplier supportedPlatform) {
        this.tasks = new UpdateTaskDispatcher(background, callbacks);
        this.checker = checker; this.applier = applier; this.mode = mode; this.appDir = appDir;
        this.currentVersion = version; this.supportedPlatform = supportedPlatform;
    }
    public interface CheckCallback {
        void onUpdateAvailable(ReleaseInfo info);
        void onUpToDate();
        void onError(Exception e);
    }
    public interface ApplyCallback {
        void onProgress(long bytesRead, long total);
        /** Helper started; installation/restart is not yet confirmed. Normal window close guards still apply. */
        void onReadyToRestart();
        void onOpenPage(String url);
        default void onManualRequired(String url, String reason) { onOpenPage(url); }
        default void onCancelled() { }
        void onError(Exception e);
    }
    public boolean canAutomaticallyUpdate(ReleaseInfo info) {
        return supportedPlatform.getAsBoolean() && applier.configured() && info.verificationAvailable();
    }
    public void checkInBackground(Consumer<ReleaseInfo> onNewVersion) {
        Objects.requireNonNull(onNewVersion);
        if (AppVersion.isDev()) return;
        tasks.execute(() -> {
            try { checker.checkForUpdate().ifPresent(info -> tasks.dispatch(() -> onNewVersion.accept(info))); }
            catch (Exception ignored) { }
        });
    }
    public void checkManually(CheckCallback cb) {
        tasks.execute(() -> {
            try {
                var update = checker.checkForUpdate();
                if (update.isPresent()) tasks.dispatch(() -> cb.onUpdateAvailable(update.get()));
                else tasks.dispatch(cb::onUpToDate);
            } catch (Exception failure) { tasks.dispatch(() -> cb.onError(failure)); }
        });
    }
    public void downloadAndApply(ReleaseInfo info, ApplyCallback cb) {
        Objects.requireNonNull(info); Objects.requireNonNull(cb);
        UpdateCancellation control;
        synchronized (this) {
            if (closed) return;
            if (active != null || handedOff) { tasks.dispatch(() -> cb.onError(new IllegalStateException("已有更新请求正在处理或等待退出"))); return; }
            active = control = new UpdateCancellation(Duration.ofMinutes(10));
        }
        try {
            tasks.execute(() -> {
                try (control) {
                    control.bindWorker();
                    if (!canAutomaticallyUpdate(info)) {
                        tasks.dispatch(() -> cb.onManualRequired(UpdateChecker.releasesPage(),
                                "此构建或发布缺少受信任的更新验证信息，自动执行已关闭。请手动核对并安装官方版本。"));
                        return;
                    }
                    InstallMode selected = mode.get();
                    Optional<Path> target = appDir.get();
                    if (selected == InstallMode.UNKNOWN || target.isEmpty()) {
                        tasks.dispatch(() -> cb.onManualRequired(UpdateChecker.releasesPage(), "无法确认当前安装位置，请手动升级。"));
                        return;
                    }
                    var prepared = applier.prepare(info, currentVersion.get(), selected, target.orElseThrow(),
                            control, (bytes, total) -> tasks.dispatch(() -> {
                                if (!control.cancelled()) cb.onProgress(bytes, total);
                            }));
                    applier.launch(prepared, control);
                    synchronized (UpdateService.this) { handedOff = true; }
                    tasks.dispatch(cb::onReadyToRestart);
                } catch (CancellationException cancelled) {
                    tasks.dispatch(cb::onCancelled);
                } catch (Exception failure) {
                    if (control.cancelled()) tasks.dispatch(cb::onCancelled);
                    else tasks.dispatch(() -> cb.onError(failure));
                } finally {
                    synchronized (UpdateService.this) { if (active == control) active = null; }
                }
            });
        } catch (RuntimeException rejected) {
            synchronized (this) { if (active == control) active = null; }
            control.close();
            tasks.dispatch(() -> cb.onError(rejected));
        }
    }
    public synchronized boolean cancelDownload() { return active != null && active.cancel(); }
    @Override public synchronized void close() {
        closed = true;
        tasks.close();
        if (active != null) { active.cancel(); active.close(); active = null; }
    }
}
