package com.datacube.export;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** ResultExporter CSV/HTML/XML 导出格式单元测试。 */
class ResultExporterTest {

    private static final List<String> COLS = List.of("ID", "NAME");

    private static List<List<Object>> rows(Object[]... rows) {
        return Arrays.stream(rows).map(Arrays::asList).toList();
    }

    // ---------- CSV ----------

    @Test
    void csvHasBomHeaderAndCrlf() throws Exception {
        StringWriter w = new StringWriter();
        ResultExporter.writeCsv(w, COLS, rows(new Object[]{1, "Tom"}));
        String out = w.toString();
        assertEquals('\uFEFF', out.charAt(0));
        assertEquals("ID,NAME\r\n1,Tom\r\n", out.substring(1));
    }

    @Test
    void csvQuotesSpecialsAndDoublesQuote() throws Exception {
        StringWriter w = new StringWriter();
        ResultExporter.writeCsv(w, COLS, rows(new Object[]{"a,b", "say \"hi\"\nok"}));
        String out = w.toString().substring(1);
        assertEquals("ID,NAME\r\n\"a,b\",\"say \"\"hi\"\"\nok\"\r\n", out);
    }

    @Test
    void csvNullAndShortRowAsEmptyField() throws Exception {
        StringWriter w = new StringWriter();
        ResultExporter.writeCsv(w, COLS, rows(new Object[]{null, "x"}, new Object[]{2}));
        String out = w.toString().substring(1);
        assertEquals("ID,NAME\r\n,x\r\n2,\r\n", out);
    }

    // ---------- HTML ----------

    @Test
    void htmlEscapesAndContainsStructure() throws Exception {
        StringWriter w = new StringWriter();
        ResultExporter.writeHtml(w, "查询结果", List.of("A<B"), rows(new Object[]{"x&\"y\""}));
        String out = w.toString();
        assertTrue(out.startsWith("<!DOCTYPE html>"));
        assertTrue(out.contains("<meta charset=\"utf-8\">"));
        assertTrue(out.contains("<title>查询结果</title>"));
        assertTrue(out.contains("<th>A&lt;B</th>"));
        assertTrue(out.contains("<td>x&amp;&quot;y&quot;</td>"));
    }

    @Test
    void htmlNullCellRendersEmpty() throws Exception {
        StringWriter w = new StringWriter();
        ResultExporter.writeHtml(w, null, COLS, rows(new Object[]{null, "v"}));
        assertTrue(w.toString().contains("<td></td><td>v</td>"));
    }

    // ---------- XML ----------

    @Test
    void xmlRowsetRowStructure() throws Exception {
        StringWriter w = new StringWriter();
        ResultExporter.writeXml(w, COLS, rows(new Object[]{1, "a<b"}));
        String out = w.toString();
        assertTrue(out.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(out.contains("<ROWSET>"));
        assertTrue(out.contains("<ROW>"));
        assertTrue(out.contains("<ID>1</ID>"));
        assertTrue(out.contains("<NAME>a&lt;b</NAME>"));
    }

    @Test
    void xmlSanitizesIllegalColumnNameKeepingOriginal() throws Exception {
        StringWriter w = new StringWriter();
        ResultExporter.writeXml(w, List.of("COUNT(*)", "user id"), rows(new Object[]{5, "u"}));
        String out = w.toString();
        assertTrue(out.contains("<COUNT___ name=\"COUNT(*)\">5</COUNT___>"));
        assertTrue(out.contains("<user_id name=\"user id\">u</user_id>"));
    }

    @Test
    void xmlNullColumnOmitted() throws Exception {
        StringWriter w = new StringWriter();
        ResultExporter.writeXml(w, COLS, rows(new Object[]{null, "v"}));
        String out = w.toString();
        assertFalse(out.contains("<ID>"));
        assertTrue(out.contains("<NAME>v</NAME>"));
    }

    @Test
    void xmlStripsIllegalControlChars() throws Exception {
        StringWriter w = new StringWriter();
        ResultExporter.writeXml(w, List.of("C"), rows(new Object[]{"a\u0000b\tc"}));
        assertTrue(w.toString().contains("<C>ab\tc</C>"));
    }
}
