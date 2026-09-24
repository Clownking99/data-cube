package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultFilterSavedViewTest {
    @Test void savedViewKeepsRawFiltersButInvalidatesOldDatabaseCompletionTokens() {
        var a = QueryResult.query(List.of("n"), List.of(List.of("sentinel"), List.of("other")), 1);
        var b = QueryResult.query(List.of("n"), List.of(List.of("new")), 1);
        var state = new ResultFilterState(); state.showOriginal(a, "select n", null);
        state.appendCondition(new FilterCondition(0, FilterConnector.AND, FilterOperator.EQ, "sentinel"));
        var request = state.databaseRequest(); var saved = state.saveView();
        state.showOriginal(b, "select n", null); state.restoreView(saved);
        assertSame(a, state.snapshot().activeResult()); assertEquals(List.of(0), state.snapshot().visibleRowIndexes());
        assertFalse(state.databaseApplied(request.generation(), b));
        assertFalse(state.databaseFailed(request.generation(), "stale"));
        assertFalse(saved.toString().contains("sentinel")); assertFalse(state.snapshot().toString().contains("sentinel"));
        var current = state.databaseRequest(); assertTrue(current.generation() > request.generation());
        assertEquals("sentinel", current.conditions().getFirst().value());
    }

    @Test void partialSourceCannotBeRewrappedAsAnApparentlyCompleteSelect() {
        var partial = QueryResult.query(List.of("n"), List.of(List.of(1)), 1).withRetentionNotice("SQL 仅保留前缀");
        assertFalse(SafeSelectEligibility.check("select 1", false, partial).eligible());
    }
}
