package com.datacube.sqleditor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlExecutionSourceTest {
    @Test void mapsSelectionRepeatedSqlAndUnicodeCharactersWithoutSearchingLiveText() {
        String sql = "skip;\r\n  select '😀', broken; select '😀', broken; trailing";
        var range = new SqlExecutionRange(sql.indexOf("  select"), sql.indexOf(" trailing"), true);
        var source = SqlExecutionSource.capture(sql, 42, range, false);
        String statement = "select '😀', broken";
        int position = statement.codePointCount(0, statement.indexOf("broken")) + 1;
        assertEquals(sql.indexOf("broken"), source.locate(1, position, 42).offset());
        assertEquals(sql.lastIndexOf("broken"), source.locate(2, position, 42).offset());
        assertFalse(source.locate(1, position, 43).available());
        assertTrue(source.locate(1, position, 43).message().contains("过期"));
        assertFalse(source.locate(1, 999, 42).available());
        assertFalse(source.locate(1, 0, 42).available());
    }
    @Test void sourceBudgetDoesNotInventAMapping() {
        String sql = " ".repeat(SqlExecutionSource.MAX_SOURCE_UNITS) + "select 1";
        var source = SqlExecutionSource.capture(sql, 1, new SqlExecutionRange(0, sql.length(), false), false);
        assertFalse(source.locate(1, 1, 1).available());
    }
}
