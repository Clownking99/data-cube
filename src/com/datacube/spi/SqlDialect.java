package com.datacube.spi;

/**
 * SQL 方言能力：收敛所有数据库专属的 SQL 片段。
 *
 * <p>业务层通过本接口拼接标识符引用、分页、schema 切换等，
 * 不出现任何数据库专属语法。
 */
public interface SqlDialect {

    /** 标识符引用（PG: 双引号）。 */
    String quoteIdentifier(String ident);

    /** 分页子句（PG: {@code LIMIT <limit> OFFSET <offset>}）。 */
    String pageClause(long offset, int limit);

    /**
     * 切换当前 schema 的 SQL（PG: {@code SET search_path TO <schema>}）；
     * 不支持则返回 {@code null}。
     */
    String currentSchemaSql(String schema);

    /** 是否有 schema 层级（PG=true；某些库 user=schema 或无 schema）。 */
    boolean hasSchemaLevel();

    /**
     * 生成查看执行计划的 SQL（PG: {@code EXPLAIN <sql>} / {@code EXPLAIN ANALYZE <sql>}）。
     *
     * <p>默认实现适用于大多数数据库；{@code analyze=true} 会真正执行 SQL。
     * 传入的 {@code sql} 应为单条语句（不含尾部分号）。
     *
     * @param sql     单条 SQL
     * @param analyze 是否 ANALYZE（实际执行以获取真实耗时/行数）
     */
    default String explainSql(String sql, boolean analyze) {
        return (analyze ? "EXPLAIN ANALYZE " : "EXPLAIN ") + sql;
    }
}
