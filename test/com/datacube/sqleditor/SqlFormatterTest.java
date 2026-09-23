package com.datacube.sqleditor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlFormatter} 纯函数单测：多层嵌套子查询的换行缩进、普通括号保持行内、
 * 幂等性与语义保全（去空白去大小写后 token 序列一致）。不依赖数据库。
 */
class SqlFormatterTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "1e-3", "1E+03", ".5", ".5e-2", "1.e+2", "1.25E-10",
            "0", "42", "1.", "001.2300", "9.99e9999",
            "25f", "0.5D", ".25F", "1e-3d",
            "1_000.25_50e-1_0", "0xFF_FF", "0X_1e", "0o_755", "0B10_01"
    })
    void numericLiteralsStayWholeAndFollowingSqlIsFormatted(String number) {
        String expected = "SELECT " + number + " AS amount,\n       id\n  FROM t"
                + "\n WHERE value < " + number + ";\n\nSELECT 2\n  FROM t";
        assertEquals(expected, SqlFormatter.format("select " + number
                + " as amount,id from t where value<" + number + ";select 2 from t"));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @Test
    void exponentSignsDoNotConsumeAdjacentArithmeticOperators() {
        String expected = "SELECT 1e-3 - 2e+4 + .5 * 3. AS amount\n  FROM t"
                + "\n WHERE value BETWEEN 1E-5 AND 1E+5";
        assertEquals(expected, SqlFormatter.format(
                "select 1e-3-2e+4+.5*3. as amount from t where value between 1E-5 and 1E+5"));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @Test
    void numericTokensComposeWithFunctionsCastsAndSubqueries() {
        String expected = "SELECT COALESCE(.5, 1e-3)::numeric AS amount,\n"
                + "       (\n        SELECT MAX(price)\n          FROM t2"
                + "\n         WHERE price > 2.5E+3\n      ) AS peak\n  FROM t";
        assertEquals(expected, SqlFormatter.format("select coalesce(.5,1e-3)::numeric as amount,"
                + "(select max(price) from t2 where price>2.5E+3) as peak from t"));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @Test
    void numericLookingIdentifiersParametersAndQuotedTextStayUnchanged() {
        String expected = "SELECT t.e3,\n       t.f,\n       col1,\n       :e3,\n       $1,\n"
                + "       '1e-3',\n       \"1.5E+3\",\n       q'[.5e-2]'"
                + "\n  FROM schema1.table2 t\n WHERE t.d = :1";
        assertEquals(expected, SqlFormatter.format(
                "select t.e3,t.f,col1,:e3,$1,'1e-3',\"1.5E+3\",q'[.5e-2]'"
                        + " from schema1.table2 t where t.d=:1"));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @Test
    void leadingSignsAndNumericLookingNamesRemainSeparateOperators() {
        String expected = "SELECT - 1e-3 + .5 AS delta,\n       1 - e3,\n       1 + f,\n"
                + "       0x1e - 3\n  FROM t";
        assertEquals(expected, SqlFormatter.format(
                "select -1e-3+.5 as delta,1-e3,1+f,0x1e-3 from t"));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @Test
    void longGroupedNumberRetainsEveryDigitWithoutNumericConversion() {
        String number = "123_".repeat(4096) + "456.000E-9999";
        String expected = "SELECT " + number + " AS amount\n  FROM t";
        assertEquals(expected, SqlFormatter.format("select " + number + " as amount from t"));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1e", "1e+", ".5e-", "0x", "0b2", "1__0", "1_", "1._0",
            "1e_2", "0x_", "1e2foo"
    })
    void incompleteOrAmbiguousNumericTokenLeavesWholeInputUntouched(String number) {
        String sql = "  select id,name from t;\r\nselect " + number + "\r\n  ";
        assertEquals(sql, SqlFormatter.format(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "q'[It's; -- select\nfrom]'",
            "Q'{It's; /* select */ from}'",
            "q'(It's; select from)'",
            "q'<It's; select from>'",
            "q'!It's; select from!'",
            "q'''quoted; from''",
            "nq'[中文 It's; from]'",
            "NQ'!中文 It's; from!'",
            "q'[]'"
    })
    void oracleQuotedTokensDoNotSwallowFollowingSql(String literal) {
        assertOpaqueTokenAndFollowingSql(literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "E'it\\'s; -- select\\nfrom'",
            "e'backslash\\\\''quote; from'",
            "N'中文''; from'",
            "n'中文'",
            "B'101010'",
            "x'AB12'",
            "U&'d\\0061t; from'",
            "u&\"d\\0061t\"",
            "E''"
    })
    void literalPrefixesAndEscapesStayAttached(String literal) {
        assertOpaqueTokenAndFollowingSql(literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "'It''s; -- from'", "\"mixed\"\"Case\"", "\u0060from\u0060\u0060value\u0060",
            "$body$'inner'; -- from\nwhere$body$", "$$begin; select 1; end;$$",
            "''", "'C:\\'"
    })
    void existingQuotedFormsRetainExactContents(String literal) {
        assertOpaqueTokenAndFollowingSql(literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "'first'\n  'second'",
            "E'first\\n'\r\n  'it\\'s; -- content'",
            "B'10'\r'01'",
            "E'first' -- comment with 'quote'\n 'it\\'s; -- content'",
            "'first'\r\n -- second line\r\n 'second'"
    })
    void continuedStringsRetainRequiredLineBreakAndEscapeMode(String literal) {
        assertOpaqueTokenAndFollowingSql(literal);
    }

    private static void assertOpaqueTokenAndFollowingSql(String literal) {
        String sql = "select " + literal + " as txt,id from t where id=:id;select 2 as n from t";
        String expected = "SELECT " + literal + " AS txt,\n       id\n  FROM t"
                + "\n WHERE id = :id;\n\nSELECT 2 AS n\n  FROM t";
        assertEquals(expected, SqlFormatter.format(sql), "引用内容不变，引用之后应继续格式化");
        assertEquals(expected, SqlFormatter.format(expected), "二次美化应保持相同输出");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void lineCommentEndsAtEveryPhysicalLineSeparator(String separator) {
        String expected = "SELECT id -- keep this comment\n  FROM t\n WHERE id = 1";
        assertEquals(expected, SqlFormatter.format("select id -- keep this comment" + separator
                + "from t where id=1"));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @Test
    void nestedBlockCommentsRemainOneOpaqueToken() {
        String comment = "/* outer /* inner */ select 'keep'; -- text\n outer end */";
        String expected = "SELECT " + comment + " id\n  FROM t";
        assertEquals(expected, SqlFormatter.format("select " + comment + " id from t"));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "'", "\"", "\u0060", "q'[unfinished", "nq'{unfinished", "E'escaped\\'",
            "$tag$unfinished", "/* unfinished", "/* outer /* inner */",
            "q'", "U&'unfinished", "u&\"unfinished", "$TAG$content$tag$",
            "'first'\n'unfinished", "E'first' -- continuation\n'escaped\\'"
    })
    void unfinishedQuotedTextOrCommentLeavesWholeInputUntouched(String unfinished) {
        String sql = "  select id, name from t;\r\nselect " + unfinished + "\r\n  ";
        assertEquals(sql, SqlFormatter.format(sql), "不完整引号/注释应让整次美化保留原文，包括边界空白");
    }

    @Test
    void dollarParametersAndIdentifierPrefixesAreNotQuotedLiterals() {
        String expected = "SELECT $1,\n       price$usd,\n       nq_name,\n       e_value\n  FROM t";
        assertEquals(expected, SqlFormatter.format("select $1,price$usd,nq_name,e_value from t"));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @Test
    void trailingLineCommentDoesNotRequireAClosingDelimiter() {
        assertEquals("SELECT 'value' -- unfinished 'quote", SqlFormatter.format(
                "select 'value' -- unfinished 'quote"));
    }

    @Test
    void scalarSubqueryInsideFunctionRestoresOuterLayout() {
        String sql = "select coalesce((select max(amount) from sales where active=true),0) as total, "
                + "id from customers order by id,name";
        String expected = """
                SELECT COALESCE((
                        SELECT MAX(amount)
                          FROM sales
                         WHERE active = TRUE
                      ), 0) AS total,
                       id
                  FROM customers
                 ORDER BY id,
                       name""";
        assertEquals(expected, SqlFormatter.format(sql));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @Test
    void windowInsideFunctionRestoresArgumentsAndSelectList() {
        String sql = "select coalesce(sum(amount) over (partition by coalesce(region,'?'),team "
                + "order by created_at,id rows between 2 preceding and current row),0) as total, "
                + "region from sales";
        String expected = """
                SELECT COALESCE(SUM(amount) OVER (
                       PARTITION BY COALESCE(region, '?'),
                       team
                       ORDER BY created_at,
                       id
                       ROWS BETWEEN 2 PRECEDING AND CURRENT ROW
                      ), 0) AS total,
                       region
                  FROM sales""";
        assertEquals(expected, SqlFormatter.format(sql));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @Test
    void nestedWindowFunctionsKeepTheirOwnKeywordsInline() {
        String sql = "select sum(amount) over (order by extract(day from created_at),id) as total from sales";
        String expected = """
                SELECT SUM(amount) OVER (
                       ORDER BY extract(day FROM created_at),
                       id
                      ) AS total
                  FROM sales""";
        assertEquals(expected, SqlFormatter.format(sql));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "rows between 2 preceding and current row exclude ties",
            "range between unbounded preceding and current row exclude current row",
            "groups between 1 preceding and 1 following exclude no others",
            "rows unbounded preceding exclude group"
    })
    void windowFrameBoundariesHaveTheirOwnLines(String frame) {
        String expected = "SELECT SUM(amount) OVER (\n"
                + "       ORDER BY created_at\n       "
                + frame.toUpperCase(Locale.ROOT).replace(" EXCLUDE", "\n       EXCLUDE")
                + "\n      ) AS total\n  FROM sales";
        assertEquals(expected, SqlFormatter.format(
                "select sum(amount) over (order by created_at " + frame + ") as total from sales"));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @Test
    void nestedSetOperatorsStayInsideTheirQueryBlock() {
        String expected = """
                SELECT *
                  FROM (
                        SELECT id
                          FROM a
                        UNION ALL
                        SELECT id
                          FROM b
                      ) s""";
        assertEquals(expected, SqlFormatter.format("select * from (select id from a union all select id from b) s"));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @Test
    void subqueryRestoresWindowListAndEnclosingFunction() {
        String sql = "select coalesce(sum(amount) over (order by "
                + "(select max(priority) from config),id),0) as total, region from sales";
        String expected = """
                SELECT COALESCE(SUM(amount) OVER (
                       ORDER BY (
                        SELECT MAX(priority)
                          FROM config
                      ),
                       id
                      ), 0) AS total,
                       region
                  FROM sales""";
        assertEquals(expected, SqlFormatter.format(sql));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @Test
    void subqueryInsideGroupedPredicateRestoresOuterBooleanClauses() {
        String sql = "select id from t where (id in (select id from u where active=true) "
                + "or score between 1 and 3) and enabled=true";
        String expected = """
                SELECT id
                  FROM t
                 WHERE (id IN (
                        SELECT id
                          FROM u
                         WHERE active = TRUE
                      ) OR score BETWEEN 1 AND 3)
                   AND enabled = TRUE""";
        assertEquals(expected, SqlFormatter.format(sql));
        assertEquals(expected, SqlFormatter.format(expected));
    }

    @Test
    void mergeContextEndsAtStatementBoundary() {
        String merge = "merge into target t using source s on (t.id=s.id) "
                + "when matched then update set t.amount=s.amount";
        String query = "select * from a join b using (id)";
        assertEquals(SqlFormatter.format(merge) + ";\n\n" + SqlFormatter.format(query),
                SqlFormatter.format(merge + ";" + query),
                "MERGE 的 USING 排版状态不能影响下一条 JOIN USING");
    }

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

    @Test
    void lineCommentsKeepFollowingSqlOnANewLine() {
        String out = SqlFormatter.format("SELECT a -- inline note\nFROM t WHERE b=1");

        assertTrue(out.contains("a -- inline note\n"), "行注释应保留行尾边界：\n" + out);
        assertFalse(out.contains("-- inline note FROM"), "后续 SQL 不能被行注释吞掉：\n" + out);
        assertTrue(out.indexOf("-- inline note") < out.indexOf("FROM t"),
                "FROM 应位于注释之后：\n" + out);
        assertEquals(out, SqlFormatter.format(out), "行注释安全修复仍应幂等：\n" + out);
    }

    @Test
    void commonPagingAndFilterKeywordsAreFormatted() {
        String out = SqlFormatter.format(
                "SELECT COUNT(*) FILTER(WHERE active=true) FROM t "
                        + "ORDER BY created_at DESC NULLS LAST FETCH FIRST 10 ROWS ONLY");

        assertTrue(out.contains("FILTER (WHERE active = TRUE)"),
                "FILTER 谓词前应有可读空格：\n" + out);
        assertTrue(out.contains("NULLS LAST"), "NULLS LAST 应识别为关键字短语：\n" + out);
        assertTrue(out.contains("FETCH FIRST 10 ROWS ONLY"),
                "FETCH 分页短语不能被拆散：\n" + out);
    }

    @Test
    void ddlColumnListsKeepReadableSpacing() {
        String out = SqlFormatter.format("CREATE TABLE t(id NUMBER, name VARCHAR2(20))");

        assertTrue(out.contains("CREATE TABLE t ("),
                "DDL 对象名后的列列表应保留空格：\n" + out);
        assertTrue(out.contains("id NUMBER, name VARCHAR2(20)"),
                "DDL 列定义应保持行内可读：\n" + out);
    }

    @Test
    void mergeActionsUseVisibleClauseBoundaries() {
        String out = SqlFormatter.format(
                "MERGE INTO target t USING (SELECT id,value FROM source WHERE flag=1) s "
                        + "ON (t.id=s.id) WHEN MATCHED THEN UPDATE SET t.value=s.value "
                        + "WHEN NOT MATCHED THEN INSERT (id,value) VALUES (s.id,s.value)");

        assertTrue(out.contains("MERGE INTO target t"), "MERGE 目标应保持同一逻辑行：\n" + out);
        assertTrue(out.contains("USING ("), "USING 源查询应成为独立子句：\n" + out);
        assertTrue(out.contains("ON (t.id = s.id)"), "匹配条件应成为独立子句：\n" + out);
        assertTrue(out.contains("WHEN MATCHED THEN"), "匹配动作应可快速定位：\n" + out);
        assertTrue(out.contains("WHEN NOT MATCHED THEN"), "未匹配动作应可快速定位：\n" + out);
        assertTrue(out.contains("INSERT (id, value)"), "动作列列表应保留可读间距：\n" + out);
        assertTrue(out.contains("VALUES (s.id, s.value)"), "动作值列表应保留可读间距：\n" + out);
        assertEquals(out, SqlFormatter.format(out), "MERGE 美化应幂等：\n" + out);
    }

    @Test
    void oracleHierarchyClausesRemainReadable() {
        String out = SqlFormatter.format(
                "SELECT employee_id, manager_id, name FROM employees "
                        + "START WITH manager_id IS NULL CONNECT BY NOCYCLE "
                        + "PRIOR employee_id = manager_id ORDER SIBLINGS BY name");

        assertTrue(out.contains("START WITH manager_id IS NULL"),
                "START WITH 应保持为一个层级查询短语：\n" + out);
        assertTrue(out.contains("CONNECT BY NOCYCLE PRIOR employee_id = manager_id"),
                "CONNECT BY 条件应保持可扫描：\n" + out);
        assertTrue(out.contains("ORDER SIBLINGS BY name"),
                "ORDER SIBLINGS BY 应保持为一个排序短语：\n" + out);
        assertEquals(out, SqlFormatter.format(out), "层级查询美化应幂等：\n" + out);
    }

    @Test
    void oracleDdlActionsAreUppercasedAndSpaced() {
        String out = SqlFormatter.format(
                "ALTER TABLE orders ADD CONSTRAINT fk_customer FOREIGN KEY(customer_id) "
                        + "REFERENCES customers(id) ON DELETE CASCADE");

        assertTrue(out.contains("FOREIGN KEY (customer_id)"),
                "外键列列表前应保留可读空格：\n" + out);
        assertTrue(out.contains("REFERENCES customers (id)"),
                "REFERENCES 对象列列表前应保留可读空格：\n" + out);
        assertTrue(out.contains("ON DELETE CASCADE"),
                "级联动作不能被拆成多行或丢失大小写：\n" + out);
    }

    @Test
    void topLevelAndWindowListsBreakWithoutSplittingFunctionArguments() {
        String out = SqlFormatter.format(
                "SELECT region, status, sum(amount) OVER (PARTITION BY region,status "
                        + "ORDER BY created_at,id) AS total FROM sales "
                        + "GROUP BY region,status,channel ORDER BY region ASC,status DESC");
        String returning = SqlFormatter.format("UPDATE sales SET status='X' WHERE id=1 RETURNING id,created_at");

        assertTrue(out.contains("PARTITION BY region,\n"),
                "窗口分区列表应逐项换行：\n" + out);
        assertTrue(out.contains("ORDER BY created_at,\n"),
                "窗口排序列表应逐项换行：\n" + out);
        assertTrue(out.contains("GROUP BY region,\n"),
                "GROUP BY 列表应逐项换行：\n" + out);
        assertTrue(out.contains("ORDER BY region ASC,\n"),
                "ORDER BY 列表应逐项换行：\n" + out);
        assertTrue(out.contains("SUM(amount)"),
                "聚合函数参数不能被列表换行规则拆开：\n" + out);
        assertTrue(returning.contains("RETURNING id,\n"),
                "RETURNING 列表应逐项换行：\n" + returning);
        assertEquals(out, SqlFormatter.format(out), "列表换行格式化应幂等：\n" + out);
        assertEquals(returning, SqlFormatter.format(returning), "RETURNING 列表格式化应幂等：\n" + returning);
    }
}
