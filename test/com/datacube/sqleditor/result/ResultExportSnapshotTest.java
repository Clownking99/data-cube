package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultExportSnapshotTest {
    @Test void projectsOrderDuplicatesAndRaggedRowsWithoutMutatingInput() {
        QueryResult active = QueryResult.query(List.of("id", "name", "hidden"),
                List.of(List.of(2, "乙", "secret"), List.of(1, "甲"),
                        List.of(1, "甲")), 1);
        List<Integer> visible = new ArrayList<>(List.of(2, 1));
        var snapshot = ResultExportSnapshot.capture(active, "select * from t", visible,
                List.of(new ResultExportSnapshot.Column(1, "name"),
                        new ResultExportSnapshot.Column(0, "id")));
        visible.clear();
        assertEquals(List.of("name", "id"), snapshot.columns());
        assertEquals(List.of(List.of("甲", 1), List.of("甲", 1)),
                snapshot.rows(ResultExportScope.CURRENT_FILTERED));
        assertEquals(List.of(List.of("乙", 2), List.of("甲", 1), List.of("甲", 1)),
                snapshot.rows(ResultExportScope.ALL_LOADED));
        var shortRow = ResultExportSnapshot.capture(active, "select * from t", List.of(1),
                List.of(new ResultExportSnapshot.Column(2, "hidden")));
        assertEquals(Collections.singletonList(null),
                shortRow.rows(ResultExportScope.CURRENT_FILTERED).get(0));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.rows(ResultExportScope.ALL_LOADED).get(0).set(0, "edit"));
        assertFalse(snapshot.toString().contains("secret"));
    }

    @Test void zeroVisibleRowsNeverFallBackAndInvalidPositionsAreRejected() {
        QueryResult active = QueryResult.query(List.of("id"), List.of(List.of(1)), 1);
        var columns = List.of(new ResultExportSnapshot.Column(0, "id"));
        var snapshot = ResultExportSnapshot.capture(active, "select id from t", List.of(), columns);
        assertEquals(0, snapshot.rows(ResultExportScope.CURRENT_FILTERED).size());
        assertEquals(1, snapshot.rows(ResultExportScope.ALL_LOADED).size());
        assertEquals("select id from t", snapshot.originalSql());
        assertThrows(IllegalArgumentException.class,
                () -> ResultExportSnapshot.capture(active, "", List.of(1), columns));
        assertThrows(IllegalArgumentException.class,
                () -> ResultExportSnapshot.capture(QueryResult.update(1, 1), "", List.of(), columns));
    }

    @Test void allLoadedMeansActiveNotCachedOriginalAndTruncationIsCaptured() {
        var columns = List.of(new com.datacube.spi.model.ResultColumn(0, "id",
                java.sql.Types.INTEGER, "int4"));
        var original = QueryResult.queryWithMetadata(columns,
                List.of(List.of(1), List.of(2), List.of(3)), 1, false);
        var active = QueryResult.queryWithMetadata(columns, List.of(List.of(2)), 1, true);
        var state = new ResultFilterState();
        state.showOriginal(original, "select id from t", null);
        state.appendCondition(new FilterCondition(0, FilterConnector.AND,
                FilterOperator.IS_NOT_NULL, null));
        var request = state.databaseRequest();
        assertTrue(state.databaseApplied(request.generation(), active));
        var current = state.snapshot();
        var captured = ResultExportSnapshot.capture(current.activeResult(), current.originalSql(),
                current.visibleRowIndexes(), List.of(new ResultExportSnapshot.Column(0, "id")));
        assertSame(original, current.originalResult());
        assertEquals(List.of(List.of(2)), captured.rows(ResultExportScope.ALL_LOADED));
        assertTrue(captured.truncated());
        state.showOriginal(original, "select id from other", null);
        assertTrue(captured.truncated());
        assertEquals(1, captured.rows(ResultExportScope.ALL_LOADED).size());
    }
}
