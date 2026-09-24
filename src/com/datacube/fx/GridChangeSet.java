package com.datacube.fx;

import com.datacube.service.DataEditService;
import java.util.*;

/** Page-local row identities and pending changes. No UI or database side effects. */
final class GridChangeSet {
    private final EditableGridModel model;
    private final List<EditableGridModel.Row> rows;
    GridChangeSet(EditableGridModel model, List<List<Object>> values) {
        this.model = model;
        rows = new ArrayList<>(values.stream().map(model::toRow).toList());
    }
    List<EditableGridModel.Row> rows() { return List.copyOf(rows); }
    long pendingCount() { return rows.stream().filter(EditableGridModel.Row::dirty).count(); }
    boolean hasPending() { return pendingCount() != 0; }
    boolean hasUnknown() { return rows.stream().anyMatch(r -> r.state() == EditableGridModel.RowState.UNKNOWN); }
    EditableGridModel.Row add() {
        if (rows.size() >= 1000) throw new IllegalStateException("当前页面最多保留 1000 行，请先保存并刷新");
        var row = model.newRow(); rows.add(row); return row;
    }
    void delete(Collection<EditableGridModel.Row> selected) {
        for (var row : List.copyOf(selected)) if (rows.contains(row) && row.editable()) {
            if (row.created()) rows.remove(row);
            else { row.setState(EditableGridModel.RowState.DELETED); row.result(""); }
        }
    }
    void discard(Collection<EditableGridModel.Row> selected) {
        for (var row : List.copyOf(selected)) if (rows.contains(row) && row.dirty()) {
            if (row.created()) rows.remove(row);
            else model.discard(row);
        }
    }
    void discardAll() { discard(rows()); }
    List<DataEditService.Change> snapshot() {
        if (hasUnknown()) throw new IllegalStateException("存在结果不确定的行，请先核对数据库，不要重复保存");
        List<DataEditService.Change> changes = new ArrayList<>();
        for (var row : rows) {
            if (!row.dirty()) continue;
            var kind = switch (row.state()) {
                case NEW -> DataEditService.ChangeKind.INSERT;
                case MODIFIED -> DataEditService.ChangeKind.UPDATE;
                case DELETED -> DataEditService.ChangeKind.DELETE;
                default -> throw new IllegalStateException("不可保存的行状态");
            };
            changes.add(new DataEditService.Change(row.id(), kind,
                    kind == DataEditService.ChangeKind.DELETE ? Map.of() : model.changedValues(row),
                    kind == DataEditService.ChangeKind.INSERT ? null : model.optimisticKeyOf(row)));
        }
        return List.copyOf(changes);
    }
    void apply(DataEditService.SaveResult result) {
        for (var outcome : result.rows()) {
            var row = rows.stream().filter(r -> r.id() == outcome.rowId()).findFirst().orElseThrow();
            row.result(outcome.message());
            if (outcome.committed()) row.setState(EditableGridModel.RowState.SAVED);
            else if (outcome.status() == DataEditService.SaveStatus.UNKNOWN) row.setState(EditableGridModel.RowState.UNKNOWN);
        }
    }
}
