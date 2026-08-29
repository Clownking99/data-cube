package com.datacube.sqleditor.result;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministically formats selected result cells for tab-separated clipboard data. */
public final class TsvClipboardFormatter {
    public record CellRef(int row, int column) {
        public CellRef {
            if (row < 0 || column < 0) throw new IllegalArgumentException("Cell indexes must be non-negative");
        }
    }

    private TsvClipboardFormatter() {
    }

    public static String rectangle(List<String> headers, List<List<String>> rows,
            Set<CellRef> selectedCells, boolean includeHeader) {
        List<CellRef> selected = selectedCells == null ? List.of() : selectedCells.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(CellRef::row).thenComparingInt(CellRef::column))
                .toList();
        if (selected.isEmpty()) return "";

        int firstRow = selected.getFirst().row();
        int lastRow = selected.getLast().row();
        int firstColumn = selected.stream().mapToInt(CellRef::column).min().orElseThrow();
        int lastColumn = selected.stream().mapToInt(CellRef::column).max().orElseThrow();
        Set<CellRef> cells = Set.copyOf(selected);
        List<String> lines = new ArrayList<>();
        if (includeHeader) lines.add(formatRange(headers, firstColumn, lastColumn));
        for (int row = firstRow; row <= lastRow; row++) {
            List<String> fields = new ArrayList<>();
            for (int column = firstColumn; column <= lastColumn; column++) {
                fields.add(cells.contains(new CellRef(row, column)) ? valueAt(rows, row, column) : "");
            }
            lines.add(join(fields));
        }
        return String.join("\n", lines);
    }

    public static String rows(List<String> headers, List<List<String>> rows,
            Set<Integer> selectedRows, boolean includeHeader) {
        List<Integer> selected = selectedRows == null ? List.of() : selectedRows.stream()
                .filter(Objects::nonNull)
                .filter(index -> index >= 0)
                .sorted()
                .toList();
        if (selected.isEmpty()) return "";

        int columns = headers == null ? widestRow(rows) : headers.size();
        List<String> lines = new ArrayList<>();
        if (includeHeader) lines.add(formatRange(headers, 0, columns - 1));
        for (int row : selected) lines.add(formatRange(rowAt(rows, row), 0, columns - 1));
        return String.join("\n", lines);
    }

    private static int widestRow(List<List<String>> rows) {
        if (rows == null) return 0;
        return rows.stream().filter(Objects::nonNull).mapToInt(List::size).max().orElse(0);
    }

    private static List<String> rowAt(List<List<String>> rows, int row) {
        return rows != null && row < rows.size() && rows.get(row) != null ? rows.get(row) : List.of();
    }

    private static String valueAt(List<List<String>> rows, int row, int column) {
        return valueAt(rowAt(rows, row), column);
    }

    private static String formatRange(List<String> values, int first, int last) {
        if (last < first) return "";
        List<String> fields = new ArrayList<>();
        for (int column = first; column <= last; column++) fields.add(valueAt(values, column));
        return join(fields);
    }

    private static String valueAt(List<String> values, int index) {
        if (values == null || index < 0 || index >= values.size()) return "";
        String value = values.get(index);
        return value == null ? "" : value;
    }

    private static String join(List<String> fields) {
        return fields.stream().map(TsvClipboardFormatter::escape).collect(java.util.stream.Collectors.joining("\t"));
    }

    private static String escape(String value) {
        if (value.indexOf('\t') < 0 && value.indexOf('\n') < 0 && value.indexOf('"') < 0) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
