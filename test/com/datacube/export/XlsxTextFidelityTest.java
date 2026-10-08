package com.datacube.export;

import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.datacube.export.XlsxTestDocuments.*;

class XlsxTextFidelityTest {
    @TempDir Path directory;
    private static final String SHEET = "xl/worksheets/sheet1.xml";
    private static final String CELL = "//*[local-name()='c']";
    private static final String INVALID_MESSAGE = "XLSX text contains an unpaired UTF-16 surrogate";
    private static final Pattern XSTRING = Pattern.compile("_x([0-9A-Fa-f]{4})_");

    // Independent one-pass reader; an escaped literal is never decoded a second time.
    private static String decode(String text) {
        var matcher = XSTRING.matcher(text);
        var decoded = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(decoded, Matcher.quoteReplacement(
                String.valueOf((char) Integer.parseInt(matcher.group(1), 16))));
        matcher.appendTail(decoded);
        return decoded.toString();
    }

    @Test void readerUsesOnePassForProtectedSharedAndAdjacentEscapes() {
        assertEquals("\u0000\r\u00AF", decode("_x0000__x000D__x00aF_"));
        assertEquals("_x005F_x0000_", decode("_x005F_x005F_x005F_x0000_"));
        assertEquals("_x0000__x000D_", decode("_x005F_x0000__x005F_x000D_"));
    }

    static Stream<Arguments> textCases() {
        var samples = new ArrayList<String[]>();
        for (int c = 0; c < 32; c++) {
            if (c == '\t' || c == '\n') continue;
            samples.add(new String[]{"a" + (char) c + "b", "a" + String.format("_x%04X_", c) + "b"});
        }
        samples.addAll(List.of(
                new String[]{"a\uFFFEb", "a_xFFFE_b"},
                new String[]{"a\uFFFFb", "a_xFFFF_b"},
                new String[]{"a\rb\r\nc", "a_x000D_b_x000D_\nc"},
                new String[]{"_x0000_", "_x005F_x0000_"},
                new String[]{"_x0000__x000D_", "_x005F_x0000__x005F_x000D_"},
                new String[]{"_x005F_x0000_", "_x005F_x005F_x005F_x0000_"},
                new String[]{"_x005F_", "_x005F_x005F_"},
                new String[]{"p_x00aF__xABcd_q", "p_x005F_x00aF__x005F_xABcd_q"},
                new String[]{"_X0000_ _x000g_ _x000_ _x00000_ _x1234", "_X0000_ _x000g_ _x000_ _x00000_ _x1234"},
                new String[]{" \t\n中文<&>\"' ", " \t\n中文<&>\"' "},
                new String[]{"", ""},
                new String[]{"\u007F\u0080\u009F\uD7FF\uE000\uFFFD\uD800\uDC00\uD83D\uDE00\uDBFF\uDFFF",
                        "\u007F\u0080\u009F\uD7FF\uE000\uFFFD\uD800\uDC00\uD83D\uDE00\uDBFF\uDFFF"}));
        return samples.stream().flatMap(sample -> Stream.of(false, true)
                .map(styled -> Arguments.of(sample[0], sample[1], styled)));
    }

    @ParameterizedTest @MethodSource("textCases")
    void headersAndCellsUseKnownXstringMappingAndRoundTrip(String input, String expectedRaw,
                                                         boolean styled) throws Exception {
        Path target = directory.resolve("case-text.xlsx");
        RowFeed feed = sink -> sink.row(List.of(input));
        if (styled) XlsxWriter.write(target.toFile(), List.of(input), feed, new XlsxLayout(List.of(12)));
        else XlsxWriter.write(target.toFile(), List.of(input), feed);
        var sheet = read(target, SHEET);
        assertEquals(2, count(sheet, CELL));
        for (String ref : List.of("A1", "A2")) {
            String cell = CELL + "[@r='" + ref + "']";
            String text = value(sheet, cell + "/*[local-name()='is']/*[local-name()='t']");
            assertEquals(expectedRaw, text, "raw mapping " + ref);
            assertEquals(input, decode(text), "round trip " + ref);
            assertEquals("inlineStr", value(sheet, cell + "/@t"));
            assertEquals("preserve", value(sheet,
                    cell + "/*[local-name()='is']/*[local-name()='t']/@*[local-name()='space']"));
            assertEquals(styled ? (ref.equals("A1") ? "1" : "2") : "", value(sheet, cell + "/@s"));
        }
        try (var zip = new ZipFile(target.toFile())) {
            assertEquals(styled, zip.getEntry("xl/styles.xml") != null);
        }
    }

