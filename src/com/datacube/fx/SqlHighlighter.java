package com.datacube.fx;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 语法高亮：对文本做单遍正则扫描，产出 RichTextFX 的 {@link StyleSpans}。
 *
 * <p>识别顺序（同一分组内互斥）：注释 &gt; 字符串 &gt; 数字 &gt; 关键字 &gt; 括号；
 * 匹配命中的样式类名与 {@code sql-highlight.css} 中的选择器一一对应。关键字大小写不敏感，
 * 且要求词边界，避免子串误命中（如 {@code ORDER} 不会命中 {@code REORDER}）。
 */
final class SqlHighlighter {

    private SqlHighlighter() {}

    /** 与 SqlEditorPane 展示一致的常见关键字（含空格的短语拆为独立词参与匹配）。 */
    private static final String[] KEYWORDS = {
            "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET",
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "ALTER", "DROP",
            "TABLE", "VIEW", "INDEX", "SEQUENCE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL",
            "OUTER", "CROSS", "ON", "AS", "AND", "OR", "NOT", "NULL", "IS", "IN", "EXISTS",
            "BETWEEN", "LIKE", "ILIKE", "DISTINCT", "UNION", "ALL", "CASE", "WHEN", "THEN",
            "ELSE", "END", "ASC", "DESC", "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE",
            "CAST", "WITH", "RETURNING", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "DEFAULT",
            "CONSTRAINT", "UNIQUE", "CHECK", "TRUE", "FALSE", "BEGIN", "COMMIT", "ROLLBACK"
    };

    private static final String KEYWORD_PATTERN = "\\b(?:" + String.join("|", KEYWORDS) + ")\\b";
    // 单行注释 --... 与块注释 /* ... */
    private static final String COMMENT_PATTERN = "--[^\\n]*|/\\*(?:.|\\R)*?\\*/";
    // 单引号字符串（含 '' 转义），双引号标识符也着色为字符串风格
    private static final String STRING_PATTERN = "'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"";
    private static final String NUMBER_PATTERN = "\\b\\d+(?:\\.\\d+)?\\b";
    private static final String PAREN_PATTERN = "[()]";

    private static final Pattern PATTERN = Pattern.compile(
            "(?<COMMENT>" + COMMENT_PATTERN + ")"
                    + "|(?<STRING>" + STRING_PATTERN + ")"
                    + "|(?<NUMBER>" + NUMBER_PATTERN + ")"
                    + "|(?<KEYWORD>" + KEYWORD_PATTERN + ")"
                    + "|(?<PAREN>" + PAREN_PATTERN + ")",
            Pattern.CASE_INSENSITIVE);

    /**
     * 计算整段文本的样式区间。未命中的普通文本使用空样式集合，
     * 保证返回的 spans 覆盖 {@code [0, text.length())} 全域。
     */
    static StyleSpans<Collection<String>> compute(String text) {
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        if (text == null || text.isEmpty()) {
            spans.add(Collections.emptyList(), 0);
            return spans.create();
        }
        Matcher m = PATTERN.matcher(text);
        int last = 0;
        while (m.find()) {
            String cls =
                    m.group("COMMENT") != null ? "sql-comment"
                    : m.group("STRING") != null ? "sql-string"
                    : m.group("NUMBER") != null ? "sql-number"
                    : m.group("KEYWORD") != null ? "sql-keyword"
                    : m.group("PAREN") != null ? "sql-paren"
                    : null;
            if (cls == null) continue; // 理论不达；保底
            spans.add(Collections.emptyList(), m.start() - last);
            spans.add(Collections.singletonList(cls), m.end() - m.start());
            last = m.end();
        }
        spans.add(Collections.emptyList(), text.length() - last);
        return spans.create();
    }
}
