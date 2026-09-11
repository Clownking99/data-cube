package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ResultCellPreviewTest {
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"NULL", "中😀\n  第二行\t", "<script>alert('inert')</script>"})
    void distinguishesNullEmptyAndLiteralContentWithoutChangingText(String value) {
        var preview = ResultCellPreview.capture(result(value), 0, 0, 2);
        assertEquals(value == null ? "" : value, preview.text());
        assertEquals(value == null, preview.nullValue());
        assertEquals("".equals(value), preview.emptyString());
        assertEquals(value == null ? 0 : value.length(), preview.displayLength());
        assertFalse(preview.truncated()); assertFalse(preview.displayOnly());
        assertEquals(3, preview.displayRow()); assertEquals(1, preview.sourceRow());
        assertEquals(1, preview.column()); assertEquals("value", preview.label());
        assertEquals("VARCHAR", preview.type()); assertEquals(Types.VARCHAR, preview.jdbcType());
    }

    @ParameterizedTest @ValueSource(ints = {65_535, 65_536, 65_537})
    void boundsDisplayWithoutClaimingLongContentIsComplete(int size) {
        var preview = ResultCellPreview.capture(result("x".repeat(size)), 0, 0, 0);
        assertEquals(Math.min(size, 65_536), preview.text().length());
        assertEquals(size, preview.displayLength());
        assertEquals(size > 65_536, preview.truncated());
    }

    @Test void truncationDoesNotSplitSurrogatePairsAndBoundsMetadata() {
        var columns = List.of(new ResultColumn(0, "中".repeat(511) + "😀尾", Types.VARCHAR, "T".repeat(513)));
        var result = QueryResult.queryWithMetadata(columns, List.of(List.of("x".repeat(65_535) + "😀尾")), 0, false);
        var preview = ResultCellPreview.capture(result, 0, 0, 0);
        assertEquals("x".repeat(65_535), preview.text());
        assertEquals(65_538, preview.displayLength()); assertTrue(preview.truncated());
        assertEquals("中".repeat(511) + "…", preview.label());
        assertEquals("T".repeat(512) + "…", preview.type()); assertTrue(preview.metadataTruncated());
    }

    @Test void specialValuesRemainDisplayOnlyAndSnapshotDoesNotRetainMutableBytes() {
        byte[] bytes = {1, 2, 15};
        QueryResult result = QueryResult.queryWithMetadata(List.of(new ResultColumn(0, "bin", Types.BINARY, "BINARY")),
                List.of(List.of(bytes)), 0, false);
        var preview = ResultCellPreview.capture(result, 0, 0, 0);
        assertEquals(ResultValueFormatter.format(result.rows.getFirst().getFirst()), preview.text());
        assertTrue(preview.text().contains("01020f")); assertTrue(preview.displayOnly());
        bytes[0] = 9;
        assertTrue(preview.text().contains("01020f"));
        assertFalse(preview.toString().contains("01020f"), "diagnostic summary must not disclose the value");
    }

    @Test void duplicateLabelsUseColumnPositionAndMalformedRowsAreNotNullValues() {
        QueryResult result = QueryResult.query(List.of("same", "same"), List.of(List.of("one", "two")), 0);
        assertEquals("two", ResultCellPreview.capture(result, 0, 1, 0).text());
        assertEquals(2, ResultCellPreview.capture(result, 0, 1, 0).column());
        var shortRow = QueryResult.query(List.of("a", "b"), List.of(List.of("one")), 0);
        assertThrows(IllegalArgumentException.class, () -> ResultCellPreview.capture(shortRow, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> ResultCellPreview.capture(result, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ResultCellPreview.capture(result, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ResultCellPreview.capture(result, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> ResultCellPreview.capture(QueryResult.update(0, 1), 0, 0, 0));
    }

    private static QueryResult result(Object value) {
        return QueryResult.queryWithMetadata(List.of(new ResultColumn(0, "value", Types.VARCHAR, "VARCHAR")),
                List.of(Arrays.asList(value)), 0, false);
    }
}
