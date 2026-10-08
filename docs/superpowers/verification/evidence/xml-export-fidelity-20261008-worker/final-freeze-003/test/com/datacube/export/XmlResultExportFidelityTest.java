package com.datacube.export;

import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import static org.junit.jupiter.api.Assertions.*;

class XmlResultExportFidelityTest {
    @TempDir Path directory;

    private ResultExportSnapshot snapshot(String column, Object value) {
        return ResultExportSnapshot.capture(QueryResult.query(List.of(column),
                List.of(Collections.singletonList(value)), 1), "select synthetic_value", List.of(0),
                List.of(new ResultExportSnapshot.Column(0, column)));
    }

    private void write(Path temporary, ResultExportOperation operation, String column, Object value)
            throws Exception {
        QueryResultFileWriter.write(temporary, QueryResultFileWriter.Format.XML,
                snapshot(column, value), ResultExportScope.CURRENT_FILTERED, false, null, operation);
    }

    private Element cell(Path target) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var parser = factory.newDocumentBuilder();
        parser.setErrorHandler(new DefaultHandler() {
            @Override public void fatalError(SAXParseException failure) throws SAXParseException {
                throw failure;
            }
        });
        var document = parser.parse(target.toFile());
        assertEquals("ROWSET", document.getDocumentElement().getTagName());
        var children = document.getElementsByTagName("ROW").item(0).getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element) return element;
        }
        return null;
    }

    private Element publish(String column, Object value) throws Exception {
        Path target = directory.resolve("result.xml");
        var operation = new ResultExportOperation();
        new SafeResultFilePublisher().publish(SafeResultFilePublisher.capture(target), operation,
                (temporary, token) -> write(temporary, token, column, value));
        assertTrue(operation.published());
        return cell(target);
    }

    static Stream<Arguments> validValues() {
        return Stream.of("中文<&\"\t\n", "a\rb\r\nc", "\u0020\u007F\u0085\uD7FF\uE000\uFFFD",
                "\uD800\uDC00\uD83D\uDE00\uDBFF\uDFFF").map(Arguments::of);
    }

    @ParameterizedTest @MethodSource("validValues")
    void validUnicodeAndTextLineEndsRoundTrip(String value) throws Exception {
        assertEquals(value, publish("C", value).getTextContent());
    }

    static Stream<Arguments> columnNames() {
        return Stream.of("a\t\n\rb", "\u00AA", "COUNT(*)", "", "\uD83D\uDE00",
                "a\u0301\u00B7b").map(Arguments::of);
    }

    @ParameterizedTest @MethodSource("columnNames")
    void columnNameRoundTripsThroughTagOrNameAttribute(String name) throws Exception {
        Element cell = publish(name, "VALUE");
        assertEquals(name, cell.hasAttribute("name") ? cell.getAttribute("name") : cell.getTagName());
        assertEquals("VALUE", cell.getTextContent());
        if (name.equals("COUNT(*)")) assertEquals("COUNT___", cell.getTagName());
        if (name.equals("\uD83D\uDE00")) assertEquals("__", cell.getTagName());
        if (name.equals("a\u0301\u00B7b")) assertEquals("a__b", cell.getTagName());
        if (name.equals("\u00AA")) assertEquals("_", cell.getTagName());
    }

    static Stream<Arguments> invalidCharacters() {
        return Stream.concat(java.util.stream.IntStream.range(0, 32)
                .filter(code -> code != 9 && code != 10 && code != 13).boxed(),
                Stream.of(0xFFFE, 0xFFFF, 0xD800, 0xDBFF, 0xDC00, 0xDFFF))
                .flatMap(code -> Stream.of(Arguments.of(code, false), Arguments.of(code, true)));
    }

    @ParameterizedTest(name="code={0}, column={1}") @MethodSource("invalidCharacters")
    void unrepresentableValueOrColumnKeepsOldTargetCleansOwnedTempAndAllowsRetry(
            int code, boolean column) throws Exception {
        String invalid = "SYNTHETIC_SECRET" + (char) code + "END";
        String name = column ? invalid : "C";
        String value = column ? "VALUE" : invalid;
        Path target = Files.writeString(directory.resolve("result.xml"), "OLD_BYTES");
        Path unrelated = Files.writeString(directory.resolve("unrelated.tmp"), "KEEP");
        var temporary = new AtomicReference<Path>();
        var operation = new ResultExportOperation();
        var failure = assertThrows(SafeResultFilePublisher.Failure.class, () ->
                new SafeResultFilePublisher().publish(SafeResultFilePublisher.capture(target), operation,
                        (path, token) -> { temporary.set(path); write(path, token, name, value); }));
        assertAll(() -> assertEquals("XML_CHARACTER", failure.stage().name()),
                () -> assertEquals("Result export failed at XML_CHARACTER", failure.getMessage()),
                () -> assertNull(failure.getCause()), () -> assertNull(failure.temporaryPath()),
                () -> assertFalse(operation.published()),
                () -> assertEquals("OLD_BYTES", Files.readString(target)),
                () -> assertEquals("KEEP", Files.readString(unrelated)),
                () -> assertNotNull(temporary.get()), () -> assertFalse(Files.exists(temporary.get())));
        var retry = new ResultExportOperation();
        new SafeResultFilePublisher().publish(SafeResultFilePublisher.capture(target), retry,
                (path, token) -> write(path, token, "C", "RETRY"));
        assertTrue(retry.published());
        assertEquals("RETRY", cell(target).getTextContent());
        try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
    }

    @Test void nullValueStillOmitsItsElement() throws Exception {
        assertNull(publish("NULL_COLUMN", null));
    }
}
