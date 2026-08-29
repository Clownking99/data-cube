package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultFilterStateTest {
    private static final QueryResult RESULT = QueryResult.queryWithMetadata(
            List.of(new com.datacube.spi.model.ResultColumn(0, "ID", Types.INTEGER, "INTEGER")),
            List.of(List.of(1), List.of(2)), 1, false);
    private static final QueryResult FILTERED_RESULT = QueryResult.queryWithMetadata(
            List.of(new com.datacube.spi.model.ResultColumn(0, "ID", Types.INTEGER, "INTEGER")),
            List.of(List.of(2)), 1, false);
    private static final FilterCondition CONDITION = new FilterCondition(
            0, FilterConnector.AND, FilterOperator.GT, 1);

    @Test
    void databaseFailurePreservesActiveRowsAndConditions() {
        ResultFilterState state = new ResultFilterState();
        state.showOriginal(RESULT, "select ID from USERS", null);
        state.setConditions(List.of(CONDITION));
        state.databaseApplied(FILTERED_RESULT);
        QueryResult before = state.snapshot().activeResult();

        state.databaseFailed("timeout");

        assertSame(before, state.snapshot().activeResult());
        assertEquals(ResultFilterState.DatabaseStatus.APPLIED,
                state.snapshot().databaseStatus());
        assertEquals("timeout", state.snapshot().recoverableError());
        assertEquals(List.of(CONDITION), state.snapshot().conditions());
    }

    @Test
    void clearAfterDatabaseApplyRestoresCachedOriginalWithoutExecution() {
        ResultFilterState state = new ResultFilterState();
        state.showOriginal(RESULT, "select ID from USERS", null);
        state.setConditions(List.of(CONDITION));
        state.databaseApplied(FILTERED_RESULT);

        state.clearFilters();

        assertSame(RESULT, state.snapshot().activeResult());
        assertEquals(ResultFilterState.DatabaseStatus.ORIGINAL,
                state.snapshot().databaseStatus());
        assertEquals(List.of(0, 1), state.snapshot().visibleRowIndexes());
    }

    @Test
    void staleDatabaseCompletionCannotReplaceNewerRequestResult() {
        ResultFilterState state = new ResultFilterState();
        state.showOriginal(RESULT, "select ID from USERS", null);
        state.setConditions(List.of(CONDITION));
        long first = state.beginDatabaseRequest().generation();
        long second = state.beginDatabaseRequest().generation();

        assertTrue(state.databaseApplied(second, FILTERED_RESULT));
        assertTrue(!state.databaseApplied(first, RESULT));

        assertSame(FILTERED_RESULT, state.snapshot().activeResult());
        assertEquals(ResultFilterState.DatabaseStatus.APPLIED, state.snapshot().databaseStatus());
    }

    @Test
    void snapshotAndDatabaseRequestAreImmutableAndValidateAvailability() {
        ResultFilterState state = new ResultFilterState();
        state.showOriginal(RESULT, "select ID from USERS", null);
        List<FilterCondition> mutable = new ArrayList<>(List.of(CONDITION));
        state.setConditions(mutable);
        mutable.clear();

        ResultFilterState.Snapshot snapshot = state.snapshot();
        assertEquals(List.of(CONDITION), snapshot.conditions());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.conditions().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.visibleRowIndexes().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> state.databaseRequest().conditions().clear());

        ResultFilterState unavailable = new ResultFilterState();
        unavailable.showOriginal(RESULT, "select ID from USERS", "连接不可用");
        unavailable.setConditions(List.of(CONDITION));
        IllegalStateException failure = assertThrows(IllegalStateException.class, unavailable::databaseRequest);
        assertEquals("连接不可用", failure.getMessage());
    }
}
