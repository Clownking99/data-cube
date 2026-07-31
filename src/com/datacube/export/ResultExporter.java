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

    /**
     * PL/SQL Developer 风格：{@code <ROWSET><ROW><列名>值</列名></ROW></ROWSET>}。
     * 列名含 XML 非法字符时净化为 {@code _}（净化后与原名不同则以 {@code name}
     * 属性保留原名）；{@code null} 列省略元素。
     */
    public static void writeXml(Writer w, List<String> columns, List<List<Object>> rows) throws IOException {
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ROWSET>\n");
        String[] tags = new String[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            tags[i] = xmlName(columns.get(i));
        }
        for (List<Object> row : rows) {
            w.write(" <ROW>\n");
            for (int i = 0; i < columns.size(); i++) {
                Object v = i < row.size() ? row.get(i) : null;
                if (v == null) continue;
                String tag = tags[i];
                String open = tag.equals(columns.get(i))
                        ? "<" + tag + ">"
                        : "<" + tag + " name=\"" + xml(columns.get(i)) + "\">";
                w.write("  " + open + xml(v.toString()) + "</" + tag + ">\n");
            }
            w.write(" </ROW>\n");
        }
        w.write("</ROWSET>\n");
    }

    /** 列名 → 合法 XML 元素名：非法字符替换为 {@code _}，首字符非法时加前缀 {@code C}。 */
    private static String xmlName(String name) {
        if (name == null || name.isEmpty()) return "COLUMN";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = i == 0
                    ? (Character.isLetter(c) || c == '_')
                    : (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.');
            sb.append(ok ? c : '_');
        }
        // 首字符被替换成 _ 之外的非法形态（如数字开头）时统一加前缀
        char first = sb.charAt(0);
        if (!Character.isLetter(first) && first != '_') sb.insert(0, 'C');
        return sb.toString();
    }

    private static String xml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    // 剔除 XML 1.0 非法控制字符
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
