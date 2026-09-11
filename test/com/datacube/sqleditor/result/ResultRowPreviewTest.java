package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ResultRowPreviewTest {
    @Test void capturesOnlyRequestedColumnsInVisibleOrderWithOriginalIdentity() {
        var result = QueryResult.query(List.of("same", "private", "same"),
                List.of(List.of("skip", "hidden", "skip"), List.of("left", "secret", "right")), 0);
        var visible = new ArrayList<>(List.of(2, 0));
        var snapshot = ResultRowPreview.capture(result, 1, visible, 0);
        visible.clear();
        assertEquals(1, snapshot.displayRow()); assertEquals(2, snapshot.sourceRow());
        assertEquals(2, snapshot.visibleColumnCount()); assertFalse(snapshot.columnsTruncated());
        assertEquals(List.of(3, 1), snapshot.fields().stream().map(ResultCellPreview::column).toList());
        assertEquals(List.of("right", "left"), snapshot.fields().stream().map(ResultCellPreview::text).toList());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.fields().clear());
        assertFalse(snapshot.toString().contains("secret")); assertFalse(snapshot.toString().contains("right"));
    }

    @ParameterizedTest @ValueSource(ints = {4095, 4096, 4097})
    void rowLimitsDoNotReduceExistingCellViewerLimit(int size) {
        var result = QueryResult.query(List.of("text"), List.of(List.of("x".repeat(size))), 0);
        var cell = ResultRowPreview.capture(result, 0, List.of(0), 0).fields().getFirst();
        assertEquals(Math.min(size, 4096), cell.text().length()); assertEquals(size, cell.displayLength());
        assertEquals(size > 4096, cell.truncated());
        assertEquals(size, ResultCellPreview.capture(result, 0, 0, 0).text().length());
    }

    @ParameterizedTest @ValueSource(ints = {1, 199, 200, 201})
    void fieldLimitIsExplicitAndTextBudgetIsBounded(int count) {
        var columns = java.util.stream.IntStream.range(0, count).mapToObj(i -> "col-" + i).toList();
        var values = java.util.stream.IntStream.range(0, count).mapToObj(i -> (Object) "x".repeat(4097)).toList();
        var indexes = java.util.stream.IntStream.range(0, count).boxed().toList();
        var snapshot = ResultRowPreview.capture(QueryResult.query(columns, List.of(values), 0), 0, indexes, 0);
        assertEquals(Math.min(count, 200), snapshot.fields().size()); assertEquals(count, snapshot.visibleColumnCount());
        assertEquals(count > 200, snapshot.columnsTruncated());
        assertEquals(Math.min(count, 200) * 4096, snapshot.fields().stream().mapToInt(f -> f.text().length()).sum());
        assertEquals(Math.min(count, 200), snapshot.fields().getLast().column());
    }

    @Test void preservesNullEmptyUnicodeAndSpecialValueSemantics() {
        var columns = List.of(new ResultColumn(0, "n", Types.VARCHAR, "VARCHAR"), new ResultColumn(1, "e", Types.VARCHAR, "VARCHAR"),
                new ResultColumn(2, "literal", Types.VARCHAR, "VARCHAR"), new ResultColumn(3, "long".repeat(180), Types.VARCHAR, "T".repeat(513)),
                new ResultColumn(4, "binary", Types.BINARY, "BINARY"));
        byte[] bytes = {1, 2, 15};
        var result = QueryResult.queryWithMetadata(columns, List.of(Arrays.asList(null, "", "NULL", "x".repeat(4095) + "😀尾", bytes)), 0, false);
        var snapshot = ResultRowPreview.capture(result, 0, List.of(0, 1, 2, 3, 4), 0);
        var fields = snapshot.fields(); bytes[0] = 9;
        assertTrue(fields.get(0).nullValue()); assertFalse(fields.get(0).emptyString()); assertEquals("", fields.get(0).text());
        assertTrue(fields.get(1).emptyString()); assertFalse(fields.get(1).nullValue());
        assertEquals("NULL", fields.get(2).text()); assertFalse(fields.get(2).nullValue());
        assertEquals("x".repeat(4095), fields.get(3).text()); assertTrue(fields.get(3).truncated());
        assertEquals(4098, fields.get(3).displayLength()); assertTrue(fields.get(3).metadataTruncated());
        assertEquals(513, fields.get(3).label().length()); assertEquals(513, fields.get(3).type().length());
        assertTrue(fields.get(4).displayOnly()); assertTrue(fields.get(4).text().contains("01020f"));
    }

    @ParameterizedTest @ValueSource(strings = {"null", "update", "row-low", "row-high", "display-low", "no-fields", "null-fields", "negative-field", "out-field", "duplicate", "short-row"})
    void invalidOrAmbiguousProjectionNeverProducesPartialOrGuessedData(String state) {
        var result = QueryResult.query(List.of("a", "b"), List.of(List.of("A", "B")), 0);
        switch (state) {
            case "null" -> assertThrows(IllegalArgumentException.class, () -> ResultRowPreview.capture(null, 0, List.of(0), 0));
            case "update" -> assertThrows(IllegalArgumentException.class, () -> ResultRowPreview.capture(QueryResult.update(0, 1), 0, List.of(0), 0));
            case "row-low" -> assertThrows(IllegalArgumentException.class, () -> ResultRowPreview.capture(result, -1, List.of(0), 0));
            case "row-high" -> assertThrows(IllegalArgumentException.class, () -> ResultRowPreview.capture(result, 1, List.of(0), 0));
            case "display-low" -> assertThrows(IllegalArgumentException.class, () -> ResultRowPreview.capture(result, 0, List.of(0), -1));
            case "no-fields" -> assertThrows(IllegalArgumentException.class, () -> ResultRowPreview.capture(result, 0, List.of(), 0));
            case "null-fields" -> assertThrows(IllegalArgumentException.class, () -> ResultRowPreview.capture(result, 0, null, 0));
            case "negative-field" -> assertThrows(IllegalArgumentException.class, () -> ResultRowPreview.capture(result, 0, List.of(-1), 0));
            case "out-field" -> assertThrows(IllegalArgumentException.class, () -> ResultRowPreview.capture(result, 0, List.of(2), 0));
            case "duplicate" -> assertThrows(IllegalArgumentException.class, () -> ResultRowPreview.capture(result, 0, List.of(0, 0), 0));
            case "short-row" -> assertThrows(IllegalArgumentException.class, () -> ResultRowPreview.capture(QueryResult.query(List.of("a", "b"), List.of(List.of("A")), 0), 0, List.of(0, 1), 0));
            default -> throw new AssertionError(state);
        }
    }

    @Test void validatesEvenOmittedColumnsBeforeCapturingAndRejectsNullIndexes() {
        var names = java.util.stream.IntStream.range(0, 201).mapToObj(i -> "f" + i).toList();
        var indexes = new ArrayList<>(java.util.stream.IntStream.range(0, 201).boxed().toList());
        var result = QueryResult.query(names, List.of(names.stream().map(name -> (Object) name).toList()), 0);
        indexes.set(200, 999);
        assertThrows(IllegalArgumentException.class, () -> ResultRowPreview.capture(result, 0, indexes, 0));
        indexes.set(200, null);
        assertThrows(IllegalArgumentException.class, () -> ResultRowPreview.capture(result, 0, indexes, 0));
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 65_537})
    void invalidInternalTextLimitsCannotBypassPreviewBounds(int limit) {
        var result = QueryResult.query(List.of("value"), List.of(List.of("sample")), 0);
        assertThrows(IllegalArgumentException.class, () -> ResultCellPreview.capture(result, 0, 0, 0, limit));
    }
}
