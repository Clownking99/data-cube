package com.datacube.sqleditor.result;

import static org.junit.jupiter.api.Assertions.*;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class LocalResultFilterTest {
    private final QueryResult result = QueryResult.queryWithMetadata(
            List.of(new ResultColumn(0, "NAME", Types.VARCHAR, "VARCHAR"),
                    new ResultColumn(1, "SCORE", Types.INTEGER, "INTEGER"),
                    new ResultColumn(2, "NOTE", Types.VARCHAR, "VARCHAR")),
            List.of(java.util.Arrays.asList("Ada", 90, null),
                    java.util.Arrays.asList("Lin", 70, "ok"),
                    java.util.Arrays.asList("Bo", 40, "Ada fan")), 1, false);

    @Test
    void globalSearchIgnoresCaseAndSearchesFormattedCells() {
        assertEquals(List.of(0, 2),
                LocalResultFilter.visibleRowIndexes(result, "ada", List.of()));
    }

    @Test
    void conditionsEvaluateStrictlyFromLeftToRight() {
        List<FilterCondition> conditions = List.of(
                new FilterCondition(1, FilterConnector.AND, FilterOperator.GT, 80),
                new FilterCondition(0, FilterConnector.OR, FilterOperator.EQ, "Lin"),
                new FilterCondition(2, FilterConnector.AND, FilterOperator.IS_NOT_NULL, null));
        assertEquals(List.of(1),
                LocalResultFilter.visibleRowIndexes(result, "", conditions));
    }

    @Test
    void nullIsDifferentFromEmptyString() {
        FilterCondition condition = new FilterCondition(
                2, FilterConnector.AND, FilterOperator.IS_NULL, null);
        assertEquals(List.of(0),
                LocalResultFilter.visibleRowIndexes(result, "", List.of(condition)));
    }

    @Test
    void parserReturnsTypedValuesAndRejectsInvalidInputInChinese() {
        ResultColumn integer = new ResultColumn(0, "SCORE", Types.INTEGER, "INTEGER");
        assertEquals(42, FilterValueParser.parse(integer, FilterOperator.EQ, "42"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FilterValueParser.parse(integer, FilterOperator.EQ, "nope"));
        assertTrue(failure.getMessage().matches(".*[\\u4e00-\\u9fff].*"));
    }

    @Test
    void parserHandlesEverySupportedValueTypeAndRejectsInvalidValues() {
        assertEquals(9_223_372_036_854_775_807L, FilterValueParser.parse(
                column(Types.BIGINT), FilterOperator.EQ, "9223372036854775807"));
        assertEquals(new BigDecimal("12.30"), FilterValueParser.parse(
                column(Types.DECIMAL), FilterOperator.EQ, "12.30"));
        assertEquals(1.5F, FilterValueParser.parse(column(Types.REAL), FilterOperator.EQ, "1.5"));
        assertEquals(16_777_217D, FilterValueParser.parse(
                column(Types.FLOAT), FilterOperator.EQ, "16777217"));
        assertEquals(1.5D, FilterValueParser.parse(column(Types.DOUBLE), FilterOperator.EQ, "1.5"));
        assertEquals(new BigDecimal("12.30"), FilterValueParser.parse(
                column(Types.NUMERIC), FilterOperator.EQ, "12.30"));
        assertEquals(true, FilterValueParser.parse(column(Types.BOOLEAN), FilterOperator.EQ, "true"));
        assertEquals(false, FilterValueParser.parse(column(Types.BIT), FilterOperator.EQ, "false"));
        assertEquals(java.sql.Date.valueOf("2026-08-29"), FilterValueParser.parse(
                column(Types.DATE), FilterOperator.EQ, "2026-08-29"));
        assertEquals(java.sql.Time.valueOf("10:11:12"), FilterValueParser.parse(
                column(Types.TIME), FilterOperator.EQ, "10:11:12"));
        assertEquals(java.sql.Timestamp.valueOf("2026-08-29 10:11:12"), FilterValueParser.parse(
                column(Types.TIMESTAMP), FilterOperator.EQ, "2026-08-29 10:11:12"));
        assertEquals(OffsetTime.of(10, 11, 12, 0, ZoneOffset.ofHours(8)), FilterValueParser.parse(
                column(Types.TIME_WITH_TIMEZONE), FilterOperator.EQ, "10:11:12+08:00"));
        assertEquals(OffsetDateTime.of(2026, 8, 29, 10, 11, 12, 0, ZoneOffset.ofHours(8)),
                FilterValueParser.parse(column(Types.TIMESTAMP_WITH_TIMEZONE), FilterOperator.EQ,
                        "2026-08-29T10:11:12+08:00"));

        for (String invalid : List.of("NaN", "Infinity", "-Infinity", " ")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> FilterValueParser.parse(column(Types.DOUBLE), FilterOperator.EQ, invalid));
            assertTrue(failure.getMessage().matches(".*[\\u4e00-\\u9fff].*"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> FilterValueParser.parse(column(Types.BOOLEAN), FilterOperator.EQ, "yes"));
        assertThrows(IllegalArgumentException.class,
                () -> FilterValueParser.parse(column(Types.DATE), FilterOperator.EQ, " "));
    }

    @Test
    void textParserPreservesEmptyAndWhitespaceAndKeepsThemDistinctFromNull() {
        ResultColumn text = column(Types.VARCHAR);
        assertEquals("", FilterValueParser.parse(text, FilterOperator.EQ, ""));
        assertEquals("  ", FilterValueParser.parse(text, FilterOperator.EQ, "  "));
        assertThrows(IllegalArgumentException.class,
                () -> FilterValueParser.parse(text, FilterOperator.EQ, null));
        QueryResult values = QueryResult.queryWithMetadata(List.of(text),
                List.of(Arrays.asList(""), Arrays.asList("  "), Arrays.asList((Object) null)), 1, false);
        assertEquals(List.of(0), LocalResultFilter.visibleRowIndexes(values, "",
                List.of(new FilterCondition(0, FilterConnector.AND, FilterOperator.EQ, ""))));
        assertEquals(List.of(2), LocalResultFilter.visibleRowIndexes(values, "",
                List.of(new FilterCondition(0, FilterConnector.AND, FilterOperator.IS_NULL, null))));
    }

    @Test
    void comparesJavaSqlAndJavaTimeTemporalValuesUsingColumnType() {
        QueryResult temporal = QueryResult.queryWithMetadata(List.of(
                column(Types.DATE), column(Types.TIME), column(Types.TIMESTAMP),
                column(Types.TIME_WITH_TIMEZONE), column(Types.TIMESTAMP_WITH_TIMEZONE)),
                List.of(Arrays.asList(LocalDate.of(2026, 8, 30), LocalTime.of(10, 30),
                        LocalDateTime.of(2026, 8, 30, 10, 30),
                        OffsetTime.of(10, 30, 0, 0, ZoneOffset.ofHours(8)),
                        OffsetDateTime.of(2026, 8, 30, 10, 30, 0, 0, ZoneOffset.ofHours(8)))), 1, false);
        List<FilterCondition> conditions = List.of(
                new FilterCondition(0, FilterConnector.AND, FilterOperator.GT, java.sql.Date.valueOf("2026-08-29")),
                new FilterCondition(1, FilterConnector.AND, FilterOperator.GTE, java.sql.Time.valueOf("10:30:00")),
                new FilterCondition(2, FilterConnector.AND, FilterOperator.EQ,
                        java.sql.Timestamp.valueOf("2026-08-30 10:30:00")),
                new FilterCondition(3, FilterConnector.AND, FilterOperator.EQ,
                        OffsetTime.of(10, 30, 0, 0, ZoneOffset.ofHours(8))),
                new FilterCondition(4, FilterConnector.AND, FilterOperator.EQ,
                        OffsetDateTime.of(2026, 8, 30, 10, 30, 0, 0, ZoneOffset.ofHours(8))));
        assertEquals(List.of(0), LocalResultFilter.visibleRowIndexes(temporal, "", conditions));
    }

    @Test
    void supportsAllOperatorsAndReturnsAnImmutableIndexList() {
        QueryResult values = QueryResult.queryWithMetadata(List.of(column(Types.VARCHAR), column(Types.INTEGER)),
                List.of(Arrays.asList("Ada", 2), Arrays.asList("Bob", 3), Arrays.asList((Object) null, null)), 1, false);
        assertEquals(List.of(0), filter(values, 0, FilterOperator.EQ, "Ada"));
        assertEquals(List.of(1), filter(values, 0, FilterOperator.NE, "Ada"));
        assertEquals(List.of(0), filter(values, 0, FilterOperator.CONTAINS, "d"));
        assertEquals(List.of(0), filter(values, 0, FilterOperator.STARTS_WITH, "A"));
        assertEquals(List.of(1), filter(values, 0, FilterOperator.ENDS_WITH, "b"));
        assertEquals(List.of(1), filter(values, 1, FilterOperator.GT, 2));
        assertEquals(List.of(0, 1), filter(values, 1, FilterOperator.GTE, 2));
        assertEquals(List.of(0), filter(values, 1, FilterOperator.LT, 3));
        assertEquals(List.of(0, 1), filter(values, 1, FilterOperator.LTE, 3));
        assertEquals(List.of(2), filter(values, 0, FilterOperator.IS_NULL, null));
        assertEquals(List.of(0, 1), filter(values, 0, FilterOperator.IS_NOT_NULL, null));
        assertThrows(UnsupportedOperationException.class,
                () -> filter(values, 0, FilterOperator.EQ, "Ada").add(99));
    }

    @Test
    void validatesAllConditionColumnsBeforeScanningAndTreatsMissingCellsAsNull() {
        QueryResult uneven = QueryResult.queryWithMetadata(List.of(column(Types.VARCHAR), column(Types.INTEGER)),
                List.of(Arrays.asList("Ada"), Arrays.asList("Bob", 1)), 1, false);
        assertEquals(List.of(0), filter(uneven, 1, FilterOperator.IS_NULL, null));
        assertThrows(IllegalArgumentException.class, () -> LocalResultFilter.visibleRowIndexes(uneven, "", List.of(
                new FilterCondition(0, FilterConnector.AND, FilterOperator.EQ, "no match"),
                new FilterCondition(2, FilterConnector.AND, FilterOperator.EQ, 1))));
        QueryResult empty = QueryResult.queryWithMetadata(List.of(column(Types.VARCHAR)), List.of(), 1, false);
        assertThrows(IllegalArgumentException.class, () -> filter(empty, 1, FilterOperator.IS_NULL, null));
    }

    @Test
    void filterConditionEnforcesItsInvariantsAndFormatterFormatsTemporalValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new FilterCondition(-1, FilterConnector.AND, FilterOperator.EQ, "x"));
        assertThrows(NullPointerException.class,
                () -> new FilterCondition(0, null, FilterOperator.EQ, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new FilterCondition(0, FilterConnector.AND, FilterOperator.EQ, null));
        assertNull(new FilterCondition(0, FilterConnector.AND, FilterOperator.IS_NULL, "ignored").value());
        assertEquals("", ResultValueFormatter.format(null));
        assertEquals("2026-08-29", ResultValueFormatter.format(LocalDate.of(2026, 8, 29)));
        assertEquals("10:11:12", ResultValueFormatter.format(LocalTime.of(10, 11, 12)));
        assertEquals("2026-08-29 10:11:12", ResultValueFormatter.format(
                LocalDateTime.of(2026, 8, 29, 10, 11, 12)));
        assertEquals("10:11:12+08:00", ResultValueFormatter.format(
                OffsetTime.of(10, 11, 12, 0, ZoneOffset.ofHours(8))));
        assertEquals("2026-08-29 10:11:12Z", ResultValueFormatter.format(
                OffsetDateTime.of(2026, 8, 29, 10, 11, 12, 0, ZoneOffset.UTC)));
    }

    @Test
    void timezoneAwareComparisonUsesTimestampInstantAndRejectsOffsetlessSqlTime() {
        TimeZone original = TimeZone.getDefault();
        try {
            java.sql.Timestamp timestamp = java.sql.Timestamp.from(
                    java.time.Instant.parse("2026-08-29T02:30:00Z"));
            QueryResult value = QueryResult.queryWithMetadata(List.of(column(Types.TIMESTAMP_WITH_TIMEZONE)),
                    List.of(List.of(timestamp)), 1, false);
            FilterCondition sameInstant = new FilterCondition(0, FilterConnector.AND, FilterOperator.EQ,
                    OffsetDateTime.parse("2026-08-29T10:30:00+08:00"));
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertEquals(List.of(0), LocalResultFilter.visibleRowIndexes(value, "", List.of(sameInstant)));
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            assertEquals(List.of(0), LocalResultFilter.visibleRowIndexes(value, "", List.of(sameInstant)));

            QueryResult offsetTime = QueryResult.queryWithMetadata(List.of(column(Types.TIME_WITH_TIMEZONE)),
                    List.of(List.of(OffsetTime.parse("10:30:00+08:00"))), 1, false);
            assertEquals(List.of(0), LocalResultFilter.visibleRowIndexes(offsetTime, "", List.of(
                    new FilterCondition(0, FilterConnector.AND, FilterOperator.EQ,
                            OffsetTime.parse("10:30:00+08:00")))));
            QueryResult sqlTime = QueryResult.queryWithMetadata(List.of(column(Types.TIME_WITH_TIMEZONE)),
                    List.of(List.of(java.sql.Time.valueOf("10:30:00"))), 1, false);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> LocalResultFilter.visibleRowIndexes(sqlTime, "", List.of(
                            new FilterCondition(0, FilterConnector.AND, FilterOperator.EQ,
                                    OffsetTime.parse("10:30:00+08:00")))));
            assertTrue(failure.getMessage().matches(".*[\\u4e00-\\u9fff].*"));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void offsetFormattingSupportsDistinctGlobalSearches() {
        QueryResult values = QueryResult.queryWithMetadata(List.of(column(Types.TIMESTAMP_WITH_TIMEZONE)),
                List.of(List.of(OffsetDateTime.parse("2026-08-29T10:11:12+08:00")),
                        List.of(OffsetDateTime.parse("2026-08-29T10:11:12Z"))), 1, false);
        assertEquals(List.of(0), LocalResultFilter.visibleRowIndexes(values, "+08:00", List.of()));
        assertEquals(List.of(1), LocalResultFilter.visibleRowIndexes(values, "z", List.of()));
    }

    @Test
    void arraysUseStableContentForDisplaySearchEqualityAndTsv() {
        byte[] first = new byte[65];
        for (int index = 0; index < first.length; index++) first[index] = (byte) index;
        byte[] differentTail = first.clone();
        differentTail[differentTail.length - 1] = 99;
        Object[] nested = {new int[]{1, 2}, new Object[]{"Ada", null}, new byte[]{4, 5}};
        QueryResult arrays = QueryResult.queryWithMetadata(List.of(
                column(Types.VARBINARY), new ResultColumn(1, "NESTED", Types.ARRAY, "ARRAY")),
                List.of(Arrays.asList(first, nested), Arrays.asList(differentTail,
                        new Object[]{new int[]{9}, new Object[]{"Lin"}})), 1, false);

        String binary = ResultValueFormatter.format(arrays.rows.getFirst().getFirst());
        String nestedText = ResultValueFormatter.format(arrays.rows.getFirst().get(1));
        assertEquals(binaryPreview(first), binary);
        assertEquals("[[1, 2], [Ada, null], 0405]", nestedText);
        assertFalse(binary.matches(".*\\[B@[0-9a-fA-F]+.*"));
        assertFalse(nestedText.matches(".*\\[[A-Z]@[0-9a-fA-F]+.*"));
        assertEquals(List.of(0, 1), LocalResultFilter.visibleRowIndexes(arrays, "000102", List.of()));
        assertEquals(List.of(0), filter(arrays, 0, FilterOperator.EQ, first.clone()),
                "binary equality must inspect bytes beyond the bounded preview");
        assertEquals(List.of(0), filter(arrays, 1, FilterOperator.EQ,
                new Object[]{new int[]{1, 2}, new Object[]{"Ada", null}, new byte[]{4, 5}}));
        assertEquals(binary + "\t" + nestedText, TsvClipboardFormatter.rows(
                List.of("BIN", "NESTED"), List.of(List.of(binary, nestedText)), Set.of(0), false));
    }

    @Test
    void nonFiniteJdbcNumbersHaveExplicitTotalOrderingWithoutParserRelaxation() {
        QueryResult numbers = QueryResult.queryWithMetadata(List.of(column(Types.DOUBLE)), List.of(
                List.of(Double.NEGATIVE_INFINITY), List.of(-1D), List.of(0D),
                List.of(Double.POSITIVE_INFINITY), List.of(Double.NaN)), 1, false);

        List<Integer> negativeInfinity = assertDoesNotThrow(
                () -> filter(numbers, 0, FilterOperator.EQ, Float.NEGATIVE_INFINITY));
        assertEquals(List.of(0), negativeInfinity);
        assertEquals(List.of(3), filter(numbers, 0, FilterOperator.EQ, Double.POSITIVE_INFINITY));
        assertEquals(List.of(4), filter(numbers, 0, FilterOperator.EQ, Float.NaN));
        assertEquals(List.of(3, 4), filter(numbers, 0, FilterOperator.GT, 0D));
        assertEquals(List.of(0, 1), filter(numbers, 0, FilterOperator.LT, 0D));
        assertEquals(List.of(4), filter(numbers, 0, FilterOperator.GT, Double.POSITIVE_INFINITY));
        assertEquals(List.of(), filter(numbers, 0, FilterOperator.LT, Double.NEGATIVE_INFINITY));

        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> FilterValueParser.parse(column(Types.DOUBLE), FilterOperator.EQ, "NaN"));
        assertTrue(invalid.getMessage().matches(".*[\\u4e00-\\u9fff].*"));
    }

    private static String binaryPreview(byte[] value) {
        StringBuilder preview = new StringBuilder();
        int displayed = Math.min(64, value.length);
        for (int index = 0; index < displayed; index++) {
            preview.append(String.format("%02x", value[index]));
        }
        if (value.length > displayed) preview.append("...(").append(value.length).append(" bytes)");
        return preview.toString();
    }

    private static ResultColumn column(int jdbcType) {
        return new ResultColumn(0, "VALUE", jdbcType, "TYPE");
    }

    private static List<Integer> filter(QueryResult result, int columnIndex,
            FilterOperator operator, Object value) {
        return LocalResultFilter.visibleRowIndexes(result, "",
                List.of(new FilterCondition(columnIndex, FilterConnector.AND, operator, value)));
    }
}
