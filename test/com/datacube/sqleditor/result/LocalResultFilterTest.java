package com.datacube.sqleditor.result;

import static org.junit.jupiter.api.Assertions.*;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import java.sql.Types;
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
}
