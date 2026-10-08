package com.datacube.service;

import com.datacube.service.DataEditService.*;
import com.datacube.spi.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class GridSaveServiceTest {
    private static final TableRef TABLE = new TableRef("synthetic", "items");
    private static Change update(long id) {
        return new Change(id, ChangeKind.UPDATE, Map.of("name", "after-" + id),
                new RowKey(List.of("id", "name"), List.of((int) id, "before")));
    }
    private static List<SaveStatus> statuses(SaveResult result) { return result.rows().stream().map(RowResult::status).toList(); }
    private static SaveResult save(GridSaveProbe probe, Change... changes) throws Exception {
        var request = probe.edit.prepareSave(probe.edit.target("target"), TABLE, List.of(changes), () -> false);
        return request.execute(request.confirm());
    }

    @ParameterizedTest @EnumSource(value=DbType.class, names={"ORACLE", "POSTGRESQL"})
    void readOnlyAndUnconfirmedProductionRejectAllKindsBeforeProviderOrConnection(DbType type) {
        for (boolean readOnly : List.of(true, false)) try (var p = new GridSaveProbe(type, readOnly, "PRODUCTION")) {
            for (Change change : List.of(update(1), new Change(2, ChangeKind.INSERT, Map.of("name", "new"), null),
                    new Change(3, ChangeKind.DELETE, Map.of(), update(3).key()))) {
                var request = p.edit.prepareSave(p.edit.target("target"), TABLE, List.of(change), () -> false);
                assertThrows(IllegalStateException.class, () -> request.execute(null));
                if (readOnly) assertThrows(IllegalStateException.class, request::confirm);
            }
            assertEquals(0, p.resolutions.get()); assertEquals(0, p.jdbc.opens.get()); assertEquals(0, p.jdbc.executes.get());
        }
    }

    @ParameterizedTest @EnumSource(value=DbType.class, names={"ORACLE", "POSTGRESQL"})
    void previewAndConfirmedSaveUseSameFrozenSqlAndParameters(DbType type) throws Exception {
        try (var p = new GridSaveProbe(type, false, "PRODUCTION")) {
            var values = new LinkedHashMap<String, String>(); values.put("id", "9"); values.put("name", "");
            var change = new Change(1, ChangeKind.UPDATE, values, new RowKey(List.of("id", "name"), Arrays.asList(1, null)));
            var changes = new ArrayList<>(List.of(change));
            var target = p.edit.target("target");
            var request = p.edit.prepareSave(target, TABLE, changes, () -> false);
            var permit = request.confirm(); String preview = p.edit.preview(target, TABLE, changes);
            assertEquals(0, p.jdbc.opens.get());
            assertTrue(preview.contains("?2 新值 name = \"\"")); assertTrue(preview.contains("IS NULL 旧值 name = NULL"));
            values.put("name", "changed after confirmation"); changes.clear();
            assertEquals(List.of(SaveStatus.COMMITTED), statuses(request.execute(permit)));
            assertTrue(preview.contains(p.jdbc.sql.getFirst() + ";"));
            assertEquals(Map.of(1, new BigDecimal("9"), 2, "", 3, 1), p.jdbc.bindings.getFirst());
            assertEquals(1, p.jdbc.commits.get()); assertEquals(1, p.jdbc.closes.get());
            assertThrows(IllegalStateException.class, () -> request.execute(permit));
            var other = p.edit.prepareSave(target, TABLE, List.of(update(2)), () -> false);
            assertThrows(IllegalStateException.class, () -> other.execute(permit));
            assertEquals(1, p.jdbc.executes.get());
        }
    }

    @Test void firstFailureStopsBatchAndNeverRollsBackEarlierCommittedRowsOrBrowseTransaction() throws Exception {
        try (var p = new GridSaveProbe(DbType.POSTGRESQL, false, "TEST")) {
            var browse = p.manager.acquire("target"); browse.setAutoCommit(false);
            p.jdbc.faults.addAll(List.of("", "execute", ""));
            var result = save(p, update(1), update(2), update(3));
            assertEquals(List.of(SaveStatus.COMMITTED, SaveStatus.FAILED, SaveStatus.NOT_EXECUTED), statuses(result));
            assertEquals(1, result.committedCount()); assertEquals(2, p.jdbc.executes.get());
            assertEquals(1, p.jdbc.commits.get()); assertEquals(1, p.jdbc.rollbacks.get());
            assertFalse(browse.getAutoCommit()); assertFalse(browse.isClosed());
            assertEquals(3, p.jdbc.opens.get()); assertEquals(2, p.jdbc.closes.get());
            assertFalse(result.toString().contains("synthetic-secret-value"));
        }
    }

    @ParameterizedTest @ValueSource(ints={0,2})
    void conflictingOldValuesAndNonUniqueMatchesAreRolledBack(int count) throws Exception {
        try (var p = new GridSaveProbe(DbType.POSTGRESQL, false, "TEST")) {
            p.jdbc.counts.add(count);
            var result = save(p, update(1), update(2));
            assertEquals(List.of(SaveStatus.CONFLICT, SaveStatus.NOT_EXECUTED), statuses(result));
            assertTrue(result.rows().getFirst().message().contains(count == 0 ? "旧值冲突" : "不唯一"));
            assertEquals(1, p.jdbc.rollbacks.get()); assertEquals(0, p.jdbc.commits.get());
        }
    }

    @ParameterizedTest @ValueSource(strings={"commit", "execute rollback", "restore", "close"})
    void uncertainCommitAndCleanupFailureCannotBeReportedAsSafeRetry(String fault) throws Exception {
        try (var p = new GridSaveProbe(DbType.POSTGRESQL, false, "TEST")) {
            p.jdbc.faults.add(fault);
            var result = save(p, update(1), update(2));
            boolean committed = fault.equals("restore") || fault.equals("close");
            assertEquals(List.of(committed ? SaveStatus.COMMITTED_WARNING : SaveStatus.UNKNOWN, SaveStatus.NOT_EXECUTED), statuses(result));
            assertEquals(committed ? 1 : 0, result.committedCount());
            assertEquals(1, p.jdbc.opens.get()); assertEquals(1, p.jdbc.executes.get());
            assertFalse(result.toString().contains("synthetic-secret-value"));
        }
    }

    @Test void cancelBeforeStartAndAfterFirstCommitAcquireOnlyAdmittedResources() throws Exception {
        try (var p = new GridSaveProbe(DbType.ORACLE, false, "TEST")) {
            AtomicBoolean cancel = new AtomicBoolean(true);
            var noWrite = p.edit.prepareSave(p.edit.target("target"), TABLE, List.of(update(1)), cancel::get);
            assertEquals(List.of(SaveStatus.NOT_EXECUTED), statuses(noWrite.execute(null)));
            assertEquals(0, p.jdbc.opens.get());
            cancel.set(false); p.jdbc.onCommit = () -> cancel.set(true);
            var batch = p.edit.prepareSave(p.edit.target("target"), TABLE, List.of(update(1), update(2)), cancel::get);
            assertEquals(List.of(SaveStatus.COMMITTED, SaveStatus.NOT_EXECUTED), statuses(batch.execute(null)));
            assertEquals(1, p.jdbc.opens.get()); assertEquals(1, p.jdbc.commits.get());
        }
    }

    @Test void configurationTighteningStopsSubsequentRowsWithoutUndoingCurrentCommit() throws Exception {
        try (var p = new GridSaveProbe(DbType.POSTGRESQL, false, "TEST")) {
            p.jdbc.onCommit = p::tighten;
            assertEquals(List.of(SaveStatus.COMMITTED, SaveStatus.NOT_EXECUTED), statuses(save(p, update(1), update(2))));
            assertEquals(1, p.jdbc.opens.get()); assertEquals(1, p.jdbc.commits.get()); assertEquals(0, p.jdbc.rollbacks.get());
        }
    }

    @Test void staleApprovalAndChangeWhileOpeningNeverDispatchToEditor() throws Exception {
        try (var p = new GridSaveProbe(DbType.POSTGRESQL, false, "PRODUCTION")) {
            var request = p.edit.prepareSave(p.edit.target("target"), TABLE, List.of(update(1)), () -> false);
            var permit = request.confirm(); p.tighten();
            assertThrows(IllegalStateException.class, () -> request.execute(permit)); assertEquals(0, p.jdbc.opens.get());
        }
        try (var p = new GridSaveProbe(DbType.POSTGRESQL, false, "PRODUCTION")) {
            p.onOpen = p::tighten;
            assertEquals(List.of(SaveStatus.NOT_EXECUTED), statuses(save(p, update(1))));
            assertEquals(1, p.jdbc.opens.get()); assertEquals(1, p.jdbc.closes.get()); assertEquals(0, p.jdbc.executes.get());
        }
    }

    @Test void invalidNumericValueRollsBackAndNullUsesTypedBinding() throws Exception {
        try (var p = new GridSaveProbe(DbType.POSTGRESQL, false, "TEST")) {
            assertEquals(List.of(SaveStatus.FAILED), statuses(save(p, new Change(1, ChangeKind.INSERT, Map.of("id", "not-number"), null))));
            assertEquals(0, p.jdbc.executes.get()); assertEquals(1, p.jdbc.rollbacks.get());
            var values = new LinkedHashMap<String, String>(); values.put("name", null);
            assertEquals(List.of(SaveStatus.COMMITTED), statuses(save(p, new Change(2, ChangeKind.INSERT, values, null))));
            assertTrue(p.jdbc.bindings.getLast().containsKey(1)); assertNull(p.jdbc.bindings.getLast().get(1));
        }
    }

    @Test void previewIsBoundedAndMarksOmittedValuesWithoutOpeningAConnection() {
        try (var p = new GridSaveProbe(DbType.ORACLE, false, "TEST")) {
            var wide = new LinkedHashMap<String, String>();
            for (int i = 0; i < 2000; i++) wide.put("column" + i, "x".repeat(500));
            var text = p.edit.preview(p.edit.target("target"), TABLE, List.of(new Change(1, ChangeKind.INSERT, wide, null)));
            assertTrue(text.length() <= 256_000); assertTrue(text.contains("字符上限"));
            assertTrue(text.contains("共 500 字符，已省略")); assertEquals(0, p.jdbc.opens.get());
        }
    }
}
