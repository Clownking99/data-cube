package com.datacube.sqleditor.result;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Deterministically formats selected result cells for tab-separated clipboard data. */
public final class TsvClipboardFormatter {
    public record CellRef(int row, int column) {
        public CellRef {
            if (row < 0 || column < 0) throw invalid("单元格坐标不能为负数");
        }
    }

    private TsvClipboardFormatter() {
    }

    public static String rectangle(List<String> headers, List<List<String>> rows,
            Set<CellRef> selectedCells, boolean includeHeader) {
        if (selectedCells == null || selectedCells.isEmpty()) return "";
        List<CellRef> selected = new ArrayList<>(selectedCells);
        validateCells(selected, rows);
        selected.sort(Comparator.comparingInt(CellRef::row).thenComparingInt(CellRef::column));

        int firstRow = selected.getFirst().row();
        int lastRow = selected.getLast().row();
        int firstColumn = selected.stream().mapToInt(CellRef::column).min().orElseThrow();
        int lastColumn = selected.stream().mapToInt(CellRef::column).max().orElseThrow();
        Set<CellRef> cells = Set.copyOf(selected);
        List<String> lines = new ArrayList<>();
        if (includeHeader && hasHeaders(headers)) lines.add(formatRange(headers, firstColumn, lastColumn));
        int row = firstRow;
        while (true) {
            List<String> fields = new ArrayList<>();
            for (int column = firstColumn; column <= lastColumn; column++) {
                fields.add(cells.contains(new CellRef(row, column)) ? valueAt(rows.get(row), column) : "");
            }
            lines.add(join(fields));
            if (row == lastRow) break;
            row++;
        }
        return String.join("\n", lines);
    }

    public static String rows(List<String> headers, List<List<String>> rows,
            Set<Integer> selectedRows, boolean includeHeader) {
        if (selectedRows == null || selectedRows.isEmpty()) return "";
        List<Integer> selected = new ArrayList<>(selectedRows);
        validateRows(selected, rows);
        selected.sort(Integer::compareTo);

        int columns = hasHeaders(headers) ? headers.size() : widestRow(rows);
        List<String> lines = new ArrayList<>();
        if (includeHeader && hasHeaders(headers)) lines.add(formatRange(headers, 0, columns - 1));
        for (int row : selected) lines.add(formatRange(rows.get(row), 0, columns - 1));
        return String.join("\n", lines);
    }

    private static void validateCells(List<CellRef> selected, List<List<String>> rows) {
        for (CellRef cell : selected) {
            if (cell == null) throw invalid("选择包含空单元格");
            if (rows == null || cell.row() >= rows.size()) throw invalid("选择的行超出结果范围");
            List<String> row = rows.get(cell.row());
            if (row == null || cell.column() >= row.size()) throw invalid("选择的列超出结果范围");
        }
    }

    private static void validateRows(List<Integer> selected, List<List<String>> rows) {
        for (Integer row : selected) {
            if (row == null || row < 0) throw invalid("选择的行不能为负数或空");
            if (rows == null || row >= rows.size() || rows.get(row) == null) {
                throw invalid("选择的行超出结果范围");
            }
        }
    }

    private static boolean hasHeaders(List<String> headers) {
        return headers != null && !headers.isEmpty();
    }

    private static int widestRow(List<List<String>> rows) {
        if (rows == null) return 0;
        int widest = 0;
        for (List<String> row : rows) if (row != null) widest = Math.max(widest, row.size());
        return widest;
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
        return fields.stream().map(TsvClipboardFormatter::escape)
                .collect(java.util.stream.Collectors.joining("\t"));
    }

    private static String escape(String value) {
        if (value.indexOf('\t') < 0 && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0 && value.indexOf('"') < 0) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
