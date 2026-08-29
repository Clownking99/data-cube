package com.datacube.sqleditor.result;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;

public final class ResultValueFormatter {
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter OFFSET_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");
    private static final DateTimeFormatter OFFSET_TIME = DateTimeFormatter.ofPattern("HH:mm:ssXXX");

    private ResultValueFormatter() {
    }

    public static String format(Object value) {
        if (value == null) return "";
        if (value instanceof Timestamp timestamp) return DATE_TIME.format(timestamp.toLocalDateTime());
        if (value instanceof Date date) return date.toLocalDate().toString();
        if (value instanceof Time time) return TIME.format(time.toLocalTime());
        if (value instanceof java.time.LocalDateTime dateTime) return DATE_TIME.format(dateTime);
        if (value instanceof java.time.LocalDate date) return date.toString();
        if (value instanceof java.time.LocalTime time) return TIME.format(time);
        if (value instanceof java.time.OffsetDateTime dateTime) return OFFSET_DATE_TIME.format(dateTime);
        if (value instanceof java.time.OffsetTime time) return OFFSET_TIME.format(time);
        return String.valueOf(value);
    }
}
