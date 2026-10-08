package com.datacube.fx;

import com.datacube.service.DataEditService.*;
import com.datacube.spi.model.*;
import org.junit.jupiter.api.Test;
import java.sql.Types;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GridChangeSetTest {
    private static EditableColumn column(String name, int type, boolean pk) {
        return new EditableColumn(name, type, "synthetic", true, pk, false, true, null);
    }
    private static EditableGridModel model() {
        return new EditableGridModel(List.of(column("id", Types.INTEGER, true), column("name", Types.VARCHAR, false)));
    }
    @Test void editsDistinguishNullEmptyAndOriginalValueAndSnapshotUsesOldPrimaryKey() {
        var model = model(); var set = new GridChangeSet(model, List.of(Arrays.asList(1, null)));
        var row = set.rows().getFirst(); row.cell(1).setText(""); model.reconcile(row);
        assertEquals(1, set.pendingCount()); assertEquals("", set.snapshot().getFirst().values().get("name"));
        row.cell(0).setText("9"); model.reconcile(row);
        var snapshot = set.snapshot();
        assertEquals(Arrays.asList(1, null), snapshot.getFirst().key().values());
        row.cell(1).setText("later"); assertEquals("", snapshot.getFirst().values().get("name"));
        row.cell(0).setText("1"); row.cell(1).setNull(); model.reconcile(row);
        assertFalse(set.hasPending()); assertTrue(set.snapshot().isEmpty());
    }
    @Test void createDeleteAndDiscardStayWithinOnePageAndKeepIdenticalRowsDistinct() {
        var model = model(); var values = List.<List<Object>>of(List.of(1, "old"), List.of(1, "old"));
        var set = new GridChangeSet(model, values); var other = new GridChangeSet(model, values);
        var first = set.rows().getFirst(); var second = set.rows().getLast();
        assertNotEquals(first.id(), second.id());
        first.cell(1).setText("edit"); model.reconcile(first); set.delete(List.of(second));
        var added = set.add(); added.cell(0).setText("3"); set.delete(List.of(added));
        assertEquals(2, set.pendingCount()); assertEquals(2, set.rows().size());
        assertEquals(List.of(ChangeKind.UPDATE, ChangeKind.DELETE), set.snapshot().stream().map(Change::kind).toList());
        set.discard(List.of(first)); assertEquals("old", first.cell(1).text()); assertEquals(1, set.pendingCount());
        assertFalse(other.hasPending());
        var pendingNew = set.add(); pendingNew.cell(1).setNull();
        assertTrue(set.snapshot().getLast().values().containsKey("name"));
        set.discardAll(); assertFalse(set.hasPending()); assertEquals(2, set.rows().size()); assertTrue(second.editable());
    }
    @Test void comparableOriginalValuesDetectConcurrentChangesAndUnreliableTypesAreExcluded() {
        var model = new EditableGridModel(List.of(column("id", Types.INTEGER, true), column("name", Types.VARCHAR, false),
                column("stamp", Types.TIMESTAMP, false), column("lob", Types.CLOB, false)));
        var row = model.toRow(Arrays.asList(1, "before", java.sql.Timestamp.valueOf("2026-01-01 12:00:00"), "long"));
        row.cell(1).setText("after"); model.reconcile(row);
        assertEquals(List.of("id", "name"), model.optimisticKeyOf(row).columns());
        assertEquals(List.of(1, "before"), model.optimisticKeyOf(row).values());
        var noPk = new EditableGridModel(List.of(column("name", Types.VARCHAR, false), column("lob", Types.CLOB, false)));
        assertTrue(noPk.canLocateRow()); assertEquals(List.of("name"), noPk.optimisticKeyOf(noPk.toRow(List.of("a", "lob"))).columns());
        assertFalse(new EditableGridModel(List.of(column("lob", Types.CLOB, false))).canLocateRow());
    }
    @Test void partialResultsLockCommittedAndUnknownRowsAndRetryContainsOnlyUnexecutedChanges() {
        var model = model(); var set = new GridChangeSet(model, List.of(List.of(1, "a"), List.of(2, "b"), List.of(3, "c")));
        for (var row : set.rows()) { row.cell(1).setText("new"); model.reconcile(row); }
        var rows = set.rows();
        set.apply(new SaveResult(List.of(new RowResult(rows.get(0).id(), SaveStatus.COMMITTED, "saved"),
                new RowResult(rows.get(1).id(), SaveStatus.UNKNOWN, "unknown"),
                new RowResult(rows.get(2).id(), SaveStatus.NOT_EXECUTED, "pending"))));
        assertEquals(2, set.pendingCount()); assertFalse(rows.get(0).editable()); assertFalse(rows.get(1).editable());
        assertThrows(IllegalStateException.class, set::snapshot);
        set.discard(List.of(rows.get(1)));
        assertFalse(rows.get(1).editable()); assertTrue(rows.get(1).result().contains("不确定"));
        assertEquals(List.of(rows.get(2).id()), set.snapshot().stream().map(Change::rowId).toList());
        set.discardAll(); assertEquals("new", rows.get(0).cell(1).text()); assertEquals("c", rows.get(2).cell(1).text());
    }
}
