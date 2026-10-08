package com.datacube.fx;

import com.datacube.fx.task.FxSerialTaskQueue;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.SqlCompletionMetadata;
import com.datacube.service.SqlCompletionMetadata.Target;
import com.datacube.service.SqlCompletionMetadata.Names;
import com.datacube.spi.SqlExecutionControl;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** FX-owned bounded cache with at most one physically unfinished lookup per editor. */
final class SqlCompletionLookup implements AutoCloseable {
    @FunctionalInterface interface Loader { Names load(Target target, SqlExecutionControl control) throws Exception; }
    private final FxSerialTaskQueue queue;
    private final FxTaskRunner runner;
    private final Loader loader;
    private final Runnable refresh;
    private final Consumer<String> notice;
    private final LinkedHashMap<Target, Names> cache = new LinkedHashMap<>(16, .75f, true);
    private final PauseTransition timeout = new PauseTransition(Duration.seconds(SqlCompletionMetadata.TIMEOUT_SECONDS));
    private volatile Request active;
    private final AtomicBoolean cancelling = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private long generation;

    SqlCompletionLookup(FxTaskRunner runner, FxSerialTaskQueue queue, Loader loader, Runnable refresh, Consumer<String> notice) {
        this.runner = runner; this.queue = queue; this.loader = loader; this.refresh = refresh; this.notice = notice;
    }
    List<String> request(Target target) {
        if (closed.get()) return List.of();
        Names names = cache.get(target);
        if (names != null) { notice.accept(names.notice()); return names.values(); }
        if (active != null) {
            if (!active.target.equals(target)) abandon("等待上一次元数据读取结束");
            return List.of();
        }
        if (cancelling.get()) { notice.accept("等待驱动完成元数据取消"); return List.of(); }
        var request = new Request(target, generation); active = request;
        if (closed.get()) { request.control.requestCancellation(); active = null; return List.of(); }
        notice.accept("正在读取指定目标的元数据…");
        timeout.setOnFinished(event -> { if (active == request) abandon("元数据读取超时；Ctrl+Space 可重试"); });
        timeout.playFromStart();
        try { queue.submit(() -> {
            Names result;
            try { result = loader.load(target, request.control); }
            catch (Exception failure) { result = new Names(List.of(), "元数据读取失败；Ctrl+Space 可重试"); }
            Names completed = result;
            Platform.runLater(() -> finish(request, completed));
            return null;
        }, ignored -> {}, ignored -> finish(request, new Names(List.of(), "元数据任务不可用"))); }
        catch (java.util.concurrent.RejectedExecutionException unavailable) {
            finish(request, new Names(List.of(), "元数据任务不可用"));
        }
        return List.of();
    }
    private void finish(Request request, Names names) {
        if (active != request) return;
        active = null; timeout.stop();
        if (closed.get() || request.generation != generation || request.abandoned) return;
        remember(request.target, names);
        notice.accept(names.notice());
        if (!names.values().isEmpty()) refresh.run();
    }
    private void remember(Target target, Names names) {
        cache.put(target, names);
        while (cache.size() > 16) cache.remove(cache.keySet().iterator().next());
    }
    /** Requesting cancellation is not proof that the JDBC call/close has physically finished. */
    private void abandon(String message) {
        if (active == null || active.abandoned) return;
        Request request = active; request.abandoned = true; timeout.stop();
        request.control.requestCancellation();
        remember(request.target, new Names(List.of(), message));
        notice.accept(message);
        cancelDriver(request);
        // Keep the task's completion path alive to release the active slot after close, including late connect.
    }
    private void cancelDriver(Request request) {
        // JDBC cancel may block. It must never execute on the FX thread, or accumulate workers.
        if (!cancelling.compareAndSet(false, true)) return;
        try { runner.submit(() -> {
            try { request.control.cancel(); } catch (Exception ignored) {}
            finally { cancelling.set(false); }
        }); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { cancelling.set(false); }
    }
    void retryFailures() { cache.entrySet().removeIf(entry -> entry.getValue().values().isEmpty()); }
    void invalidate() {
        generation++; cache.clear(); abandon("补全上下文已变化，旧请求已取消"); cache.clear();
    }
    @Override public void close() {
        // Resource cleanup runs off FX. Publish cancellation before queuing any UI cleanup.
        if (!closed.compareAndSet(false, true)) return;
        Request request = active;
        if (request != null) { request.control.requestCancellation(); cancelDriver(request); }
        Runnable cleanup = () -> { generation++; cache.clear(); timeout.stop(); active = null; };
        if (Platform.isFxApplicationThread()) cleanup.run(); else Platform.runLater(cleanup);
    }
    private static final class Request {
        final Target target; final long generation; final SqlExecutionControl control = new SqlExecutionControl();
        boolean abandoned;
        Request(Target target, long generation) { this.target = target; this.generation = generation; }
    }
}
