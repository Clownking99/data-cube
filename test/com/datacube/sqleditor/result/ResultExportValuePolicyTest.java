package com.datacube.sqleditor.result;

import com.datacube.spi.model.ImmutableResultValue;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import javax.sql.rowset.serial.SerialClob;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultExportValuePolicyTest {
    @Test
    void acceptsCompleteScalarsIncludingLiteralEllipsis() {
        for (Object value : Arrays.asList(null, "...", "值…（预览）", 1, 2L,
                1.25F, 2.5D, new BigDecimal("3.50"), true, LocalDate.of(2026, 8, 30))) {
            assertTrue(ResultExportValuePolicy.isCompleteScalar(value));
            assertSame(value, ResultExportValuePolicy.displayValue(value));
        }
    }

    @Test
    void rejectsSpecialUnknownAndNonFiniteValuesWithoutInspectingText() {
        List<Object> special = List.of(ImmutableResultValue.freeze(new byte[]{1, 2}),
                ImmutableResultValue.freeze(new Object[]{new byte[]{3}}),
                Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                new Object() {
                    @Override
                    public String toString() {
                        return "ordinary";
                    }
                });
        var assessment = ResultExportValuePolicy.assess(List.of(special));
        assertEquals(6, assessment.displayOnlyCells());
        assertFalse(assessment.sqlAllowed());
        for (Object value : special) {
            assertFalse(ResultExportValuePolicy.isCompleteScalar(value));
            assertInstanceOf(String.class, ResultExportValuePolicy.displayValue(value));
        }
    }

    @Test
    void boundedClobPreviewIsDisplayOnlyEvenWhenItsTextLooksOrdinary() throws Exception {
        String source = "x".repeat(700);
        QueryResult result = QueryResult.queryWithMetadata(
                List.of(new ResultColumn(0, "note", Types.CLOB, "CLOB")),
                List.of(List.of(new SerialClob(source.toCharArray()))), 1, false);
        ResultExportSnapshot snapshot = ResultExportSnapshot.capture(result, "select note",
                List.of(0), List.of(new ResultExportSnapshot.Column(0, "note")));

        Object value = snapshot.rows(ResultExportScope.CURRENT_FILTERED).getFirst().getFirst();
        assertInstanceOf(ImmutableResultValue.class, value);
        assertFalse(ResultExportValuePolicy.isCompleteScalar(value));
        assertEquals(1, ResultExportValuePolicy.assess(
                snapshot.rows(ResultExportScope.CURRENT_FILTERED)).displayOnlyCells());
        assertFalse(ResultExportValuePolicy.assess(
                snapshot.rows(ResultExportScope.CURRENT_FILTERED)).sqlAllowed());
        assertEquals(((ImmutableResultValue) value).displayText(),
                ResultExportValuePolicy.displayValue(value));
    }

}
