package com.datacube.sqleditor;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link InsertSqlGenerator} 纯文本单测：单表来源解析 + INSERT 生成，
 * 不依赖数据库。
 */
class InsertSqlGeneratorTest {

    // ---------- singleTableName：可解析 ----------

    @Test
    void plainSingleTable() {
        assertEquals("users", InsertSqlGenerator.singleTableName("SELECT * FROM users"));
        assertEquals("users", InsertSqlGenerator.singleTableName("select id, name from users where id = 1"));
    }

    @Test
    void schemaQualifiedAndQuoted() {
        assertEquals("hr.emp", InsertSqlGenerator.singleTableName("SELECT * FROM hr.emp ORDER BY id"));
        assertEquals("\"HR\".\"EMP\"", InsertSqlGenerator.singleTableName("SELECT * FROM \"HR\".\"EMP\""));
    }

    @Test
    void aliasAndCommentsTolerated() {
        assertEquals("t_order", InsertSqlGenerator.singleTableName(
                "-- 导出\nSELECT o.id /* 主键 */ FROM t_order o WHERE o.id IN ('1','2')"));
        assertEquals("t_order", InsertSqlGenerator.singleTableName("SELECT * FROM t_order AS o"));
    }

    @Test
    void subqueryInWhereStillSingleTable() {
        assertEquals("a", InsertSqlGenerator.singleTableName(
                "SELECT * FROM a WHERE id IN (SELECT aid FROM b)"));
    }

    // ---------- singleTableName：不可解析 ----------

    @Test
    void joinAndMultiTableRejected() {
        assertNull(InsertSqlGenerator.singleTableName("SELECT * FROM a JOIN b ON a.id = b.id"));
        assertNull(InsertSqlGenerator.singleTableName("SELECT * FROM a, b WHERE a.id = b.id"));
        assertNull(InsertSqlGenerator.singleTableName("SELECT * FROM a LEFT JOIN b ON a.id = b.id"));
    }

    @Test
    void subqueryUnionWithNonSelectRejected() {
        assertNull(InsertSqlGenerator.singleTableName("SELECT * FROM (SELECT * FROM a) t"));
        assertNull(InsertSqlGenerator.singleTableName("SELECT * FROM a UNION SELECT * FROM b"));
        assertNull(InsertSqlGenerator.singleTableName("WITH x AS (SELECT 1) SELECT * FROM x"));
        assertNull(InsertSqlGenerator.singleTableName("UPDATE a SET x = 1"));
        assertNull(InsertSqlGenerator.singleTableName(null));
        assertNull(InsertSqlGenerator.singleTableName("   "));
    }

    // ---------- generate ----------

    @Test
    void generateBasicRows() {
        List<List<Object>> rows = List.of(
                Arrays.asList(1, "Tom"),
                Arrays.asList(2, null));
        String s = InsertSqlGenerator.generate("users", List.of("ID", "NAME"), rows);
        assertEquals("INSERT INTO users (ID, NAME) VALUES (1, 'Tom');\n"
                + "INSERT INTO users (ID, NAME) VALUES (2, NULL);\n", s);
    }

    @Test
    void quoteEscapingAndTypes() {
        List<List<Object>> rows = List.of(
                Arrays.asList("O'Brien", 3.14, true, "2026-07-08 12:00:00"));
        String s = InsertSqlGenerator.generate("t", List.of("A", "B", "C", "D"), rows);
        assertEquals("INSERT INTO t (A, B, C, D) VALUES ('O''Brien', 3.14, TRUE, '2026-07-08 12:00:00');\n", s);
    }

    @Test
    void irregularColumnNameQuoted() {
        String s = InsertSqlGenerator.generate("t", List.of("user id", "普通"), List.of(Arrays.asList(1, 2)));
        assertEquals("INSERT INTO t (\"user id\", 普通) VALUES (1, 2);\n", s);
    }

    @Test
    void shortRowPaddedWithNull() {
        String s = InsertSqlGenerator.generate("t", List.of("A", "B"), List.of(List.of(1)));
        assertEquals("INSERT INTO t (A, B) VALUES (1, NULL);\n", s);
    }
}
