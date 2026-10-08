package com.datacube.export;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * 查询结果多格式文本导出器（CSV / HTML / XML），零第三方依赖。
 *
 * <p>与 {@link XlsxWriter} 同风格：纯文本写出、与 UI/数据库完全解耦，便于单测。
 * SQL(INSERT) 格式由 {@code InsertSqlGenerator} 负责，Excel 由 {@link XlsxWriter}
 * 负责，本类不重复。
 *
 * <p>统一约定：{@code null} 单元格 CSV 写空字段、HTML 写空单元格、XML 省略元素；
 * 其余值一律 {@code toString()} 后按格式转义。
 */
public final class ResultExporter {

    private ResultExporter() {
    }

    // ---------- CSV ----------

    /**
     * RFC 4180 风格 CSV：首字符写 UTF-8 BOM（Excel 双击打开不乱码），
     * 行尾 CRLF；含逗号/双引号/换行的字段以双引号包裹、内部 {@code "} 双写。
     */
    public static void writeCsv(Writer w, List<String> columns, List<List<Object>> rows) throws IOException {
        w.write('\uFEFF');
        writeCsvRow(w, columns, columns.size());
        for (List<Object> row : rows) {
            writeCsvRow(w, row, columns.size());
        }
    }

    private static void writeCsvRow(Writer w, List<?> cells, int width) throws IOException {
        for (int i = 0; i < width; i++) {
            if (i > 0) w.write(',');
            Object v = i < cells.size() ? cells.get(i) : null;
            if (v != null) w.write(csvField(v.toString()));
        }
        w.write("\r\n");
    }

    private static String csvField(String s) {
        boolean needQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needQuote) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    // ---------- HTML ----------

    /**
     * 完整 HTML 文档：内联基础样式（边框、斑马纹、表头加深），浏览器直接打开
     * 可预览/打印。所有文本经 HTML 转义。
     */
    public static void writeHtml(Writer w, String title, List<String> columns, List<List<Object>> rows)
            throws IOException {
        String t = html(title == null ? "" : title);
        w.write("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<title>" + t + "</title>\n");
        w.write("""
                <style>
                body { font-family: "Microsoft YaHei", "Segoe UI", sans-serif; font-size: 13px; margin: 16px; }
                table { border-collapse: collapse; }
                th, td { border: 1px solid #b8b8b8; padding: 4px 10px; text-align: left; white-space: pre-wrap; }
                th { background: #dde5ee; }
                tr:nth-child(even) td { background: #f4f6f8; }
                </style>
                """);
        w.write("</head>\n<body>\n<h3>" + t + "</h3>\n<table>\n<thead>\n<tr>");
        for (String c : columns) {
            w.write("<th>" + html(c) + "</th>");
        }
        w.write("</tr>\n</thead>\n<tbody>\n");
        for (List<Object> row : rows) {
            w.write("<tr>");
            for (int i = 0; i < columns.size(); i++) {
                Object v = i < row.size() ? row.get(i) : null;
                w.write("<td>" + (v == null ? "" : html(v.toString())) + "</td>");
            }
            w.write("</tr>\n");
        }
        w.write("</tbody>\n</table>\n</body>\n</html>\n");
    }

