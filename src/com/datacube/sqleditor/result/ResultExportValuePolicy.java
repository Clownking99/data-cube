package com.datacube.sqleditor.result;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Determines whether a result value may be represented as a complete SQL scalar. */
public final class ResultExportValuePolicy {
    private static final Set<Class<?>> SCALARS = Set.of(
            String.class, Character.class, Boolean.class,
            Byte.class, Short.class, Integer.class, Long.class,
            BigInteger.class, BigDecimal.class, UUID.class, URI.class,
            LocalDate.class, LocalTime.class, LocalDateTime.class,
            OffsetTime.class, OffsetDateTime.class, Instant.class);

    private ResultExportValuePolicy() {
    }

    public record Assessment(long displayOnlyCells) {
        public boolean sqlAllowed() {
            return displayOnlyCells == 0;
        }
    }

    public static boolean isCompleteScalar(Object value) {
        if (value == null) return true;
        if (value instanceof Double d) return Double.isFinite(d);
        if (value instanceof Float f) return Float.isFinite(f);
        return SCALARS.contains(value.getClass());
    }

    public static Object displayValue(Object value) {
        return isCompleteScalar(value) ? value : ResultValueFormatter.format(value);
    }

    public static Assessment assess(List<List<Object>> rows) {
        long displayOnly = 0;
        for (List<Object> row : rows) {
            for (Object value : row) {
                if (!isCompleteScalar(value)) displayOnly++;
            }
        }
        return new Assessment(displayOnly);
    }
}
