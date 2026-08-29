package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultFilterStateTest {
    private static final QueryResult RESULT = result(List.of(List.of(1), List.of(2)));
    private static final QueryResult FILTERED_RESULT = result(List.of(List.of(2)));
    private static final FilterCondition CONDITION = new FilterCondition(
            0, FilterConnector.AND, FilterOperator.GT, 1);

    @Test
    void databaseFailurePreservesActiveRowsConditionsAndStatus() {
        ResultFilterState state = preparedState();
        state.databaseApplied(FILTERED_RESULT);
        QueryResult before = state.snapshot().activeResult();

        state.databaseFailed("timeout");

        assertSame(before, state.snapshot().activeResult());
        assertEquals(ResultFilterState.DatabaseStatus.APPLIED, state.snapshot().databaseStatus());
        assertEquals("timeout", state.snapshot().recoverableError());
        assertEquals(List.of(CONDITION), state.snapshot().conditions());
    }

    @Test
    void databaseFailureUsesDefaultMessageWhenMissing() {
        ResultFilterState state = preparedState();

        state.databaseFailed(null);

        assertEquals("数据库筛选失败", state.snapshot().recoverableError());
    }

    @Test
    void clearAfterDatabaseApplyRestoresCachedOriginalWithoutExecution() {
        ResultFilterState state = preparedState();
        state.databaseApplied(FILTERED_RESULT);

        state.clearFilters();

        assertEquals(RESULT.rows, state.snapshot().activeResult().rows);
        assertEquals(ResultFilterState.DatabaseStatus.ORIGINAL, state.snapshot().databaseStatus());
        assertEquals(List.of(0, 1), state.snapshot().visibleRowIndexes());
    }

    @Test
    void onlyFirstTerminalCompletionConsumesTheInFlightGeneration() {
        ResultFilterState state = preparedState();
        long generation = state.beginDatabaseRequest().generation();

        assertTrue(state.databaseFailed(generation, "timeout"));
        assertFalse(state.databaseFailed(generation, "again"));
        assertFalse(state.databaseApplied(generation, FILTERED_RESULT));

        assertEquals("timeout", state.snapshot().recoverableError());
        assertEquals(ResultFilterState.DatabaseStatus.LOCAL_PREVIEW, state.snapshot().databaseStatus());
    }

    @Test
    void staleCompletionCannotReplaceNewerRequestResultOrError() {
        ResultFilterState state = preparedState();
        long first = state.beginDatabaseRequest().generation();
        long second = state.beginDatabaseRequest().generation();

        assertTrue(state.databaseApplied(second, FILTERED_RESULT));
        assertFalse(state.databaseApplied(first, RESULT));
        assertFalse(state.databaseFailed(first, "old timeout"));

        assertEquals(FILTERED_RESULT.rows, state.snapshot().activeResult().rows);
        assertNull(state.snapshot().recoverableError());
        assertEquals(ResultFilterState.DatabaseStatus.APPLIED, state.snapshot().databaseStatus());
    }

    @Test
    void everyInvalidationRejectsBothTerminalKinds() {
        assertInvalidates(state -> state.setSearchText("2"));
        assertInvalidates(state -> state.setConditions(List.of(CONDITION)));
        assertInvalidates(ResultFilterState::clearFilters);
        assertInvalidates(state -> state.showOriginal(RESULT, "select ID from USERS", null));
        assertInvalidates(ResultFilterState::clearAll);
    }

    @Test
    void untaggedTerminalMethodsCannotBypassAnInFlightGeneration() {
        ResultFilterState state = preparedState();
        long generation = state.beginDatabaseRequest().generation();
        ResultFilterState.Snapshot before = state.snapshot();

        assertThrows(IllegalStateException.class, () -> state.databaseApplied(FILTERED_RESULT));
        assertThrows(IllegalStateException.class, () -> state.databaseFailed("timeout"));
        assertSnapshotSame(before, state.snapshot());
        assertTrue(state.databaseApplied(generation, FILTERED_RESULT));
    }

    @Test
    void whitespaceSearchIsARealSearchAndOnlyEmptyTextRestoresOriginalState() {
        ResultFilterState state = new ResultFilterState();
        state.showOriginal(RESULT, "select ID from USERS", null);

        state.setSearchText(" ");
        assertEquals(ResultFilterState.DatabaseStatus.LOCAL_PREVIEW, state.snapshot().databaseStatus());
        assertEquals(List.of(), state.snapshot().visibleRowIndexes());

        state.setSearchText("\t");
        assertEquals(ResultFilterState.DatabaseStatus.LOCAL_PREVIEW, state.snapshot().databaseStatus());
        assertEquals(List.of(), state.snapshot().visibleRowIndexes());

        state.setSearchText("");
        assertEquals(ResultFilterState.DatabaseStatus.ORIGINAL, state.snapshot().databaseStatus());
        assertEquals(List.of(0, 1), state.snapshot().visibleRowIndexes());
    }

    @Test
    void failedConditionOrAppliedResultEvaluationLeavesStateAndRequestUntouched() {
        ResultFilterState state = preparedState();
        long generation = state.beginDatabaseRequest().generation();
        ResultFilterState.Snapshot before = state.snapshot();

        FilterCondition invalid = new FilterCondition(1, FilterConnector.AND, FilterOperator.EQ, 1);
        assertThrows(IllegalArgumentException.class, () -> state.setConditions(List.of(invalid)));
        assertSnapshotSame(before, state.snapshot());
        assertThrows(IllegalArgumentException.class,
                () -> state.databaseApplied(generation, QueryResult.queryWithMetadata(List.of(), List.of(List.of(2)), 1, false)));
        assertSnapshotSame(before, state.snapshot());
        assertTrue(state.databaseApplied(generation, FILTERED_RESULT));
    }

    @Test
    void failedShowOriginalValidationLeavesStateAndRequestUntouched() {
        ResultFilterState state = preparedState();
        long generation = state.beginDatabaseRequest().generation();
        ResultFilterState.Snapshot before = state.snapshot();

        assertThrows(NullPointerException.class, () -> state.showOriginal(null, "sql", null));
        assertSnapshotSame(before, state.snapshot());
        assertThrows(NullPointerException.class, () -> state.showOriginal(RESULT, null, null));
        assertSnapshotSame(before, state.snapshot());
        assertTrue(state.databaseApplied(generation, FILTERED_RESULT));
    }

    @Test
    void stateSnapshotsQueryResultsAgainstOuterAndInnerListAliases() {
        List<ResultColumn> columns = new ArrayList<>(List.of(column()));
        List<Object> row = new ArrayList<>(List.of(1));
        List<List<Object>> rows = new ArrayList<>(List.of(row));
        List<String> comments = new ArrayList<>(List.of("old comment"));
        QueryResult source = QueryResult.queryWithMetadata(columns, rows, 7, true).withColumnComments(comments);
        ResultFilterState state = new ResultFilterState();

        state.showOriginal(source, "select ID from USERS", null);
        row.set(0, 99);
        rows.clear();
        columns.clear();
        comments.set(0, "new comment");

        QueryResult snapshot = state.snapshot().originalResult();
        assertNotSame(source, snapshot);
        assertEquals(List.of("ID"), snapshot.columns);
        assertEquals(List.of(List.of(1)), snapshot.rows);
        assertEquals(List.of("old comment"), snapshot.columnComments);
        assertEquals(List.of(0), state.snapshot().visibleRowIndexes());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.rows.add(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.rows.getFirst().add(2));
    }

    @Test
    void stateSnapshotsUpdateAndErrorResultsWithoutAliasingTheirInstances() {
        ResultFilterState state = new ResultFilterState();
        QueryResult update = QueryResult.update(4, 3);
        state.showOriginal(update, "update USERS", null);
        assertNotSame(update, state.snapshot().originalResult());
        assertEquals(3, state.snapshot().originalResult().updateCount);

        QueryResult error = QueryResult.timeout("slow", 5);
        state.showOriginal(error, "select ID from USERS", null);
        assertNotSame(error, state.snapshot().originalResult());
        assertEquals(QueryResult.FailureKind.TIMEOUT, state.snapshot().originalResult().failureKind);
        assertEquals("slow", state.snapshot().originalResult().errorMessage);
    }

    @Test
    void snapshotAndDatabaseRequestAreImmutableAndValidateAvailabilityAndConditions() {
        ResultFilterState state = preparedState();
        List<FilterCondition> mutable = new ArrayList<>(List.of(CONDITION));
        state.setConditions(mutable);
        mutable.clear();

        ResultFilterState.Snapshot snapshot = state.snapshot();
        assertEquals(List.of(CONDITION), snapshot.conditions());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.conditions().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.visibleRowIndexes().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.databaseRequest().conditions().clear());

        ResultFilterState empty = new ResultFilterState();
        empty.showOriginal(RESULT, "select ID from USERS", null);
        assertThrows(IllegalStateException.class, empty::databaseRequest);

        ResultFilterState unavailable = new ResultFilterState();
        unavailable.showOriginal(RESULT, "select ID from USERS", "连接不可用");
        unavailable.setConditions(List.of(CONDITION));
        IllegalStateException failure = assertThrows(IllegalStateException.class, unavailable::databaseRequest);
        assertEquals("连接不可用", failure.getMessage());
    }

    private static ResultFilterState preparedState() {
        ResultFilterState state = new ResultFilterState();
        state.showOriginal(RESULT, "select ID from USERS", null);
        state.setConditions(List.of(CONDITION));
        return state;
    }

    private static void assertInvalidates(Consumer<ResultFilterState> invalidation) {
        ResultFilterState state = preparedState();
        long generation = state.beginDatabaseRequest().generation();

        invalidation.accept(state);

        assertFalse(state.databaseApplied(generation, FILTERED_RESULT));
        assertFalse(state.databaseFailed(generation, "timeout"));
    }

    private static void assertSnapshotSame(ResultFilterState.Snapshot expected, ResultFilterState.Snapshot actual) {
        assertSame(expected.originalResult(), actual.originalResult());
        assertSame(expected.activeResult(), actual.activeResult());
        assertEquals(expected.originalSql(), actual.originalSql());
        assertEquals(expected.searchText(), actual.searchText());
        assertEquals(expected.conditions(), actual.conditions());
        assertEquals(expected.visibleRowIndexes(), actual.visibleRowIndexes());
        assertEquals(expected.databaseStatus(), actual.databaseStatus());
        assertEquals(expected.databaseUnavailableReason(), actual.databaseUnavailableReason());
        assertEquals(expected.recoverableError(), actual.recoverableError());
    }

    private static QueryResult result(List<List<Integer>> values) {
        List<List<Object>> rows = values.stream().<List<Object>>map(value -> new ArrayList<>(value)).toList();
        return QueryResult.queryWithMetadata(List.of(column()), rows, 1, false);
    }

    private static ResultColumn column() {
        return new ResultColumn(0, "ID", Types.INTEGER, "INTEGER");
    }
}