    private static String html(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------- XML ----------

    /** XML 1.0 cannot represent these characters, including unpaired UTF-16 surrogates. */
    public static final class InvalidXmlCharacterException extends IOException {
        private InvalidXmlCharacterException() { super("XML contains an unrepresentable character"); }
    }

    /**
     * PL/SQL Developer 风格：{@code <ROWSET><ROW><列名>值</列名></ROW></ROWSET>}。
     * 列名含 XML 非法字符时净化为 {@code _}（净化后与原名不同则以 {@code name}
     * 属性保留原名）；{@code null} 列省略元素。
     */

    public static void writeXml(Writer w, List<String> columns, List<List<Object>> rows) throws IOException {
        org.w3c.dom.Document names = nameValidator();
        String[] tags = new String[columns.size()];
        for (int i = 0; i < columns.size(); i++) tags[i] = xmlName(columns.get(i), names);
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ROWSET>\n");
        for (List<Object> row : rows) {
            w.write(" <ROW>\n");
            for (int i = 0; i < columns.size(); i++) {
                Object v = i < row.size() ? row.get(i) : null;
                if (v == null) continue;
                String tag = tags[i];
                String open = tag.equals(columns.get(i))
                        ? "<" + tag + ">"
                        : "<" + tag + " name=\"" + xml(columns.get(i), true) + "\">";
                w.write("  " + open + xml(v.toString(), false) + "</" + tag + ">\n");
            }
            w.write(" </ROW>\n");
        }
        w.write("</ROWSET>\n");
    }

    /** Sanitizes markup names while the name attribute preserves the complete original label. */
    private static String xmlName(String name, org.w3c.dom.Document names) throws InvalidXmlCharacterException {
        if (name == null || name.isEmpty()) return "COLUMN";
        for (int offset = 0; offset < name.length();) {
            int code = name.codePointAt(offset);
            requireXmlCharacter(code);
            offset += Character.charCount(code);
        }
        // Preserve the existing char-based tag mapping, restricted to legal XML names.
        StringBuilder result = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char code = name.charAt(index);
            boolean allowed = index == 0
                    ? (Character.isLetter(code) || code == '_') && nameStart(code)
                    : (Character.isLetterOrDigit(code) || code == '_' || code == '-' || code == '.')
                            && namePart(code);
            result.append(allowed ? code : '_');
        }
        String tag = result.toString();
        if (acceptedName(names, tag)) return tag;
        // The JDK reader accepts fewer names than XML 1.0 fifth edition. Keep every
        // accepted original tag and replace only characters rejected by the same JDK DOM.
        for (int index = 0; index < tag.length(); index++) {
            if (!acceptedName(names, (index == 0 ? "" : "C") + tag.charAt(index)))
                result.setCharAt(index, '_');
        }
        return result.toString();
    }

    private static org.w3c.dom.Document nameValidator() throws IOException {
        try {
            // No parsing, custom providers, external entities, or shared mutable document.
            return javax.xml.parsers.DocumentBuilderFactory.newDefaultInstance()
                    .newDocumentBuilder().newDocument();
        } catch (javax.xml.parsers.ParserConfigurationException unavailable) {
            throw new IOException("XML name validation unavailable");
        }
    }

    private static boolean acceptedName(org.w3c.dom.Document names, String tag) {
        try {
            names.createElement(tag);
            return true;
        } catch (org.w3c.dom.DOMException invalid) {
            if (invalid.code != org.w3c.dom.DOMException.INVALID_CHARACTER_ERR) throw invalid;
            return false;
        }
    }

    private static boolean nameStart(int code) {
        // Keep names namespace-neutral; colons are represented through the name attribute.
        return code == '_' || code >= 'A' && code <= 'Z' || code >= 'a' && code <= 'z'
                || code >= 0xC0 && code <= 0xD6 || code >= 0xD8 && code <= 0xF6
                || code >= 0xF8 && code <= 0x2FF || code >= 0x370 && code <= 0x37D
                || code >= 0x37F && code <= 0x1FFF || code >= 0x200C && code <= 0x200D
                || code >= 0x2070 && code <= 0x218F || code >= 0x2C00 && code <= 0x2FEF
                || code >= 0x3001 && code <= 0xD7FF || code >= 0xF900 && code <= 0xFDCF
                || code >= 0xFDF0 && code <= 0xFFFD || code >= 0x10000 && code <= 0xEFFFF;
    }

    private static boolean namePart(int code) {
        return nameStart(code) || code == '-' || code == '.' || code >= '0' && code <= '9'
                || code == 0xB7 || code >= 0x300 && code <= 0x36F || code >= 0x203F && code <= 0x2040;
    }

    private static void requireXmlCharacter(int code) throws InvalidXmlCharacterException {
        if (!(code == 9 || code == 10 || code == 13 || code >= 0x20 && code <= 0xD7FF
                || code >= 0xE000 && code <= 0xFFFD || code >= 0x10000 && code <= 0x10FFFF))
            throw new InvalidXmlCharacterException();
    }

    private static String xml(String value, boolean attribute) throws InvalidXmlCharacterException {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int offset = 0; offset < value.length();) {
            int code = value.codePointAt(offset);
            requireXmlCharacter(code);
            switch (code) {
                case '&' -> result.append("&amp;");
                case '<' -> result.append("&lt;");
                case '>' -> result.append("&gt;");
                case '"' -> result.append("&quot;");
                case '\r' -> result.append("&#13;");
                case '\t' -> result.append(attribute ? "&#9;" : "\t");
                case '\n' -> result.append(attribute ? "&#10;" : "\n");
                default -> result.appendCodePoint(code);
            }
            offset += Character.charCount(code);
        }
        return result.toString();
    }
}
