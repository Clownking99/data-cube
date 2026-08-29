package com.datacube.sqleditor.result;

import java.util.Objects;

public record FilterCondition(
        int columnIndex, FilterConnector connector, FilterOperator operator, Object value) {
    public FilterCondition {
        if (columnIndex < 0) throw new IllegalArgumentException("columnIndex must be non-negative");
        connector = Objects.requireNonNull(connector, "connector");
        operator = Objects.requireNonNull(operator, "operator");
        if (operator.valueRequired() && value == null) {
            throw new IllegalArgumentException("operator requires a value");
        }
        if (!operator.valueRequired()) value = null;
    }
}
