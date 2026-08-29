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
        state.databaseApplied(request(state), FILTERED_RESULT);
        QueryResult before = state.snapshot().activeResult();
        long failureRequest = request(state);

        assertTrue(state.databaseFailed(failureRequest, "timeout"));

        assertEquals(before.rows, state.snapshot().activeResult().rows);
        assertEquals(ResultFilterState.DatabaseStatus.APPLIED, state.snapshot().databaseStatus());
        assertEquals("timeout", state.snapshot().recoverableError());
        assertEquals(List.of(CONDITION), state.snapshot().conditions());
    }

    @Test
    void databaseFailureUsesDefaultMessageWhenMissing() {
        ResultFilterState state = preparedState();
        assertTrue(state.databaseFailed(request(state), null));

        assertEquals("数据库筛选失败", state.snapshot().recoverableError());
    }

    @Test
    void clearAfterDatabaseApplyRestoresCachedOriginalWithoutExecution() {
        ResultFilterState state = preparedState();
        state.databaseApplied(request(state), FILTERED_RESULT);

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
    @SuppressWarnings("deprecation")
    void noTokenTerminalMethodsAlwaysFailFast() {
        ResultFilterState state = preparedState();
        request(state);
        ResultFilterState.Snapshot before = state.snapshot();

        assertThrows(IllegalStateException.class, () -> state.databaseApplied(FILTERED_RESULT));
        assertThrows(IllegalStateException.class, () -> state.databaseFailed("timeout"));
        assertSnapshotSame(before, state.snapshot());
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
        assertSame(source, snapshot, "an immutable QueryResult must be frozen only once on ingestion");
        assertSame(source.rows, snapshot.rows);
        assertSame(source.resultColumns, snapshot.resultColumns);
        assertSame(source.columnComments, snapshot.columnComments);
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
        assertSame(update, state.snapshot().originalResult());
        assertResultEquivalent(update, state.snapshot().originalResult());

        for (QueryResult error : List.of(QueryResult.error("sql", 5),
                QueryResult.cancelled("cancelled", 6), QueryResult.timeout("slow", 7))) {
            state.showOriginal(error, "select ID from USERS", null);
            QueryResult snapshot = state.snapshot().originalResult();
            assertSame(error, snapshot);
            assertResultEquivalent(error, snapshot);
        }
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

    @Test
    void newRequestsAndLocalChangesRejectEveryOlderGeneration() {
        ResultFilterState state = preparedState();
        long first = request(state);
        long second = request(state);
        assertFalse(state.databaseApplied(first, FILTERED_RESULT));
        assertFalse(state.databaseFailed(first, "timeout"));

        state.setSearchText("2");
        long third = request(state);
        assertFalse(state.databaseApplied(second, FILTERED_RESULT));
        assertFalse(state.databaseFailed(second, "timeout"));
        assertTrue(state.databaseApplied(third, FILTERED_RESULT));
    }

    @Test
    void databaseRequestAndBeginDatabaseRequestSafelyReplaceOneAnother() {
        ResultFilterState state = preparedState();
        long first = request(state);
        long tagged = state.beginDatabaseRequest().generation();
        assertFalse(state.databaseApplied(first, FILTERED_RESULT));
        assertTrue(state.databaseApplied(tagged, FILTERED_RESULT));

        long replacement = request(state);
        assertTrue(state.databaseApplied(replacement, RESULT));

        long staleTagged = state.beginDatabaseRequest().generation();
        long finalRequest = request(state);
        assertFalse(state.databaseApplied(staleTagged, FILTERED_RESULT));
        assertTrue(state.databaseFailed(finalRequest, "timeout"));
        assertEquals("timeout", state.snapshot().recoverableError());
    }

    @Test
    void dirtyAfterApplyAndClearAllResetAllVisibleState() {
        ResultFilterState state = new ResultFilterState();
        state.showOriginal(RESULT, "select ID from USERS", "连接不可用");
        state.setConditions(List.of(CONDITION));
        state.setSearchText("2");
        state.clearFilters();
        state.showOriginal(RESULT, "select ID from USERS", null);
        state.setConditions(List.of(CONDITION));
        state.databaseApplied(request(state), FILTERED_RESULT);
        state.setSearchText("2");
        assertEquals(ResultFilterState.DatabaseStatus.DIRTY_AFTER_APPLY, state.snapshot().databaseStatus());

        state.clearAll();

        ResultFilterState.Snapshot snapshot = state.snapshot();
        assertNull(snapshot.originalResult());
        assertNull(snapshot.activeResult());
        assertNull(snapshot.originalSql());
        assertEquals("", snapshot.searchText());
        assertEquals(List.of(), snapshot.conditions());
        assertEquals(List.of(), snapshot.visibleRowIndexes());
        assertEquals(ResultFilterState.DatabaseStatus.ORIGINAL, snapshot.databaseStatus());
        assertNull(snapshot.databaseUnavailableReason());
        assertNull(snapshot.recoverableError());
    }

    @Test
    void mutableResultCellsAreFrozenAtInputAndOutputBoundaries() {
        StringBuilder text = new StringBuilder("before");
        java.sql.Timestamp timestamp = java.sql.Timestamp.valueOf("2026-08-29 10:11:12.123456789");
        byte[] bytes = {1, 2, 3};
        QueryResult source = QueryResult.queryWithMetadata(List.of(
                new ResultColumn(0, "TEXT", Types.VARCHAR, "VARCHAR"),
                new ResultColumn(1, "TS", Types.TIMESTAMP, "TIMESTAMP"),
                new ResultColumn(2, "BIN", Types.VARBINARY, "VARBINARY")),
                List.of(new ArrayList<>(List.of(text, timestamp, bytes))), 1, false);
        ResultFilterState state = new ResultFilterState();
        state.showOriginal(source, "select * from T", null);

        text.append(" changed");
        timestamp.setNanos(1);
        bytes[0] = 9;
        List<Object> exposed = state.snapshot().originalResult().rows.getFirst();
        assertEquals("before", exposed.get(0));
        assertEquals(java.time.LocalDateTime.parse("2026-08-29T10:11:12.123456789"), exposed.get(1));
        assertEquals("010203", ResultValueFormatter.format(exposed.get(2)));
        assertFalse(exposed.get(1) instanceof java.sql.Timestamp);
        assertFalse(exposed.get(2) instanceof byte[]);
        assertThrows(UnsupportedOperationException.class, () -> exposed.set(0, "changed output"));
        List<Object> later = state.snapshot().originalResult().rows.getFirst();
        assertSame(exposed, later);
        assertSame(exposed.get(1), later.get(1));
        assertSame(exposed.get(2), later.get(2));
    }

    @Test
    void mutableConditionValuesAreFrozenForStateSnapshotAndRequest() {
        byte[] value = {4, 5};
        FilterCondition condition = new FilterCondition(0, FilterConnector.AND, FilterOperator.EQ, value);
        ResultFilterState state = new ResultFilterState();
        state.showOriginal(QueryResult.queryWithMetadata(List.of(column()), List.of(List.of("x")), 1, false),
                "select ID from USERS", null);
        state.setConditions(List.of(condition));

        value[0] = 9;
        ResultFilterState.Snapshot snapshot = state.snapshot();
        Object fromSnapshot = snapshot.conditions().getFirst().value();
        assertEquals("0405", ResultValueFormatter.format(fromSnapshot));
        assertFalse(fromSnapshot instanceof byte[]);
        ResultFilterState.DatabaseFilterRequest request = state.databaseRequest();
        assertSame(snapshot.conditions(), request.conditions());
        assertSame(fromSnapshot, request.conditions().getFirst().value());
        assertSame(snapshot.conditions(), state.snapshot().conditions());
    }

    @Test
    void databaseRequestsRetainTheEffectiveSchemaUntilNewResultOrClear() {
        ResultFilterState state = new ResultFilterState();
        state.showOriginal(RESULT, "select ID from USERS", "schema_a", null);
        state.setConditions(List.of(CONDITION));

        ResultFilterState.DatabaseFilterRequest request = state.databaseRequest();
        assertEquals("schema_a", request.effectiveSchema());
        assertEquals("schema_a", state.snapshot().effectiveSchema());

        state.showOriginal(RESULT, "select ID from USERS", "schema_b", null);
        assertEquals("schema_b", state.snapshot().effectiveSchema());
        state.clearAll();
        assertNull(state.snapshot().effectiveSchema());
    }

    @Test
    void diagnosticRepresentationsRedactSqlSchemaSearchAndConditionValues() {
        String sentinel = "sentinel-state-secret-7f3a";
        ResultFilterState state = new ResultFilterState();
        state.showOriginal(RESULT, "select '" + sentinel + "'", sentinel, null);
        state.setSearchText(sentinel);
        state.setConditions(List.of(new FilterCondition(
                0, FilterConnector.AND, FilterOperator.EQ, sentinel)));

        ResultFilterState.DatabaseFilterRequest request = state.databaseRequest();
        assertFalse(request.toString().contains(sentinel));
        assertFalse(state.snapshot().toString().contains(sentinel));
        assertFalse(request.conditions().getFirst().toString().contains(sentinel));
    }

    @Test
    void stateDiagnosticRepresentationsNeverDelegateToUnsafeErrorResultText() {
        String sentinel = "sentinel-error-result-secret-7f3a";
        QueryResult unsafe = QueryResult.error("driver echoed " + sentinel, 4);
        ResultFilterState.DatabaseFilterRequest request =
                new ResultFilterState.DatabaseFilterRequest(
                        7, "select ?", "schema_a", unsafe, List.of());
        ResultFilterState.Snapshot snapshot = new ResultFilterState.Snapshot(
                unsafe, unsafe, "select ?", "schema_a", "", List.of(), List.of(),
                ResultFilterState.DatabaseStatus.ORIGINAL, null, null);

        assertFalse(request.toString().contains(sentinel));
        assertFalse(snapshot.toString().contains(sentinel));
    }

    @Test
    void calendarsAndReferenceArraysFreezeWithoutElementTypeFailures() {
        CalendarValue value = new CalendarValue();
        java.util.Calendar calendar = value.calendar();
        StringBuilder[] builders = {new StringBuilder("first"), new StringBuilder("second")};
        Object[] nested = {builders, new Object[]{new StringBuilder("nested"), new int[]{1, 2}}};
        QueryResult source = QueryResult.queryWithMetadata(List.of(
                new ResultColumn(0, "CAL", Types.TIMESTAMP, "TIMESTAMP"),
                new ResultColumn(1, "ARRAY", Types.ARRAY, "ARRAY")),
                List.of(new ArrayList<>(List.of(calendar, nested))), 1, false);
        ResultFilterState state = new ResultFilterState();
        state.showOriginal(source, "select * from T", null);

        calendar.setTimeInMillis(0);
        builders[0].append(" changed");
        List<Object> exposed = state.snapshot().originalResult().rows.getFirst();
        assertEquals(value.instant(), exposed.get(0));
        assertEquals("[[first, second], [nested, [1, 2]]]", ResultValueFormatter.format(exposed.get(1)));
        assertFalse(exposed.get(0) instanceof java.util.Calendar);
        assertFalse(exposed.get(1).getClass().isArray());
        assertSame(exposed.get(0), state.snapshot().originalResult().rows.getFirst().get(0));
        assertSame(exposed.get(1), state.snapshot().originalResult().rows.getFirst().get(1));
    }

    @Test
    void unknownIdentityOnlyValueIsRejectedBeforeItCanEnterState() {
        ResultFilterState state = preparedState();
        long generation = state.beginDatabaseRequest().generation();
        ResultFilterState.Snapshot before = state.snapshot();

        assertThrows(IllegalArgumentException.class, () -> QueryResult.queryWithMetadata(List.of(column()),
                List.of(List.of(new Object())), 1, false));
        assertSnapshotSame(before, state.snapshot());
        assertTrue(state.databaseApplied(generation, FILTERED_RESULT));
    }

    @Test
    void repeatedSnapshotsAndRequestsShareImmutablePayloads() {
        ResultFilterState state = preparedState();

        ResultFilterState.Snapshot first = state.snapshot();
        ResultFilterState.Snapshot second = state.snapshot();
        ResultFilterState.DatabaseFilterRequest request = state.databaseRequest();

        assertSame(RESULT, first.originalResult());
        assertSame(first.originalResult(), second.originalResult());
        assertSame(first.activeResult(), second.activeResult());
        assertSame(first.originalResult().rows, second.originalResult().rows);
        assertSame(first.originalResult().rows.getFirst(), second.originalResult().rows.getFirst());
        assertSame(first.originalResult().resultColumns, second.originalResult().resultColumns);
        assertSame(first.conditions(), second.conditions());
        assertSame(first.visibleRowIndexes(), second.visibleRowIndexes());
        assertSame(first.originalResult(), request.originalResult());
        assertSame(first.conditions(), request.conditions());
        assertThrows(UnsupportedOperationException.class,
                () -> request.originalResult().rows.getFirst().set(0, 99));
        assertThrows(UnsupportedOperationException.class, () -> request.conditions().clear());
    }

    private static ResultFilterState preparedState() {
        ResultFilterState state = new ResultFilterState();
        state.showOriginal(RESULT, "select ID from USERS", null);
        state.setConditions(List.of(CONDITION));
        return state;
    }

    private static long request(ResultFilterState state) {
        return state.databaseRequest().generation();
    }

    private static void assertInvalidates(Consumer<ResultFilterState> invalidation) {
        ResultFilterState state = preparedState();
        long generation = state.beginDatabaseRequest().generation();

        invalidation.accept(state);

        assertFalse(state.databaseApplied(generation, FILTERED_RESULT));
        assertFalse(state.databaseFailed(generation, "timeout"));
    }

    private static void assertSnapshotSame(ResultFilterState.Snapshot expected, ResultFilterState.Snapshot actual) {
        assertResultEquivalent(expected.originalResult(), actual.originalResult());
        assertResultEquivalent(expected.activeResult(), actual.activeResult());
        assertEquals(expected.originalSql(), actual.originalSql());
        assertEquals(expected.searchText(), actual.searchText());
        assertEquals(expected.conditions(), actual.conditions());
        assertEquals(expected.visibleRowIndexes(), actual.visibleRowIndexes());
        assertEquals(expected.databaseStatus(), actual.databaseStatus());
        assertEquals(expected.databaseUnavailableReason(), actual.databaseUnavailableReason());
        assertEquals(expected.recoverableError(), actual.recoverableError());
    }

    private static void assertResultEquivalent(QueryResult expected, QueryResult actual) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual);
            return;
        }
        assertEquals(expected.kind, actual.kind);
        assertEquals(expected.columns, actual.columns);
        assertEquals(expected.columnComments, actual.columnComments);
        assertEquals(expected.resultColumns, actual.resultColumns);
        assertEquals(expected.rows, actual.rows);
        assertEquals(expected.updateCount, actual.updateCount);
        assertEquals(expected.elapsedMillis, actual.elapsedMillis);
        assertEquals(expected.errorMessage, actual.errorMessage);
        assertEquals(expected.failureKind, actual.failureKind);
        assertEquals(expected.truncated, actual.truncated);
    }

    private static QueryResult result(List<List<Integer>> values) {
        List<List<Object>> rows = values.stream().<List<Object>>map(value -> new ArrayList<>(value)).toList();
        return QueryResult.queryWithMetadata(List.of(column()), rows, 1, false);
    }

    private static ResultColumn column() {
        return new ResultColumn(0, "ID", Types.INTEGER, "INTEGER");
    }

    private static final class CalendarValue {
        private final java.util.Calendar calendar = new java.util.GregorianCalendar(
                2026, java.util.Calendar.AUGUST, 29, 10, 11, 12);
        private final java.time.Instant instant = calendar.toInstant();

        private java.util.Calendar calendar() { return calendar; }

        private java.time.Instant instant() { return instant; }
    }
}
