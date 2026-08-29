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
        assertEquals(1.5F, FilterValueParser.parse(column(Types.FLOAT), FilterOperator.EQ, "1.5"));
        assertEquals(true, FilterValueParser.parse(column(Types.BOOLEAN), FilterOperator.EQ, "true"));
        assertEquals(java.sql.Date.valueOf("2026-08-29"), FilterValueParser.parse(
                column(Types.DATE), FilterOperator.EQ, "2026-08-29"));
        assertEquals(java.sql.Time.valueOf("10:11:12"), FilterValueParser.parse(
                column(Types.TIME), FilterOperator.EQ, "10:11:12"));
        assertEquals(java.sql.Timestamp.valueOf("2026-08-29 10:11:12"), FilterValueParser.parse(
                column(Types.TIMESTAMP), FilterOperator.EQ, "2026-08-29 10:11:12"));

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
