package com.datacube.sqleditor;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlFormatter} 纯函数单测：多层嵌套子查询的换行缩进、普通括号保持行内、
 * 幂等性与语义保全（去空白去大小写后 token 序列一致）。不依赖数据库。
 */
class SqlFormatterTest {

    /** 行首含 >=2 个空格后紧跟指定关键字（表示被缩进的嵌套子句）。 */
    private static boolean hasIndentedClause(String text, String keyword) {
        return Pattern.compile("(?m)^ {2,}" + keyword + "\\b").matcher(text).find();
    }

    /** 存在顶格（无缩进）的指定关键字行。 */
    private static boolean hasTopLevelClause(String text, String keyword) {
        return Pattern.compile("(?m)^" + keyword + "\\b").matcher(text).find();
    }

    /** 去除全部空白并大写：用于语义保全的弱校验（token 序列不增删、不改字符）。 */
    private static String normalized(String s) {
        return s.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    @Test
    void nestedSubqueryInFrom() {
        String out = SqlFormatter.format("SELECT * FROM (SELECT id FROM t WHERE x=1) a");
        assertTrue(hasTopLevelClause(out, "SELECT"), "外层 SELECT 应顶格：\n" + out);
        assertTrue(hasIndentedClause(out, "SELECT"), "内层 SELECT 应缩进：\n" + out);
        assertTrue(hasIndentedClause(out, "FROM"), "内层 FROM 应缩进：\n" + out);
        assertTrue(hasIndentedClause(out, "WHERE"), "内层 WHERE 应缩进：\n" + out);
    }

    @Test
    void derivedTableAlias() {
        String out = SqlFormatter.format("SELECT a.id FROM (SELECT id FROM t) a");
        assertTrue(hasIndentedClause(out, "SELECT"), "派生表内层 SELECT 应缩进：\n" + out);
        // 别名 a 应保留在闭括号之后
        assertTrue(out.replaceAll("\\s+", "").contains(")a"), "别名应跟在 ) 之后：\n" + out);
    }

    @Test
    void cteBody() {
        String out = SqlFormatter.format("WITH x AS (SELECT id FROM t) SELECT * FROM x");
        assertTrue(hasTopLevelClause(out, "WITH"), "WITH 应顶格：\n" + out);
        assertTrue(hasIndentedClause(out, "SELECT"), "CTE 体内 SELECT 应缩进：\n" + out);
        assertTrue(hasTopLevelClause(out, "SELECT"), "主查询 SELECT 应顶格：\n" + out);
    }

    @Test
    void inSubquery() {
        String out = SqlFormatter.format("SELECT * FROM t WHERE id IN (SELECT id FROM s)");
        assertTrue(hasIndentedClause(out, "SELECT"), "IN 子查询内 SELECT 应缩进：\n" + out);
    }

    @Test
    void functionArgsStayInline() {
        String out = SqlFormatter.format("SELECT COALESCE(a, b, c) FROM t");
        assertTrue(out.contains("COALESCE(a, b, c)"), "函数参数应保持行内、不换行：\n" + out);
    }

    @Test
    void inListStaysInline() {
        String out = SqlFormatter.format("SELECT * FROM t WHERE id IN (1, 2, 3)");
        assertTrue(out.contains("(1, 2, 3)"), "普通 IN 列表应保持行内：\n" + out);
    }

    @Test
    void idempotent() {
        String[] samples = {
                "SELECT * FROM (SELECT id FROM t WHERE x=1) a",
                "WITH x AS (SELECT id FROM t) SELECT * FROM x",
                "SELECT COALESCE(a, b, c) FROM t WHERE id IN (SELECT id FROM s)"
        };
        for (String s : samples) {
            String once = SqlFormatter.format(s);
            String twice = SqlFormatter.format(once);
            assertEquals(once, twice, "美化应幂等：\n--- once ---\n" + once + "\n--- twice ---\n" + twice);
        }
    }

    @Test
    void semanticsPreserved() {
        String[] samples = {
                "select * from (select id from t where x=1) a",
                "with x as (select id from t) select * from x",
                "SELECT COALESCE(a, b, c) FROM t WHERE id IN (SELECT id FROM s) AND y BETWEEN 1 AND 9",
                "update t set a=1, b=2 where id in (select id from s)"
        };
        for (String s : samples) {
            String out = SqlFormatter.format(s);
            assertEquals(normalized(s), normalized(out),
                    "格式化前后 token 序列（去空白去大小写）应一致：\n" + s + "\n=>\n" + out);
        }
    }

    @Test
    void multiLevelNesting() {
        String out = SqlFormatter.format(
                "SELECT * FROM (SELECT id FROM (SELECT id FROM t WHERE x=1) inner1) outer1");
        // 两层子查询：应至少出现两处不同深度的缩进 SELECT
        assertTrue(Pattern.compile("(?m)^ {2,}SELECT\\b").matcher(out).results().count() >= 2,
                "多层嵌套应有多处缩进 SELECT：\n" + out);
    }

    @Test
    void complexDmlUsesReadableParenthesisSpacingAndClauseBoundaries() {
        String out = SqlFormatter.format(
                "INSERT INTO t (a,b) SELECT x,y FROM u WHERE z IN (SELECT z FROM v WHERE q=2) RETURNING id");

        assertTrue(out.contains("INSERT INTO t (a, b)"), "DML 列表前应保留可读空格：\n" + out);
        assertTrue(out.contains("WHERE z IN ("), "谓词子查询前应保留空格：\n" + out);
        assertTrue(out.contains("RETURNING id"), "RETURNING 应保持独立子句：\n" + out);
        assertTrue(!out.contains("WHERE("), "WHERE 与普通括号不能粘连：\n" + out);
    }

    @Test
    void caseAndWindowExpressionsHaveVisibleStructure() {
        String out = SqlFormatter.format(
                "SELECT CASE WHEN a=1 THEN 'x' WHEN a=2 THEN 'y' ELSE 'z' END AS label, "
                        + "SUM(amount) OVER (PARTITION BY customer_id ORDER BY created_at "
                        + "ROWS BETWEEN 3 PRECEDING AND CURRENT ROW) AS total FROM sales "
                        + "WHERE (a=1 OR b=2) AND c IN (1,2,3)");

        assertTrue(out.contains("CASE\n"), "CASE 应形成可扫描的表达式块：\n" + out);
        assertTrue(out.contains("WHEN a = 1 THEN 'x'"), "WHEN 条件应保持同一逻辑行：\n" + out);
        assertTrue(out.contains("ELSE 'z'"), "ELSE 应可独立定位：\n" + out);
        assertTrue(out.contains("END AS label"), "END 后的别名不能丢失：\n" + out);
        assertTrue(out.contains("OVER ("), "窗口定义前应有空格：\n" + out);
        assertTrue(out.contains("OVER (\n"), "复杂窗口定义应展开：\n" + out);
        assertTrue(out.contains("PARTITION BY customer_id\n"), "窗口分区应单独成行：\n" + out);
        assertTrue(out.contains("WHERE ("), "条件分组括号前应有空格：\n" + out);
    }

    @Test
    void cteListAndSetOperatorsBreakAtStatementBoundaries() {
        String out = SqlFormatter.format(
                "WITH a AS (SELECT id FROM t), b AS (SELECT id FROM u) "
                        + "SELECT id FROM a UNION ALL SELECT id FROM b");

        assertTrue(out.contains("),\n"), "CTE 列表应在同一层换行：\n" + out);
        assertTrue(out.contains("b AS ("), "第二个 CTE 名称不能被吞掉：\n" + out);
        assertTrue(Pattern.compile("(?m)^UNION ALL$").matcher(out).find()
                        || out.contains("\nUNION ALL\n"),
                "集合运算符应成为独立边界：\n" + out);
    }

    @Test
    void postgresDollarQuotedBodiesRemainOpaque() {
        String sql = "SELECT $$BEGIN; SELECT 'FROM'; END;$$ AS body, payload->>'name' AS name "
                + "FROM events WHERE id = :id AND payload::jsonb IS NOT NULL";
        String out = SqlFormatter.format(sql);

        assertTrue(out.contains("$$BEGIN; SELECT 'FROM'; END;$$"),
                "美元引用正文不能被关键字或分号改写：\n" + out);
        assertTrue(out.contains("payload ->> 'name'"), "JSON 运算符应保持可读间距：\n" + out);
        assertTrue(out.contains("payload::jsonb"), "类型转换运算符不能被拆开：\n" + out);
        assertEquals(out, SqlFormatter.format(out), "复杂 SQL 美化应保持幂等：\n" + out);
    }

    @Test
    void oracleQQuotedLiteralRemainsOpaque() {
        String sql = "SELECT q'[A; FROM WHERE]' AS text_value FROM dual WHERE id = :id";
        String out = SqlFormatter.format(sql);

        assertTrue(out.contains("q'[A; FROM WHERE]'"), "Oracle q 引用正文不能被拆分：\n" + out);
        assertTrue(out.contains("id = :id"), "Oracle 绑定变量不能被拆成两个 token：\n" + out);
        assertEquals(out, SqlFormatter.format(out), "Oracle q 引用格式化应幂等：\n" + out);
    }
}
