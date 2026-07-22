package com.datacube.spi;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;

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
     * 折叠未加引号标识符的大小写以匹配数据字典中的实际存储形式。
     *
     * <p>Oracle 未加引号标识符默认大写、PG 默认小写。用户在 SQL 编辑器
     * 手填 schema 或书写表/别名时不必关心大小写，业务层用本方法归一后再去
     * 切 schema 或查元数据。默认原样返回。
     */
    default String foldUnquotedIdentifier(String ident) {
        return ident;
    }

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

    /**
     * 将一个值渲染为本方言的 SQL 字面量（用于导出 INSERT 脚本）。
     *
     * <p>收敛各库对 NULL / 数字 / 布尔 / 字符串 / 二进制 / 日期的字面量差异，
     * 使 {@code export.SqlScriptExporter} 不出现任何数据库专属语法。
     */
    String sqlLiteral(Object v);

    /**
     * 查询指定表的列注释（列名 → 注释）；无注释或不支持时返回空 Map。
     *
     * <p>默认空实现；各方言按数据字典（Oracle {@code ALL_COL_COMMENTS} /
     * PG {@code col_description}）覆盖。键为数据字典中存储的列名形式（Oracle 大写、PG 小写）。
     */
    default Map<String, String> columnComments(Connection conn, String schema, String table) throws SQLException {
        return Collections.emptyMap();
    }
}
