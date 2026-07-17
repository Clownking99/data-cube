package com.datacube.spi;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;

import java.sql.Connection;
import java.util.List;

/**
 * SQL 执行能力：单条 / 多语句脚本执行。
 *
 * <p>失败时以 {@link QueryResult#error} 承载，不抛异常（便于 UI 展示）。
 * schema 切换等方言差异由实现委托 {@link SqlDialect}。
 */
public interface SqlRunner {

    /**
     * 执行单条 SQL。
     *
     * @param conn   连接（调用方负责生命周期）
     * @param sql    单条 SQL（不含尾部分号）
     * @param schema 可选 schema；非空时先切换 search_path
     */
    QueryResult execute(Connection conn, String sql, String schema);

    /**
     * 多语句逐条执行；遇错停止后续并把错误作为一条结果返回。
     */
    List<ScriptOutcome> executeScript(Connection conn, String script, String schema);
}
