package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Bounded text-only row projection. Does not retain the source result or hidden field values. */
public final class ResultRowPreview {
    public static final int MAX_FIELDS = 200;
    public static final int MAX_FIELD_TEXT_LENGTH = 4096;
    private final int displayRow;
    private final int sourceRow;
    private final int visibleColumnCount;
    private final List<ResultCellPreview> fields;

    private ResultRowPreview(int displayRow, int sourceRow, int visibleColumnCount, List<ResultCellPreview> fields) {
        this.displayRow = displayRow; this.sourceRow = sourceRow; this.visibleColumnCount = visibleColumnCount;
        this.fields = List.copyOf(fields);
    }

    public static ResultRowPreview capture(QueryResult result, int sourceRow, List<Integer> visibleColumns, int displayRow) {
        if (result == null || result.kind != QueryResult.Kind.QUERY || sourceRow < 0 || sourceRow >= result.rows.size()
                || displayRow < 0 || visibleColumns == null || visibleColumns.isEmpty())
            throw new IllegalArgumentException("No selected result row projection");
        var seen = new HashSet<Integer>();
        // Validate the entire projection before formatting; never replace missing fields with NULL.
        for (Integer column : visibleColumns) {
            if (column == null || column < 0 || column >= result.resultColumns.size()
                    || column >= result.rows.get(sourceRow).size() || !seen.add(column))
                throw new IllegalArgumentException("Invalid result row projection");
        }
        var fields = new ArrayList<ResultCellPreview>();
        for (int i = 0; i < Math.min(MAX_FIELDS, visibleColumns.size()); i++)
            fields.add(ResultCellPreview.capture(result, sourceRow, visibleColumns.get(i), displayRow, MAX_FIELD_TEXT_LENGTH));
        return new ResultRowPreview(displayRow + 1, sourceRow + 1, visibleColumns.size(), fields);
    }

    public int displayRow() { return displayRow; }
    public int sourceRow() { return sourceRow; }
    public int visibleColumnCount() { return visibleColumnCount; }
    public List<ResultCellPreview> fields() { return fields; }
    public boolean columnsTruncated() { return fields.size() < visibleColumnCount; }

    @Override public String toString() {
        return "ResultRowPreview[row=" + sourceRow + ", fields=" + fields.size() + ", truncated=" + columnsTruncated() + "]";
    }
}
