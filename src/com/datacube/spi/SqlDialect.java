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
}
