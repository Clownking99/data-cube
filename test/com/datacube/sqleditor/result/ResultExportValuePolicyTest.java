package com.datacube.sqleditor.result;

import com.datacube.spi.model.ImmutableResultValue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
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
                new BigDecimal("3.50"), true, LocalDate.of(2026, 8, 30))) {
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

}
