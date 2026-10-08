package com.datacube.service;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.ConnectionSafetyOptions;
import java.util.Objects;

/** A pinned relational target. The validator also detects delete/recreate and ABA changes. */
public final class WriteTarget {
    static final String CHANGED = "连接配置已变化或删除，请重新打开页面后确认写入";
    private final ConnConfig config;
    private final Runnable validator;
    private final java.util.function.Function<Runnable, Runnable> subscribe;
    private final Object authority;

    WriteTarget(ConnConfig config, Runnable validator) {
        this(config, validator, ignored -> () -> {}, null);
    }

    WriteTarget(ConnConfig config, Runnable validator, java.util.function.Function<Runnable, Runnable> subscribe,
                Object authority) {
        this.config = Objects.requireNonNull(config);
        this.validator = Objects.requireNonNull(validator);
        this.subscribe = Objects.requireNonNull(subscribe);
        this.authority = authority;
    }

    ConnConfig config() { return config; }
    void validate() { validator.run(); }
    boolean belongsTo(Object expected) { return authority == expected; }
    public ConnectionSafetyOptions safety() { return ConnectionSafetyOptions.from(config); }
    public Runnable whenChanged(Runnable callback) { return subscribe.apply(callback); }

    public String blockedReason() {
        if (safety().readOnly()) return "只读连接不允许写入";
        try { validate(); return ""; }
        catch (IllegalStateException rejected) { return CHANGED; }
    }

    public String description() {
        return "连接: " + config.name() + " [" + config.id() + "]\n"
                + "目标: " + config.type() + " / " + config.host() + ":" + config.port()
                + " / " + config.database() + " / " + config.username() + "\n"
                + "环境: " + safety().environment().label();
    }

    @Override public String toString() { return "WriteTarget[redacted]"; }
}
