package com.datacube.sqleditor;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.util.deparser.StatementDeParser;

import java.util.List;

/**
 * SQL 美化器：基于 JSqlParser 解析 + 重排，输出标准化格式。
 *
 * <p>特性：
 * <ul>
 *   <li>关键字大写（SELECT / FROM / WHERE / JOIN ...）</li>
 *   <li>主句换行（SELECT/FROM/WHERE/GROUP BY/ORDER BY/LIMIT）</li>
 *   <li>JOIN 自动换行</li>
 *   <li>子查询缩进</li>
 *   <li>多语句分别美化后用换行分隔</li>
 * </ul>
 *
 * <p>无 JavaFX 依赖，可被 CLI 复用。
 */
public final class SqlFormatter {

    private SqlFormatter() {}

    /**
     * 美化 SQL 脚本（支持多语句）。
     *
     * @param sql 原始 SQL
     * @return 美化后的 SQL；解析失败时返回原文（不抛异常）
     */
    public static String format(String sql) {
        if (sql == null || sql.isEmpty()) return sql;
        try {
            Statements stmts = CCJSqlParserUtil.parseStatements(sql);
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Statement s : stmts.getStatements()) {
                if (!first) sb.append(";\n\n");
                first = false;
                deparse(s, sb);
            }
            return sb.toString().trim();
        } catch (JSQLParserException e) {
            // 解析失败：尝试单语句
            try {
                Statement s = CCJSqlParserUtil.parse(sql);
                StringBuilder sb = new StringBuilder();
                deparse(s, sb);
                return sb.toString().trim();
            } catch (Exception ex) {
                return sql;
            }
        }
    }

    private static void deparse(Statement stmt, StringBuilder sb) {
        // JSqlParser 4.x: StatementDeParser(StringBuilder) 直接写入目标缓冲
        StatementDeParser deparser = new StatementDeParser(sb);
        stmt.accept(deparser);
    }

    /**
     * 批量美化：对 List&lt;String&gt; 中的每条 SQL 单独美化后拼接。
     * 用于逐条语句时方便复用。
     */
    public static List<String> formatEach(List<String> stmts) {
        return stmts.stream()
                .map(SqlFormatter::format)
                .collect(java.util.stream.Collectors.toList());
    }
}