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
     * @param conn    连接（调用方负责生命周期）
     * @param sql     单条 SQL（不含尾部分号）
     * @param schema  可选 schema；非空时先切换 search_path
     * @param maxRows 查询结果最大保留行数（{@code <=0} 不限制）
     */
    QueryResult execute(Connection conn, String sql, String schema, int maxRows);

    /**
     * 多语句逐条执行；遇失败语句时通过 {@code policy} 决定继续/全部继续/中止，
     * 每条结果（含失败）作为一条 {@link ScriptOutcome} 返回。
     *
     * @param maxRows 每条查询结果最大保留行数（{@code <=0} 不限制）
     * @param policy  遇错处置回调；{@code null} 时遇错即中止（等价历史行为）
     */
    List<ScriptOutcome> executeScript(Connection conn, String script, String schema, int maxRows,
                                      ScriptErrorPolicy policy);

    /**
     * 生成执行计划，结果以单列多行文本承载（首列逐行拼接即计划文本）。
     *
     * <p>各库机制不同：PG 为单条 {@code EXPLAIN [ANALYZE] <sql>}；
     * Oracle 为 {@code EXPLAIN PLAN FOR} + {@code DBMS_XPLAN} 两步。由具体实现自管流程。
     * 失败时以 {@link QueryResult#error} 承载，不抛异常。
     *
     * @param sql     单条 SQL（不含尾部分号）
     * @param analyze 是否实际执行以获取真实行数/耗时
     */
    QueryResult explain(Connection conn, String sql, String schema, boolean analyze);
}
