package com.datacube.provider.jdbc;

import com.datacube.spi.*;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.sqleditor.SqlScriptSplitter;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/** Serial execution with a single retention allowance and bounded completion snapshots. */
public final class JdbcScriptExecutor {
    private JdbcScriptExecutor() {}
    public static List<ScriptOutcome> execute(SqlRunner runner, Connection connection, String script,
            String schema, SqlExecutionOptions options, ScriptErrorPolicy policy, boolean oracle) {
        var statements = SqlScriptSplitter.split(script, oracle);
        var outcomes = new ArrayList<ScriptOutcome>(Math.min(statements.size(), options.resultBudget().limits().results() + 1));
        long started = System.currentTimeMillis();
        boolean continueAll = false;
        for (int i = 0; i < statements.size(); i++) {
            if (options.control().cancellationRequested()) break;
            if (i >= options.resultBudget().limits().results() || options.resultBudget().exhausted()) {
                outcomes.add(new ScriptOutcome(i + 1, "", QueryResult.error("结果预算已用尽；第 " + (i + 1)
                        + " 条起的 " + (statements.size() - i) + " 条语句未执行。已有事务未自动提交或回滚，请分批执行。", 0)));
                options.publish(new SqlScriptProgress(outcomes, i, statements.size(), System.currentTimeMillis() - started));
                break;
            }
            String sql = statements.get(i);
            QueryResult result = runner.execute(connection, sql, schema, options);
            ScriptOutcome retained = options.resultBudget().retain(i + 1, sql, result);
            outcomes.add(retained);
            options.publish(new SqlScriptProgress(outcomes, i + 1, statements.size(), System.currentTimeMillis() - started));
            if (result.failureKind == QueryResult.FailureKind.CANCELLED) break;
            if (result.kind == QueryResult.Kind.ERROR && !continueAll && i + 1 < statements.size()
                    && !options.control().cancellationRequested()) {
                ScriptErrorPolicy.Decision decision = policy == null ? ScriptErrorPolicy.Decision.ABORT
                        : policy.onError(i + 1, sql, retained.result().errorMessage);
                if (decision == ScriptErrorPolicy.Decision.ABORT) break;
                if (decision == ScriptErrorPolicy.Decision.CONTINUE_ALL) continueAll = true;
            }
        }
        return List.copyOf(outcomes);
    }
}
