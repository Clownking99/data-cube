package com.datacube.export;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 手写最简 .xlsx 写出器（零第三方依赖）。
 *
 * <p>用 {@link ZipOutputStream} 组装 OOXML 最小骨架：{@code [Content_Types].xml}、
 * {@code _rels/.rels}、{@code xl/workbook.xml}（及其 rels）、
 * {@code xl/worksheets/sheet1.xml}。字符串用 inline string（{@code <is><t>}）避免
 * 共享字符串表；{@code Number} 写数值单元格；{@code null} 写空单元格。
 *
 * <p>流式写行：行数据经 {@link RowFeed} 逐行到达，不全量驻留内存。
 */
public final class XlsxWriter {

    private XlsxWriter() {
    }

    /**
     * 写出单 sheet 的 xlsx。
     *
     * @param out     目标文件
     * @param columns 表头列名（写在第 1 行）
     * @param feed    数据行来源
     */
    public static void write(File out, List<String> columns, RowFeed feed) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(out)))) {

            putEntry(zip, "[Content_Types].xml", contentTypes());
            putEntry(zip, "_rels/.rels", rootRels());
            putEntry(zip, "xl/workbook.xml", workbook());
            putEntry(zip, "xl/_rels/workbook.xml.rels", workbookRels());

            // sheet 需要流式写，单独处理
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            Writer w = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
            writeSheet(w, columns, feed);
            w.flush();
            zip.closeEntry();
        }
    }

    private static void writeSheet(Writer w, List<String> columns, RowFeed feed) throws Exception {
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        w.write("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        w.write("<sheetData>");

        // 第 1 行：表头
        int rowNum = 1;
        w.write("<row r=\"" + rowNum + "\">");
        for (int c = 0; c < columns.size(); c++) {
            writeInlineString(w, cellRef(c, rowNum), columns.get(c));
        }
        w.write("</row>");

        // 数据行
        int[] rowCounter = {rowNum};
        feed.forEach(values -> {
            int r = ++rowCounter[0];
            try {
                w.write("<row r=\"" + r + "\">");
                for (int c = 0; c < values.size(); c++) {
                    writeCell(w, cellRef(c, r), values.get(c));
                }
                w.write("</row>");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        w.write("</sheetData></worksheet>");
    }

    private static void writeCell(Writer w, String ref, Object value) throws IOException {
        if (value == null) {
            return; // 空单元格：不写即可
        }
        if (value instanceof Number) {
            w.write("<c r=\"" + ref + "\"><v>" + value + "</v></c>");
        } else if (value instanceof Boolean b) {
            w.write("<c r=\"" + ref + "\" t=\"b\"><v>" + (b ? 1 : 0) + "</v></c>");
        } else {
            writeInlineString(w, ref, value.toString());
        }
    }

    private static void writeInlineString(Writer w, String ref, String text) throws IOException {
        w.write("<c r=\"" + ref + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">");
        w.write(xml(text == null ? "" : text));
        w.write("</t></is></c>");
    }

    /** 列索引(0起)+行号 → A1 式引用。 */
    private static String cellRef(int col0, int row) {
        StringBuilder sb = new StringBuilder();
        int n = col0;
        do {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.append(row).toString();
    }

    private static String xml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    // 剔除 XML 1.0 非法控制字符，避免 Excel 打不开
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static void putEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "</Types>";
    }

    private static String rootRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    private static String workbook() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "</workbook>";
    }

    private static String workbookRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "</Relationships>";
    }
}
