package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class LocalResultFilter {
    private LocalResultFilter() {
    }

    public static List<Integer> visibleRowIndexes(
            QueryResult result, String search, List<FilterCondition> conditions) {
        Objects.requireNonNull(result, "result");
        List<FilterCondition> filters = conditions == null ? List.of() : List.copyOf(conditions);
        String query = search == null ? "" : search.toLowerCase(Locale.ROOT);
        List<Integer> indexes = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < result.rows.size(); rowIndex++) {
            List<Object> row = result.rows.get(rowIndex);
            if (matchesSearch(row, query) && matchesConditions(result.resultColumns, row, filters)) {
                indexes.add(rowIndex);
            }
        }
        return List.copyOf(indexes);
    }

    private static boolean matchesSearch(List<Object> row, String query) {
        if (query.isEmpty()) return true;
        for (Object cell : row) {
            if (ResultValueFormatter.format(cell).toLowerCase(Locale.ROOT).contains(query)) return true;
        }
        return false;
    }

    private static boolean matchesConditions(
            List<ResultColumn> columns, List<Object> row, List<FilterCondition> conditions) {
        if (conditions.isEmpty()) return true;
        boolean accepted = matches(columns, row, conditions.getFirst());
        for (int index = 1; index < conditions.size(); index++) {
            FilterCondition next = conditions.get(index);
            accepted = next.connector() == FilterConnector.AND
                    ? accepted && matches(columns, row, next)
                    : accepted || matches(columns, row, next);
        }
        return accepted;
    }

    private static boolean matches(List<ResultColumn> columns, List<Object> row, FilterCondition condition) {
        if (condition.columnIndex() >= columns.size()) {
            throw new IllegalArgumentException("columnIndex exceeds result columns");
        }
        Object cell = condition.columnIndex() < row.size() ? row.get(condition.columnIndex()) : null;
        return switch (condition.operator()) {
            case IS_NULL -> cell == null;
            case IS_NOT_NULL -> cell != null;
            case EQ -> cell != null && compare(cell, condition.value()) == 0;
            case NE -> cell != null && compare(cell, condition.value()) != 0;
            case CONTAINS -> cell != null && String.valueOf(cell).contains(String.valueOf(condition.value()));
            case STARTS_WITH -> cell != null && String.valueOf(cell).startsWith(String.valueOf(condition.value()));
            case ENDS_WITH -> cell != null && String.valueOf(cell).endsWith(String.valueOf(condition.value()));
            case GT -> cell != null && compare(cell, condition.value()) > 0;
            case GTE -> cell != null && compare(cell, condition.value()) >= 0;
            case LT -> cell != null && compare(cell, condition.value()) < 0;
            case LTE -> cell != null && compare(cell, condition.value()) <= 0;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
        }
        if (left instanceof String || right instanceof String) {
            return String.valueOf(left).compareTo(String.valueOf(right));
        }
        if (left instanceof Comparable comparable) {
            return comparable.compareTo(right);
        }
        return left.equals(right) ? 0 : String.valueOf(left).compareTo(String.valueOf(right));
    }
}
