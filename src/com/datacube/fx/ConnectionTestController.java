package com.datacube.fx;

import com.datacube.fx.task.FxTaskScope;
import com.datacube.spi.model.ConnConfig;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

/** Owns one dialog's test state; UI methods and completion callbacks run on the FX thread. */
final class ConnectionTestController implements AutoCloseable {
    enum Phase {
        IDLE("尚未测试当前配置"), TESTING("正在测试连接…"),
        SUCCEEDED("连接成功，可保存配置"),
        FAILED("连接失败。请检查主机和端口、数据库或服务名、凭据及网络后重试。"),
        UNAVAILABLE("无法开始连接测试，请稍后重试");

        private final String text;
        Phase(String text) { this.text = text; }
        String text() { return text; }
    }

    @FunctionalInterface interface Submitter {
        void submit(Callable<String> work, Consumer<String> success, Consumer<Throwable> failure);
    }

    private final Submitter submitter;
    private final Runnable stop;
    private final Function<ConnConfig, String> operation;
    private final ReadOnlyObjectWrapper<Phase> phase = new ReadOnlyObjectWrapper<>(Phase.IDLE);
    private boolean closed;

    ConnectionTestController(FxTaskScope scope, Function<ConnConfig, String> operation) {
        this(scope::submit, scope::close, operation);
    }

    ConnectionTestController(Submitter submitter, Runnable stop, Function<ConnConfig, String> operation) {
        this.submitter = Objects.requireNonNull(submitter);
        this.stop = Objects.requireNonNull(stop);
        this.operation = Objects.requireNonNull(operation);
    }

    ReadOnlyObjectProperty<Phase> phaseProperty() { return phase.getReadOnlyProperty(); }
    Phase phase() { return phase.get(); }

    void start(ConnConfig snapshot) {
        if (closed || phase() == Phase.TESTING) return;
        Objects.requireNonNull(snapshot);
        phase.set(Phase.TESTING);
        try {
            submitter.submit(() -> operation.apply(snapshot),
                    error -> finish(error == null ? Phase.SUCCEEDED : Phase.FAILED),
                    error -> finish(Phase.FAILED));
        } catch (RuntimeException rejected) {
            finish(Phase.UNAVAILABLE);
        }
    }

    void edited() {
        if (!closed && phase() != Phase.TESTING) phase.set(Phase.IDLE);
    }

    private void finish(Phase result) {
        if (!closed) phase.set(result);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        stop.run();
    }
}
