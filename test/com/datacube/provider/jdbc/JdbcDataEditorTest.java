package com.datacube.provider.jdbc;

import com.datacube.spi.model.RowKey;
import com.datacube.spi.model.TableRef;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcDataEditor} 纯函数单测：SQL 拼接（含 IS NULL / 无主键全列 WHERE / 标识符引用）
 * 与文本→类型强转。不依赖真实数据库连接。
 */
class JdbcDataEditorTest {

    /** 双引号引用（模拟 PG 方言）。 */
    private static final UnaryOperator<String> Q = s -> "\"" + s + "\"";

    // ---------- qualify ----------

    @Test
    void qualifyWithoutSchema() {
        assertEquals("\"emp\"", JdbcDataEditor.qualify(new TableRef(null, "emp"), Q));
        assertEquals("\"emp\"", JdbcDataEditor.qualify(new TableRef("", "emp"), Q));
    }

    @Test
    void qualifyWithSchema() {
        assertEquals("\"hr\".\"emp\"", JdbcDataEditor.qualify(new TableRef("hr", "emp"), Q));
    }

    // ---------- INSERT ----------

    @Test
    void buildInsert() {
        String sql = JdbcDataEditor.buildInsertSql("\"emp\"", List.of("id", "name"), Q);
        assertEquals("INSERT INTO \"emp\" (\"id\", \"name\") VALUES (?, ?)", sql);
    }

    // ---------- UPDATE ----------

    @Test
    void buildUpdateWithPkWhere() {
        RowKey key = new RowKey(List.of("id"), Arrays.asList(7));
        String sql = JdbcDataEditor.buildUpdateSql("\"emp\"", List.of("name", "age"), key, Q);
        assertEquals("UPDATE \"emp\" SET \"name\" = ?, \"age\" = ? WHERE \"id\" = ?", sql);
    }

    @Test
    void buildUpdateWithNullInWhereUsesIsNull() {
        // 无主键全列匹配：含 NULL 旧值的列用 IS NULL（不占位）
        RowKey key = new RowKey(Arrays.asList("a", "b"), Arrays.asList("x", null));
        String sql = JdbcDataEditor.buildUpdateSql("\"t\"", List.of("c"), key, Q);
        assertEquals("UPDATE \"t\" SET \"c\" = ? WHERE \"a\" = ? AND \"b\" IS NULL", sql);
    }

    // ---------- DELETE ----------

    @Test
    void buildDeleteAllColumnMatch() {
        RowKey key = new RowKey(Arrays.asList("a", "b"), Arrays.asList(1, null));
        String sql = JdbcDataEditor.buildDeleteSql("\"t\"", key, Q);
        assertEquals("DELETE FROM \"t\" WHERE \"a\" = ? AND \"b\" IS NULL", sql);
    }

    // ---------- coerce ----------

    @Test
    void coerceNullTextIsSqlNull() {
        assertNull(JdbcDataEditor.coerce(null, Types.VARCHAR));
    }

    @Test
    void coerceNumeric() {
        assertEquals(new BigDecimal("123"), JdbcDataEditor.coerce("123", Types.INTEGER));
        assertEquals(new BigDecimal("1.5"), JdbcDataEditor.coerce("1.5", Types.DECIMAL));
    }

    @Test
    void coerceBlankNumericIsNull() {
        assertNull(JdbcDataEditor.coerce("  ", Types.BIGINT));
    }

    @Test
    void coerceBoolean() {
        assertEquals(Boolean.TRUE, JdbcDataEditor.coerce("true", Types.BOOLEAN));
        assertEquals(Boolean.TRUE, JdbcDataEditor.coerce("1", Types.BOOLEAN));
        assertEquals(Boolean.FALSE, JdbcDataEditor.coerce("f", Types.BIT));
    }

    @Test
    void coerceCharKeepsEmptyString() {
        assertEquals("", JdbcDataEditor.coerce("", Types.VARCHAR));
        assertEquals("abc", JdbcDataEditor.coerce("abc", Types.VARCHAR));
    }

    @Test
    void coerceDate() {
        assertEquals(java.sql.Date.valueOf("2024-01-02"),
                JdbcDataEditor.coerce("2024-01-02", Types.DATE));
    }

    @Test
    void coerceTimestampDateOnlyGetsMidnight() {
        assertEquals(java.sql.Timestamp.valueOf("2024-01-02 00:00:00"),
                JdbcDataEditor.coerce("2024-01-02", Types.TIMESTAMP));
    }

    @Test
    void coerceInvalidNumberThrows() {
        assertThrows(RuntimeException.class, () -> JdbcDataEditor.coerce("abc", Types.INTEGER));
    }

    @Test
    void coerceInvalidBooleanThrows() {
        assertThrows(RuntimeException.class, () -> JdbcDataEditor.coerce("maybe", Types.BOOLEAN));
    }

    @Test
    void coerceUnknownTypePassesThroughText() {
        // 例如 PG uuid/json：交由驱动强转，纯函数原样返回文本
        assertEquals("11111111-1111-1111-1111-111111111111",
                JdbcDataEditor.coerce("11111111-1111-1111-1111-111111111111", Types.OTHER));
    }

    // ---------- whereClause ----------

    @Test
    void whereClauseMixedNullAndValue() {
        RowKey key = new RowKey(Arrays.asList("a", "b", "c"), Arrays.asList(1, null, "z"));
        String w = JdbcDataEditor.whereClause(key, Q);
        assertTrue(w.startsWith(" WHERE "));
        assertEquals(" WHERE \"a\" = ? AND \"b\" IS NULL AND \"c\" = ?", w);
    }
}
