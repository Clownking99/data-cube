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
}
