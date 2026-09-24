package com.datacube.sqleditor;

import java.util.List;
import java.util.Set;

/** Conservative, independent current-statement action; never falls back to the entire editor. */
public final class SqlCurrentStatement {
    public static final int MAX_CONTEXT_CHARS = 2 * 1024 * 1024;
    private static final Set<String> UNFINISHED = Set.of("SELECT", "FROM", "WHERE", "JOIN", "ON", "AS",
            "AND", "OR", "BY", "INTO", "SET", "UPDATE", "INSERT", "DELETE", "VALUES", "UNION", "WITH");
    private static final Set<String> STARTS = Set.of("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "COMMENT", "GRANT", "REVOKE", "BEGIN", "DECLARE", "DO",
            "EXPLAIN", "SET", "RESET", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE", "SHOW", "VALUES",
            "ANALYZE", "VACUUM", "CALL", "END");
    public record Resolution(SqlExecutionRange range, String description) {
        public boolean available() { return range != null; }
    }
    private SqlCurrentStatement() {}

    public static Resolution resolve(String text, int caret, boolean oracle) {
        if (text.length() > MAX_CONTEXT_CHARS) return unavailable("文本过大，请明确选中范围执行");
        if (caret < 0 || caret > text.length()) return unavailable("光标位置无效");
        if (!oracle && unsupportedAtomicBody(text)) return unavailable("BEGIN ATOMIC 函数体边界未支持，请明确选中范围执行");
        List<SqlScriptSplitter.Fragment> fragments = SqlScriptSplitter.fragments(text, oracle);
        for (var fragment : fragments) {
            boolean delimiter = fragment.terminated() && (caret == fragment.delimiterEnd() - 1
                    || caret == fragment.delimiterEnd() && (caret == text.length() || Character.isWhitespace(text.charAt(caret))));
            if (caret < fragment.start() || caret > fragment.end() && !delimiter) continue;
            String sql = fragment.text(text);
            var scan = SqlContextTokens.scan(sql, oracle);
            int local = caret - fragment.start();
            // A comment or whitespace-only location is never interpreted as a request for its neighbour.
            boolean onComment = scan.tokens().stream().anyMatch(t -> t.kind() == SqlContextTokens.Kind.COMMENT
                    && local >= t.start() && local <= t.end());
            boolean onToken = scan.tokens().stream().anyMatch(t -> t.kind() != SqlContextTokens.Kind.COMMENT
                    && local >= t.start() && local <= t.end());
            if (!delimiter && (onComment || !onToken)) return unavailable("光标位于注释或空白，请移入语句");
            if (!scan.closed() || !scan.balanced()) return unavailable("引号、注释或括号未闭合");
            if (fragment.procedural() && !fragment.terminated())
                return unavailable("PL/SQL 块需要单独成行的 / 确认边界");
            var tokens = scan.significant();
            if (tokens.isEmpty()) return unavailable("没有可执行语句");
            if (tokens.getFirst().kind() != SqlContextTokens.Kind.WORD
                    || !STARTS.contains(tokens.getFirst().text().toUpperCase(java.util.Locale.ROOT)))
                return unavailable("此类语句边界未支持，请明确选中范围执行");
            if (tokens.getFirst().is("WITH") && tokens.stream().noneMatch(t -> t.depth() == 0
                    && (t.is("SELECT") || t.is("INSERT") || t.is("UPDATE") || t.is("DELETE") || t.is("MERGE"))))
                return unavailable("WITH 后缺少主体语句");
            if (fragment.procedural()) {
                int end = tokens.size() - 1;
                if (tokens.get(end).text().equals(";")) end--;
                if (end < 0 || !(tokens.get(end).is("END") || end > 0 && tokens.get(end - 1).is("END")
                        && tokens.get(end).identifier() && !tokens.get(end).is("IF") && !tokens.get(end).is("LOOP") && !tokens.get(end).is("CASE")))
                    return unavailable("PL/SQL 块缺少完整的 END 结尾");
            }
            var last = tokens.getLast();
            if (last.kind() == SqlContextTokens.Kind.WORD && UNFINISHED.contains(last.text().toUpperCase(java.util.Locale.ROOT))
                    || last.kind() == SqlContextTokens.Kind.SYMBOL && ",.+-*/=<>|".contains(last.text()))
                return unavailable("语句尚未完整，请补齐后执行");
            int firstLine = line(text, fragment.start()), lastLine = line(text, fragment.end());
            return new Resolution(new SqlExecutionRange(fragment.start(), fragment.end(), false),
                    "当前语句：行 " + firstLine + "–" + lastLine + "（" + (fragment.end() - fragment.start()) + " 字符）");
        }
        return unavailable("光标处没有可执行语句");
    }
    private static Resolution unavailable(String reason) { return new Resolution(null, "当前语句不可执行：" + reason); }
    static boolean unsupportedAtomicBody(String text) {
        if (!text.toUpperCase(java.util.Locale.ROOT).contains("ATOMIC")) return false;
        var tokens = SqlContextTokens.scan(text, false).significant();
        for (int i = 1; i < tokens.size(); i++) if (tokens.get(i - 1).is("BEGIN") && tokens.get(i).is("ATOMIC")) return true;
        return false;
    }
    private static int line(String text, int end) {
        int line = 1;
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\r') { line++; if (i + 1 < end && text.charAt(i + 1) == '\n') i++; }
            else if (text.charAt(i) == '\n') line++;
        }
        return line;
    }
}