    static Stream<Arguments> unpairedCases() {
        return Stream.of("\uD800tail", "head\uD800tail", "head\uD800", "\uDC00tail", "head\uDC00tail",
                "head\uDC00", "\uD800\uD800", "\uDC00\uDC00", "\uDC00\uD800",
                "\uD800\uDC00\uD800", "\uDC00\uD800\uDC00")
                .flatMap(text -> Stream.of(false, true).flatMap(styled -> Stream.of(false, true)
                        .map(header -> Arguments.of(text, styled, header))));
    }

    @ParameterizedTest @MethodSource("unpairedCases")
    void unpairedUtf16FailsWithTypedFixedReasonInHeadersAndCells(String invalid, boolean styled,
                                                               boolean header) {
        var columns = List.of(header ? invalid : "C");
        RowFeed feed = sink -> sink.row(List.of(header ? "VALUE" : invalid));
        Path target = directory.resolve("case-invalid.xlsx");
        IOException failure = assertThrows(IOException.class, () -> {
            if (styled) XlsxWriter.write(target.toFile(), columns, feed, new XlsxLayout(List.of(12)));
            else XlsxWriter.write(target.toFile(), columns, feed);
        });
        assertEquals("com.datacube.export.XlsxWriter$InvalidTextCharacterException", failure.getClass().getName());
        assertEquals(INVALID_MESSAGE, failure.getMessage());
        assertNull(failure.getCause());
    }

    private ResultExportSnapshot snapshot(String header, String value) {
        return ResultExportSnapshot.capture(QueryResult.query(List.of(header), List.of(List.of(value)), 1),
                "select synthetic_value", List.of(0), List.of(new ResultExportSnapshot.Column(0, header)));
    }

    private void query(Path temporary, ResultExportOperation operation, String header, String value) throws Exception {
        QueryResultFileWriter.write(temporary, QueryResultFileWriter.Format.XLSX, snapshot(header, value),
                ResultExportScope.CURRENT_FILTERED, false, null, operation);
    }

    static Stream<Arguments> publisherCases() {
        return Stream.of("SYNTHETIC_SECRET\uD800END", "SYNTHETIC_SECRET\uDC00END", "SYNTHETIC_SECRET\uDC00\uD800END")
                .flatMap(text -> Stream.of(false, true).flatMap(existed -> Stream.of(false, true)
                        .map(header -> Arguments.of(text, existed, header))));
    }

    @ParameterizedTest @MethodSource("publisherCases")
    void realQueryPublisherPreservesOriginalAndUnrelatedBytesCleansAndRetries(String invalid,
                                                                            boolean existed, boolean header) throws Exception {
        Path target = directory.resolve("case-published.xlsx");
        byte[] old = {0, 2, 7, (byte) 0xFF};
        byte[] unrelated = {9, (byte) 0xFD, 3, 0};
        Path neighbor = Files.write(directory.resolve("case-unrelated.bin"), unrelated);
        if (existed) Files.write(target, old);
        var operation = new ResultExportOperation();
        var publisher = new SafeResultFilePublisher();
        var captured = SafeResultFilePublisher.capture(target);
        var failure = assertThrows(SafeResultFilePublisher.Failure.class,
                () -> publisher.publish(captured, operation, (temporary, token) -> query(temporary, token,
                        header ? invalid : "C", header ? "VALUE" : invalid)));
        assertEquals(SafeResultFilePublisher.Stage.WRITE, failure.stage());
        assertEquals("Result export failed at WRITE", failure.getMessage());
        assertNull(failure.getCause());
        assertNull(failure.temporaryPath());
        assertFalse(operation.published());
        if (existed) assertArrayEquals(old, Files.readAllBytes(target));
        else assertFalse(Files.exists(target));
        assertArrayEquals(unrelated, Files.readAllBytes(neighbor));
        try (var paths = Files.list(directory)) {
            assertEquals(existed ? Set.of(target, neighbor) : Set.of(neighbor), paths.collect(Collectors.toSet()));
        }
        var retry = new ResultExportOperation();
        String text = "RETRY\u0000\r_x0000_\uD83D\uDE00";
        publisher.publish(captured, retry, (temporary, token) -> query(temporary, token, text, text));
        assertTrue(retry.published());
        var sheet = read(target, SHEET);
        for (String ref : List.of("A1", "A2")) {
            assertEquals(text, decode(value(sheet, CELL + "[@r='" + ref + "']")));
        }
        assertArrayEquals(unrelated, Files.readAllBytes(neighbor));
        try (var paths = Files.list(directory)) { assertEquals(Set.of(target, neighbor), paths.collect(Collectors.toSet())); }
    }
}
