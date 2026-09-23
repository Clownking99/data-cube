package com.datacube.service;

import com.datacube.spi.model.ConnectionEnvironment;
import com.datacube.sqleditor.SqlSafetyAnalyzer;
import com.datacube.sqleditor.SqlSafetyPolicy;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One immutable request and a non-transferable, one-shot confirmation; no UI dependency. */
public final class WriteOperation<T> {
    @FunctionalInterface interface Action<T> { T run(Runnable revalidate) throws SQLException; }
    private final WriteTarget target;
    private final String operation;
    private final String scope;
    private final String blocked;
    private final boolean requiresCurrentTarget;
    private final boolean confirmationRequired;
    private final Action<T> action;
    private final AtomicBoolean started = new AtomicBoolean();

    WriteOperation(WriteTarget target, String operation, String scope, Action<T> action) {
        this(target, operation, scope, target.safety().readOnly() ? "只读连接不允许写入" : "",
                true, target.safety().environment() == ConnectionEnvironment.PRODUCTION, action);
    }

    private WriteOperation(WriteTarget target, String operation, String scope, String blocked,
                           boolean requiresCurrentTarget, boolean confirmationRequired, Action<T> action) {
        this.target = Objects.requireNonNull(target);
        this.operation = Objects.requireNonNull(operation);
        this.scope = Objects.requireNonNull(scope);
        this.blocked = blocked;
        this.requiresCurrentTarget = requiresCurrentTarget;
        this.confirmationRequired = confirmationRequired;
        this.action = Objects.requireNonNull(action);
    }

    static <T> WriteOperation<T> sql(WriteTarget target, String operation, String scope,
                                    String sql, boolean oracle, boolean forceWrite, Action<T> action) {
        var analysis = SqlSafetyAnalyzer.analyze(sql, oracle);
        var decision = SqlSafetyPolicy.decide(analysis, target.safety());
        boolean commit = "COMMIT".equals(SqlSafetyAnalyzer.transactionCompletionKeyword(sql, oracle));
        boolean write = forceWrite || commit || analysis.statements().stream().anyMatch(s ->
                s.kind() != SqlSafetyAnalyzer.StatementKind.READ
                        && !"ROLLBACK".equals(s.firstKeyword()));
        String blocked = decision.blocked() ? decision.message()
                : write && target.safety().readOnly() ? "只读连接不允许写入" : "";
        String risks = decision.relevantStatements().stream().map(s -> "#" + s.index() + " "
                + (s.risks().contains(SqlSafetyAnalyzer.Risk.MISSING_WHERE) ? "缺少 WHERE " : "")
                + (s.risks().contains(SqlSafetyAnalyzer.Risk.DESTRUCTIVE_DDL) ? "破坏性 DDL " : "")
                + (s.risks().contains(SqlSafetyAnalyzer.Risk.UNKNOWN_STATEMENT) ? "未知语句 " : ""))
                .collect(java.util.stream.Collectors.joining("；"));
        return new WriteOperation<>(target, operation, scope + (risks.isBlank() ? "" : "\n风险: " + risks), blocked, write,
                decision.confirmationRequired() || write
                        && target.safety().environment() == ConnectionEnvironment.PRODUCTION, action);
    }

    /** The caller obtains this only after presenting this request to the user. */
    public Confirmation confirm() { validate(); return new Confirmation(this); }
    public boolean confirmationRequired() { return confirmationRequired; }
    public boolean production() { return target.safety().environment() == ConnectionEnvironment.PRODUCTION; }
    public String description() { return target.description() + "\n操作: " + operation + "\n范围: " + scope; }

    public String blockedReason() {
        try { validate(); return ""; }
        catch (IllegalStateException rejected) { return rejected.getMessage(); }
    }

    void validate() {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("写入请求已取消");
        if (!blocked.isEmpty()) throw new IllegalStateException(blocked);
        if (requiresCurrentTarget) target.validate();
    }

    public T execute(Confirmation confirmation) throws SQLException {
        validate();
        if ((confirmation != null && confirmation.request != this)
                || (confirmationRequired && confirmation == null)) {
            throw new IllegalStateException("写入确认缺失或不属于当前目标和请求");
        }
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("写入请求已使用，请重新确认");
        return action.run(this::validate);
    }

    public static final class Confirmation {
        private final WriteOperation<?> request;
        private Confirmation(WriteOperation<?> request) { this.request = request; }
        @Override public String toString() { return "WriteConfirmation[redacted]"; }
    }

    @Override public String toString() { return "WriteOperation[redacted]"; }
}
