package com.datacube.config;

import com.datacube.spi.model.DbType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlWorkspaceRecoveryTest {
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final UUID D = new UUID(0, 4);

    @Test void resolvesByIdInWorkspaceOrderWithoutChangingTextOrPositions() {
        SqlDraft a = draft(A, " \r\nselect '😀';\r\n\t ");
        SqlDraft b = draft(B, "");
        SqlWorkspace workspace = new SqlWorkspace(40, List.of(entry(B), new SqlWorkspace.Entry(A, 999, 2)), A);
        var result = SqlWorkspaceRecovery.resolve(workspace, List.of(a, draft(C, "ignored"), b));
        assertEquals(List.of(B, A), result.tabs().stream().map(tab -> tab.draft().id()).toList());
        assertEquals(List.of(), result.missingDraftIds());
        assertEquals(A, result.selectedDraftId());
        assertSame(a, result.tabs().get(1).draft());
        assertEquals(" \r\nselect '😀';\r\n\t ", result.tabs().get(1).draft().sql());
        assertEquals("", result.tabs().get(0).draft().sql());
        assertEquals("private-connection", result.tabs().get(1).draft().connectionId());
        assertEquals(DbType.ORACLE, result.tabs().get(1).draft().connectionType());
        assertEquals("same-name", result.tabs().get(1).draft().connectionName());
        assertEquals(" private-schema ", result.tabs().get(1).draft().schema());
        assertEquals(10, result.tabs().get(1).draft().modifiedAt());
        assertEquals(999, result.tabs().get(1).anchor());
        assertEquals(2, result.tabs().get(1).caret());
        assertFalse(result.toString().contains("select '😀'"));
        assertFalse(result.toString().contains("private-schema"));
        assertFalse(result.toString().contains("private-connection"));
    }

    @Test void missingSelectionFallsBackToFirstAvailableWithoutNameSubstitution() {
        var result = SqlWorkspaceRecovery.resolve(new SqlWorkspace(0,
                List.of(entry(C), entry(B), entry(A), entry(D)), C), List.of(draft(A, "a"), draft(B, "b")));
        assertEquals(List.of(C, D), result.missingDraftIds());
        assertEquals(List.of(B, A), result.tabs().stream().map(tab -> tab.draft().id()).toList());
        assertEquals(B, result.selectedDraftId());
    }

    @Test void retainsNullSelectionWhenSelectedPageWasNotSql() {
        var result = SqlWorkspaceRecovery.resolve(new SqlWorkspace(0, List.of(entry(A)), null), List.of(draft(A, "a")));
        assertEquals(1, result.tabs().size());
        assertNull(result.selectedDraftId());
    }

    @Test void allMissingAndEmptyWorkspacesProduceNoTabs() {
        var missing = SqlWorkspaceRecovery.resolve(new SqlWorkspace(0, List.of(entry(A), entry(B)), B), List.of());
        assertEquals(List.of(A, B), missing.missingDraftIds());
        assertEquals(List.of(), missing.tabs());
        assertNull(missing.selectedDraftId());
        var empty = SqlWorkspaceRecovery.resolve(new SqlWorkspace(0, List.of(), null), List.of(draft(A, "unused")));
        assertEquals(List.of(), empty.tabs());
        assertEquals(List.of(), empty.missingDraftIds());
        assertNull(empty.selectedDraftId());
    }

    @Test void resultListsAreImmutableAndDetachedFromCallerList() {
        List<SqlDraft> source = new ArrayList<>(List.of(draft(A, "a")));
        var result = SqlWorkspaceRecovery.resolve(new SqlWorkspace(0, List.of(entry(A), entry(B)), A), source);
        source.clear();
        assertEquals(A, result.tabs().getFirst().draft().id());
        assertEquals(List.of(B), result.missingDraftIds());
        assertThrows(UnsupportedOperationException.class, () -> result.tabs().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.missingDraftIds().clear());
    }

    @Test void rejectsInvalidCandidateSnapshotsWithFixedDiagnostics() {
        SqlWorkspace empty = new SqlWorkspace(0, List.of(), null);
        assertThrows(IllegalArgumentException.class, () -> SqlWorkspaceRecovery.resolve(null, List.of()));
        for (List<SqlDraft> bad : Arrays.asList(null, Arrays.asList(draft(A, "secret"), null),
                List.of(draft(A, "secret"), draft(A, "other")))) {
            var error = assertThrows(IllegalArgumentException.class, () -> SqlWorkspaceRecovery.resolve(empty, bad));
            assertEquals("Invalid SQL workspace draft snapshot", error.getMessage());
            assertNull(error.getCause());
        }
        List<SqlDraft> candidates = new ArrayList<>();
        for (int i = 0; i < 101; i++) candidates.add(draft(new UUID(0, i), "synthetic"));
        assertThrows(IllegalArgumentException.class, () -> SqlWorkspaceRecovery.resolve(empty, candidates));
        assertEquals(List.of(), SqlWorkspaceRecovery.resolve(empty, candidates.subList(0, 100)).tabs());
    }

    private static SqlWorkspace.Entry entry(UUID id) { return new SqlWorkspace.Entry(id, 0, 0); }
    private static SqlDraft draft(UUID id, String sql) {
        return new SqlDraft(id, 10, "private-connection", DbType.ORACLE, "same-name", " private-schema ", sql);
    }
}
