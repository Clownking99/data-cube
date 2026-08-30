package com.datacube.export;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryXlsxLayoutEstimatorTest {
    private int width(String header, Object value) {
        return QueryXlsxLayoutEstimator.estimate(List.of(header),
                List.of(Collections.singletonList(value)), () -> {}).widths().getFirst();
    }

    @Test void measuresHeaderScalarsUnicodeAndLimits() {
        assertEquals(22, width("abcdefghijklmnopqrst", null));
        assertEquals(12, width("n", null));
        assertEquals(14, width("n", "中文列宽测试"));
        assertEquals(31, width("n", LocalDateTime.of(2026, 8, 30, 10, 20, 30, 123456789)));
        assertEquals(22, width("n", Long.MIN_VALUE));
        assertEquals(12, width("n", true));
        assertEquals(34, width("n", Double.NaN));
        assertEquals(34, width("n", Float.POSITIVE_INFINITY));
        assertEquals(12, width("n", '甲'));
        assertEquals(38, width("n", UUID.fromString("00000000-0000-0000-0000-000000000000")));
        assertEquals(60, width("n", "x".repeat(5000)));
        assertEquals(60, width("中".repeat(50), null));
        assertEquals(14, width("n", "abcd\r\nabcdefghijkl"));
        assertEquals(14, width("n", "abcd\t中文"));
        assertEquals(12, width("n", "\u0001".repeat(200)));
        assertEquals(12, width("n", "\n".repeat(256) + "中".repeat(100)));
        assertEquals(14, width("n", "\n".repeat(250) + "😀".repeat(7)));
    }

    @Test void limitsRowsWithoutReadingThe101st() {
        AtomicInteger reads = new AtomicInteger();
        List<List<Object>> rows = new AbstractList<>() {
            public int size() { return 101; }
            public List<Object> get(int index) {
                assertTrue(index < 100, "layout read beyond its sample");
                reads.incrementAndGet();
                return List.of("short");
            }
        };
        assertEquals(List.of(12), QueryXlsxLayoutEstimator.estimate(
                List.of("n"), rows, () -> {}).widths());
        assertEquals(100, reads.get());
    }

    @Test void expensiveValuesAreNotFormattedForLayout() {
        Object poison = new Object() {
            public String toString() { throw new AssertionError("unexpected formatting"); }
        };
        BigDecimal decimal = new BigDecimal("123.45") {
            public String toString() { throw new AssertionError("decimal formatted"); }
        };
        BigInteger integer = new BigInteger("123") {
            public String toString() { throw new AssertionError("integer formatted"); }
        };
        for (Object value : List.of(poison, decimal, integer, URI.create("https://example.invalid/"))) {
            assertEquals(34, width("n", value));
        }
    }

    @Test void supportsHeadersOnlyAndRaggedRowsWithoutMutatingInput() {
        assertEquals(List.of(22), QueryXlsxLayoutEstimator.estimate(
                List.of("abcdefghijklmnopqrst"), List.of(), () -> {}).widths());
        assertEquals(List.of(12, 12), QueryXlsxLayoutEstimator.estimate(
                List.of("a", "b"), List.of(List.of(1)), () -> {}).widths());
        var mutable = new ArrayList<>(List.of(12));
        var layout = new XlsxLayout(mutable);
        mutable.set(0, 60);
        assertEquals(List.of(12), layout.widths());
        assertThrows(UnsupportedOperationException.class, () -> layout.widths().add(20));
        assertThrows(IllegalArgumentException.class, () -> new XlsxLayout(List.of(11)));
        assertThrows(IllegalArgumentException.class, () -> new XlsxLayout(List.of(61)));
        assertThrows(NullPointerException.class, () -> new XlsxLayout(Arrays.asList((Integer) null)));
    }

    @Test void cancellationBetweenColumnsPreventsFurtherValueAccess() {
        var operation = new ResultExportOperation();
        AtomicInteger reads = new AtomicInteger();
        List<Object> row = new AbstractList<>() {
            public int size() { return 2; }
            public Object get(int column) {
                assertEquals(0, column);
                reads.incrementAndGet();
                operation.cancel();
                return "first";
            }
        };
        assertThrows(CancellationException.class, () -> QueryXlsxLayoutEstimator.estimate(
                List.of("a", "b"), List.of(row), operation::check));
        assertEquals(1, reads.get());
        assertThrows(CancellationException.class, () -> QueryXlsxLayoutEstimator.estimate(
                List.of(), List.of(), operation::check));
    }
}
