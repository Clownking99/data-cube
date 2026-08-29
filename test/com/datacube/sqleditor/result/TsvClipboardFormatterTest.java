package com.datacube.sqleditor.result;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TsvClipboardFormatterTest {
    @Test
    void rectangleIncludesGapsAndEscapesSpreadsheetSensitiveText() {
        String tsv = TsvClipboardFormatter.rectangle(
                List.of("A", "B", "C"),
                List.of(List.of("x", "a\tb", "z"), List.of("q", "line\n2", "w")),
                Set.of(new TsvClipboardFormatter.CellRef(0, 0),
                        new TsvClipboardFormatter.CellRef(0, 2),
                        new TsvClipboardFormatter.CellRef(1, 1)),
                true);
        assertEquals("A\tB\tC\nx\t\tz\n\t\"line\n2\"\t", tsv);
    }

    @Test
    void rowsSortByIndexAndFormatNullQuotesAndTabsWithoutTrailingNewline() {
        String tsv = TsvClipboardFormatter.rows(
                List.of("A", "B"),
                List.of(java.util.Arrays.asList("plain", null), List.of("say \"hi\"", "a\tb")),
                Set.of(1, 0), false);

        assertEquals("plain\t\n\"say \"\"hi\"\"\"\t\"a\tb\"", tsv);
    }

    @Test
    void escapeQuotesEveryCarriageReturnLineEndingAndQuoteCombination() {
        String value = "q\"\r\nline\rtab\tend";

        String tsv = TsvClipboardFormatter.rows(List.of("A"), List.of(List.of(value)), Set.of(0), false);

        assertEquals("\"q\"\"\r\nline\rtab\tend\"", tsv);
    }

    @Test
    void rowsIncludeHeaderAndUseRowIndexOrderForUnorderedSelection() {
        String tsv = TsvClipboardFormatter.rows(
                List.of("A", "B"), List.of(List.of("first", "1"), List.of("second", "2")),
                Set.of(1, 0), true);

        assertEquals("A\tB\nfirst\t1\nsecond\t2", tsv);
    }

    @Test
    void emptyOrNullHeadersBehaveAsNoHeaderAndUseRaggedDataWidth() {
        List<List<String>> data = List.of(List.of("one"), List.of("two", "2"));

        assertEquals("one\t\ntwo\t2", TsvClipboardFormatter.rows(List.of(), data, Set.of(0, 1), true));
        assertEquals("one\t\ntwo\t2", TsvClipboardFormatter.rows(null, data, Set.of(0, 1), true));
        assertEquals("one\t\ntwo\t2", TsvClipboardFormatter.rows(List.of(), data, Set.of(0, 1), false));
    }

    @Test
    void nullAndEmptySelectionsProduceNoOutput() {
        List<List<String>> data = List.of(List.of("x"));

        assertEquals("", TsvClipboardFormatter.rows(List.of("A"), data, null, true));
        assertEquals("", TsvClipboardFormatter.rows(List.of("A"), data, Set.of(), true));
        assertEquals("", TsvClipboardFormatter.rectangle(List.of("A"), data, null, true));
        assertEquals("", TsvClipboardFormatter.rectangle(List.of("A"), data, Set.of(), true));
    }

    @Test
    void rectangleAndRowsRejectInvalidOrExtremeSelectionBeforeFormatting() {
        List<List<String>> data = List.of(List.of("x"));
        assertThrows(IllegalArgumentException.class,
                () -> new TsvClipboardFormatter.CellRef(-1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> TsvClipboardFormatter.rectangle(List.of("A"), data,
                        Set.of(new TsvClipboardFormatter.CellRef(1, 0)), false));
        assertThrows(IllegalArgumentException.class,
                () -> TsvClipboardFormatter.rectangle(List.of("A"), data,
                        Set.of(new TsvClipboardFormatter.CellRef(0, 1)), false));
        assertThrows(IllegalArgumentException.class,
                () -> TsvClipboardFormatter.rows(List.of("A"), data, Set.of(-1), false));
        assertThrows(IllegalArgumentException.class,
                () -> TsvClipboardFormatter.rows(List.of("A"), data, Set.of(1), false));
        assertThrows(IllegalArgumentException.class,
                () -> TsvClipboardFormatter.rows(List.of("A"), data, Set.of(Integer.MAX_VALUE), false));
    }

    @Test
    void mixedValidAndInvalidSelectionsFailInsteadOfProducingPartialOutput() {
        List<List<String>> data = List.of(List.of("x"), List.of("y"));
        Set<TsvClipboardFormatter.CellRef> cells = new HashSet<>();
        cells.add(new TsvClipboardFormatter.CellRef(0, 0));
        cells.add(new TsvClipboardFormatter.CellRef(1, 1));

        assertThrows(IllegalArgumentException.class,
                () -> TsvClipboardFormatter.rectangle(List.of("A"), data, cells, false));
        assertThrows(IllegalArgumentException.class,
                () -> TsvClipboardFormatter.rows(List.of("A"), data, Set.of(0, 2), false));
    }
}
