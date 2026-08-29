package com.datacube.sqleditor.result;

import com.datacube.spi.model.ResultColumn;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.Locale;
import java.util.Objects;

public final class FilterValueParser {
    private FilterValueParser() {
    }

    public static Object parse(ResultColumn column, FilterOperator operator, String input) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(operator, "operator");
        if (!operator.valueRequired()) return null;
        if (input == null) throw invalid(column, null);
        if (isTextType(column.jdbcType())) return input;
        if (input.isBlank()) {
            throw invalid(column, input);
        }
        String value = input.trim();
        try {
            return switch (column.jdbcType()) {
                case Types.TINYINT -> Byte.valueOf(value);
                case Types.SMALLINT -> Short.valueOf(value);
                case Types.INTEGER -> Integer.valueOf(value);
                case Types.BIGINT -> Long.valueOf(value);
                case Types.REAL, Types.FLOAT -> finite(Float.valueOf(value), column, input);
                case Types.DOUBLE -> finite(Double.valueOf(value), column, input);
                case Types.NUMERIC, Types.DECIMAL -> new BigDecimal(value);
                case Types.BIT, Types.BOOLEAN -> parseBoolean(column, value);
                case Types.DATE -> Date.valueOf(LocalDate.parse(value));
                case Types.TIME -> Time.valueOf(LocalTime.parse(value));
                case Types.TIME_WITH_TIMEZONE -> OffsetTime.parse(value);
                case Types.TIMESTAMP -> Timestamp.valueOf(LocalDateTime.parse(value.replace(' ', 'T')));
                case Types.TIMESTAMP_WITH_TIMEZONE -> OffsetDateTime.parse(value);
                default -> input;
            };
        } catch (RuntimeException exception) {
            throw invalid(column, input);
        }
    }

    private static Boolean parseBoolean(ResultColumn column, String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "false".equals(normalized)) {
            return Boolean.valueOf(normalized);
        }
        throw invalid(column, value);
    }

    private static Float finite(Float value, ResultColumn column, String input) {
        if (!Float.isFinite(value)) throw invalid(column, input);
        return value;
    }

    private static Double finite(Double value, ResultColumn column, String input) {
        if (!Double.isFinite(value)) throw invalid(column, input);
        return value;
    }

    private static boolean isTextType(int jdbcType) {
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR,
                    Types.NVARCHAR, Types.LONGNVARCHAR -> true;
            default -> false;
        };
    }

    private static IllegalArgumentException invalid(ResultColumn column, String input) {
        return new IllegalArgumentException("列“" + column.label() + "”的筛选值“"
                + input + "”格式无效");
    }
}
