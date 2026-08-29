package com.datacube.sqleditor.result;

import com.datacube.spi.model.ResultColumn;
import java.sql.Types;
import java.util.List;

public enum FilterOperator {
    EQ(true), NE(true), CONTAINS(true), STARTS_WITH(true), ENDS_WITH(true),
    GT(true), GTE(true), LT(true), LTE(true), IS_NULL(false), IS_NOT_NULL(false);

    private final boolean valueRequired;

    FilterOperator(boolean valueRequired) {
        this.valueRequired = valueRequired;
    }

    public boolean valueRequired() {
        return valueRequired;
    }

    public static List<FilterOperator> allowedFor(ResultColumn column) {
        return switch (column.jdbcType()) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR,
                    Types.NVARCHAR, Types.LONGNVARCHAR -> List.of(EQ, NE, CONTAINS,
                    STARTS_WITH, ENDS_WITH, IS_NULL, IS_NOT_NULL);
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                    Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC,
                    Types.DECIMAL, Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE,
                    Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> List.of(EQ, NE,
                    GT, GTE, LT, LTE, IS_NULL, IS_NOT_NULL);
            default -> List.of(EQ, NE, IS_NULL, IS_NOT_NULL);
        };
    }
}
