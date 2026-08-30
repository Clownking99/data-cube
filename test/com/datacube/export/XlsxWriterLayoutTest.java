package com.datacube.export;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import static org.junit.jupiter.api.Assertions.*;
import static com.datacube.export.XlsxTestDocuments.*;

class XlsxWriterLayoutTest {
    @TempDir Path directory;
    private static final String SHEET = "xl/worksheets/sheet1.xml";
    private static final String CELL = "//*[local-name()='c']";

    @Test void styledPackageHasWidthsFreezeAndValidStyleReferences() throws Exception {
        Path path = directory.resolve("styled.xlsx");
        AtomicInteger feeds = new AtomicInteger();
        XlsxWriter.write(path.toFile(), List.of("姓名", "n"), sink -> {
            feeds.incrementAndGet();
            sink.row(List.of("甲", 7));
        }, new XlsxLayout(List.of(14, 12)));
        assertEquals(1, feeds.get());
        var sheet = read(path, SHEET);
        assertEquals("14", value(sheet, "//*[local-name()='col'][1]/@width"));
        assertEquals("12", value(sheet, "//*[local-name()='col'][2]/@width"));
        assertEquals("1", value(sheet, "//*[local-name()='col'][1]/@customWidth"));
        assertEquals("1", value(sheet, "//*[local-name()='col'][1]/@min"));
        assertEquals("2", value(sheet, "//*[local-name()='col'][2]/@max"));
        assertEquals("frozen", value(sheet, "//*[local-name()='pane']/@state"));
        assertEquals("1", value(sheet, "//*[local-name()='pane']/@ySplit"));
        assertEquals("A2", value(sheet, "//*[local-name()='pane']/@topLeftCell"));
        assertEquals("", value(sheet, "//*[local-name()='pane']/@xSplit"));
        assertEquals(1, count(read(path, "xl/workbook.xml"),
                "//*[local-name()='bookViews']/*[local-name()='workbookView']"));
        assertEquals("1", value(sheet, CELL + "[@r='A1']/@s"));
        assertEquals("2", value(sheet, CELL + "[@r='A2']/@s"));
        assertEquals("", value(sheet, CELL + "[@r='B2']/@s"));
        assertEquals(0, count(sheet, "//*[local-name()='row']/@ht"));
        assertEquals("cols", value(sheet,
                "local-name(//*[local-name()='sheetData']/preceding-sibling::*[1])"));
        var styles = read(path, "xl/styles.xml");
        assertEquals(3, count(styles, "//*[local-name()='cellXfs']/*"));
        assertEquals("1", value(styles,
                "//*[local-name()='cellXfs']/*[3]/*[local-name()='alignment']/@wrapText"));
        assertEquals("top", value(styles,
                "//*[local-name()='cellXfs']/*[3]/*[local-name()='alignment']/@vertical"));
        assertEquals("", value(styles,
                "//*[local-name()='cellXfs']/*[3]/*[local-name()='alignment']/@horizontal"));
        assertEquals("FFE8EEF7", value(styles, "//*[local-name()='fgColor']/@rgb"));
        assertEquals(1, count(styles, "//*[local-name()='fonts']/*[2]/*[local-name()='b']"));
        var types = read(path, "[Content_Types].xml");
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml",
                value(types, "//*[local-name()='Override'][@PartName='/xl/styles.xml']/@ContentType"));
        var rels = read(path, "xl/_rels/workbook.xml.rels");
        assertEquals("styles.xml", value(rels,
                "//*[local-name()='Relationship'][@Id='rId2']/@Target"));
        assertEquals("http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles",
                value(rels, "//*[local-name()='Relationship'][@Id='rId2']/@Type"));
    }

    @Test void layoutDoesNotChangeCellValuesTypesOrOrder() throws Exception {
        var columns = List.of("n", "flag", "text", "time", "empty", "formula", "long");
        var time = LocalDateTime.of(2026, 8, 30, 10, 20, 30, 123456789);
        List<Object> row = Arrays.asList(7, true, "甲<&\"\n乙", time, null, "=1+1", "中".repeat(500));
        Path plain = directory.resolve("plain.xlsx"), styled = directory.resolve("styled.xlsx");
        AtomicInteger feeds = new AtomicInteger();
        RowFeed feed = sink -> { feeds.incrementAndGet(); sink.row(row); };
        XlsxWriter.write(plain.toFile(), columns, feed);
        XlsxWriter.write(styled.toFile(), columns, feed, new XlsxLayout(Collections.nCopies(7, 32)));
        assertEquals(2, feeds.get());
        Document before = read(plain, SHEET), after = read(styled, SHEET);
        assertEquals(count(before, CELL), count(after, CELL));
        for (int i = 1; i <= count(before, CELL); i++) {
            String node = "(" + CELL + ")[" + i + "]";
            for (String suffix : List.of("/@r", "/@t", "/text()", "/*[local-name()='v']/text()",
                    "/*[local-name()='is']/*[local-name()='t']/text()")) {
                assertEquals(value(before, node + suffix), value(after, node + suffix));
            }
        }
        assertEquals("7", value(after, CELL + "[@r='A2']/*[local-name()='v']"));
        assertEquals("b", value(after, CELL + "[@r='B2']/@t"));
        assertEquals("1", value(after, CELL + "[@r='B2']/*[local-name()='v']"));
        assertEquals(0, count(after, CELL + "[@r='E2']"));
        assertEquals("inlineStr", value(after, CELL + "[@r='F2']/@t"));
        assertEquals(0, count(after, "//*[local-name()='f']"));
        assertEquals(time.toString(), value(after, CELL + "[@r='D2']"));
        assertEquals("中".repeat(500), value(after, CELL + "[@r='G2']"));
    }

    @Test void legacyEntryKeepsItsOriginalPartsAndPlainSheet() throws Exception {
        Path path = directory.resolve("legacy.xlsx");
        XlsxWriter.write(path.toFile(), List.of("name"), sink -> sink.row(List.of("Ada")));
        try (var zip = new ZipFile(path.toFile())) {
            assertEquals(Set.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                    "xl/_rels/workbook.xml.rels", SHEET),
                    new HashSet<>(zip.stream().map(entry -> entry.getName()).toList()));
            String sheet = new String(zip.getInputStream(zip.getEntry(SHEET)).readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                    + "name</t></is></c></row><row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is>"
                    + "<t xml:space=\"preserve\">Ada</t></is></c></row></sheetData></worksheet>", sheet);
        }
        assertEquals(1, count(read(path, "xl/_rels/workbook.xml.rels"), "//*[local-name()='Relationship']"));
        assertEquals(0, count(read(path, "[Content_Types].xml"),
                "//*[local-name()='Override'][@PartName='/xl/styles.xml']"));
        assertEquals(0, count(read(path, "xl/workbook.xml"), "//*[local-name()='bookViews']"));
    }

    @Test void invalidLayoutDoesNotOpenOutputOrConsumeRows() throws Exception {
        Path target = directory.resolve("existing.xlsx");
        Files.writeString(target, "keep");
        RowFeed feed = sink -> fail("invalid layout consumed feed");
        assertThrows(IllegalArgumentException.class, () -> XlsxWriter.write(
                target.toFile(), List.of("a", "b"), feed, new XlsxLayout(List.of(12))));
        assertThrows(NullPointerException.class, () -> XlsxWriter.write(
                target.toFile(), List.of("a"), feed, null));
        assertEquals("keep", Files.readString(target));
    }
}
