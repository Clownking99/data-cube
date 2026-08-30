package com.datacube.export;

import java.time.*;
import java.util.*;

public final class QueryXlsxLayoutEstimator {
    private static final Set<Class<?>> SHORT_SCALARS = Set.of(
            Character.class, Boolean.class, Byte.class, Short.class, Integer.class,
            Long.class, Float.class, Double.class, UUID.class, LocalDate.class,
            LocalTime.class, LocalDateTime.class, OffsetTime.class,
            OffsetDateTime.class, Instant.class);

    private QueryXlsxLayoutEstimator() {}

    public static XlsxLayout estimate(List<String> columns, List<List<Object>> rows,
                                      Runnable check) {
        Objects.requireNonNull(columns);
        Objects.requireNonNull(rows);
        Objects.requireNonNull(check).run();
        int[] widest = new int[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            check.run();
            widest[c] = measure(columns.get(c));
        }
        int count = Math.min(100, rows.size());
        for (int r = 0; r < count; r++) {
            check.run();
            List<Object> row = rows.get(r);
            for (int c = 0; c < columns.size(); c++) {
                check.run();
                Object value = c < row.size() ? row.get(c) : null;
                widest[c] = Math.max(widest[c], measure(value));
            }
        }
        var widths = new ArrayList<Integer>(columns.size());
        for (int value : widest) widths.add(Math.min(60, Math.max(12, value + 2)));
        check.run();
        return new XlsxLayout(widths);
    }

    private static int measure(Object value) {
        if (value == null) return 0;
        if (value instanceof Double d && !Double.isFinite(d)) return 32;
        if (value instanceof Float f && !Float.isFinite(f)) return 32;
        String text;
        if (value instanceof String string) text = string;
        else if (SHORT_SCALARS.contains(value.getClass())) text = value.toString();
        else return 32;
        int line = 0, widest = 0, scanned = 0;
        for (int offset = 0; offset < text.length() && scanned < 256; scanned++) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == '\r' || cp == '\n') {
                widest = Math.max(widest, line);
                line = 0;
            } else if (cp == '\t') {
                line += 4;
            } else if (cp >= 0x20 && cp != 0xFFFE && cp != 0xFFFF) {
                line += cp < 0x80 ? 1 : 2;
            }
            if (line >= 58) return 58;
        }
        return Math.max(widest, line);
    }
}
