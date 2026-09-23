package com.datacube.sqleditor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 美化器：基于词法分词的整形排版，输出多行、带缩进的清晰格式。
 *
 * <p>设计要点：
 * <ul>
 *   <li>基于词法调整空白与关键字大小写，不替代完整的方言语法解析；</li>
 *   <li>字符串字面量 {@code '...'}、双引号标识符 {@code "..."}、行注释 {@code --} 与
 *       块注释 {@code /*..*} 均作为整体保留，不会在其内部插入换行；</li>
 *   <li>采用 PL/SQL Developer 风格的“河道”对齐：SELECT / FROM / WHERE / GROUP BY /
 *       ORDER BY / JOIN 等主子句的前导关键字<b>右对齐</b>到同一列（= "SELECT" 宽度），
 *       视觉上形成一条竖直的对齐“河道”；</li>
 *   <li>SELECT / SET 列表逐列换行，续行左对齐到河道右侧一格；</li>
 *   <li>JOIN 子句独立成行，{@code ON} 另起一行并右对齐；</li>
 *   <li>WHERE / HAVING / ON 中的 AND / OR 右对齐到河道另起一行；</li>
 *   <li>子查询括号（{@code (} 后紧跟 SELECT / WITH）内部另起一行并逐层缩进，
 *       形成多层嵌套的可读排版；其余普通括号（函数参数、IN 列表、VALUES 元组）
 *       保持行内排版，避免破坏函数调用可读性；</li>
 *   <li>关键字统一大写；多语句以分号分隔并各自成段。</li>
 * </ul>
 *
 * <p>无 JavaFX 依赖，可被 CLI 复用。
 */
public final class SqlFormatter {

    private SqlFormatter() {}

    /** PL/SQL Developer 风格的对齐列宽：主子句前导关键字右对齐至此列（= "SELECT" 长度）。 */
    private static final int RIVER = 6;

    /** 与编辑器结果上限一致；先限制生成缓冲区，避免缩进膨胀后才检查结果。 */
    private static final int MAX_OUTPUT_LENGTH = 8 * 1024 * 1024;

    /** 需大写的关键字集合（大小写不敏感匹配）。 */
    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET",
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "MERGE", "CREATE", "ALTER", "DROP",
            "TABLE", "VIEW", "INDEX", "SEQUENCE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL",
            "OUTER", "CROSS", "ON", "AS", "AND", "OR", "NOT", "NULL", "IS", "IN", "EXISTS",
            "BETWEEN", "LIKE", "ILIKE", "DISTINCT", "UNION", "INTERSECT", "EXCEPT", "MINUS",
            "ALL", "ANY", "SOME", "CASE", "WHEN", "THEN", "ELSE", "END", "ASC", "DESC",
            "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "CAST", "WITH", "RETURNING",
            "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "DEFAULT", "CONSTRAINT", "UNIQUE",
            "CHECK", "TRUE", "FALSE", "USING", "OVER", "PARTITION", "RECURSIVE", "FETCH",
            "FIRST", "NEXT", "ONLY", "NULLS", "LAST", "FILTER", "MATERIALIZED", "LATERAL",
            "CONNECT", "START", "PRIOR", "NOCYCLE", "SEARCH", "CYCLE", "SIBLINGS", "CASCADE",
            "RESTRICT", "ACTION", "DEFERRABLE", "INITIALLY", "IMMEDIATE", "DEFERRED", "ENABLE",
            "DISABLE", "VALIDATE", "NOVALIDATE", "LEVEL", "ROWNUM",
            "ROWS", "RANGE", "GROUPS", "EXCLUDE", "UNBOUNDED", "PRECEDING", "FOLLOWING",
            "CURRENT", "ROW", "TIES", "NO", "OTHERS", "ARRAY");

    /** 触发另起一行（顶层子句）的关键字。 */
    private static final Set<String> LINE_STARTERS = Set.of(
            "WHERE", "HAVING", "LIMIT", "OFFSET",
            "UNION", "INTERSECT", "EXCEPT", "MINUS", "VALUES", "RETURNING", "FETCH");

    /** 声明对象后紧随的列/参数列表，使用关键字与列表之间的可读空格。 */
    private static final Set<String> DECLARATION_PREFIXES = Set.of(
            "TABLE", "VIEW", "INDEX", "SEQUENCE", "TYPE", "FUNCTION", "PROCEDURE",
            "TRIGGER", "PACKAGE", "ON", "JOIN", "REFERENCES");

    /** JOIN 短语的引导词（其后的 OUTER / JOIN 续接同一行）。 */
    private static final Set<String> JOIN_LEAD = Set.of("INNER", "LEFT", "RIGHT", "FULL", "CROSS");

    /**
     * 美化 SQL 脚本；引用/注释未闭合或数值 token 边界不明确时整段保留原文。
     * @throws IllegalArgumentException 生成缓冲区超过 8 Mi UTF-16 单元，不返回部分结果
     */
    public static String format(String sql) {
        if (sql == null || sql.isBlank()) return sql;
        List<String> tokens = tokenize(sql);
        if (tokens == null || tokens.isEmpty()) return sql;
        return new Renderer(tokens).render();
    }

    // ---------------------------------------------------------------- 分词

    /** 返回 null 表示词法边界不完整或不明确，调用方应保留整段原文。 */
    private static List<String> tokenize(String sql) {
        List<String> out = new ArrayList<>();
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            // 行注释 --...
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int j = i + 2;
                while (j < n && sql.charAt(j) != '\n' && sql.charAt(j) != '\r') j++;
                out.add(sql.substring(i, j));
                i = j;
                continue;
            }
            // 块注释 /* ... */
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int j = readBlockComment(sql, i);
                if (j < 0) return null;
                out.add(sql.substring(i, j));
                i = j;
                continue;
            }
            // PostgreSQL dollar-quoted strings: keep the body opaque, including semicolons
            // and SQL-looking words. A missing terminator leaves the whole input untouched.
            if (c == '$') {
                int j = readDollarQuoted(sql, i);
                if (j < 0) return null;
                if (j > i) {
                    out.add(sql.substring(i, j));
                    i = j;
                    continue;
                }
            }
            // Oracle q / nq literals: paired or repeated user-selected delimiters.
            int qStart = i;
            if ((c == 'n' || c == 'N') && i + 2 < n
                    && (sql.charAt(i + 1) == 'q' || sql.charAt(i + 1) == 'Q')) qStart++;
            char q = sql.charAt(qStart);
            if ((q == 'q' || q == 'Q') && qStart + 1 < n && sql.charAt(qStart + 1) == '\'') {
                int j = readOracleQuoted(sql, qStart);
                if (j < 0) return null;
                out.add(sql.substring(i, j));
                i = j;
                continue;
            }
            // Keep literal prefixes attached. Only explicit E strings enable backslash escapes;
            // ordinary strings use standard doubled quotes, not session-dependent settings.
            if ("eEnNbBxX".indexOf(c) >= 0 && i + 1 < n && sql.charAt(i + 1) == '\'') {
                int j = readStringLiteral(sql, i + 1, c == 'e' || c == 'E');
                if (j < 0) return null;
                out.add(sql.substring(i, j));
                i = j;
                continue;
            }
            if ((c == 'u' || c == 'U') && i + 2 < n && sql.charAt(i + 1) == '&'
                    && (sql.charAt(i + 2) == '\'' || sql.charAt(i + 2) == '"')) {
                int j = sql.charAt(i + 2) == '\''
                        ? readStringLiteral(sql, i + 2, false) : readQuoted(sql, i + 2, '"');
                if (j < 0) return null;
                out.add(sql.substring(i, j));
                i = j;
                continue;
            }
            // 单引号字符串（'' 转义）
            if (c == '\'') {
                int j = readStringLiteral(sql, i, false);
                if (j < 0) return null;
                out.add(sql.substring(i, j));
                i = j;
                continue;
            }
            // 双引号标识符（"" 转义）
            if (c == '"') {
                int j = readQuoted(sql, i, '"');
                if (j < 0) return null;
                out.add(sql.substring(i, j));
                i = j;
                continue;
            }
            if (c == '`') {
                int j = readQuoted(sql, i, '`');
                if (j < 0) return null;
                out.add(sql.substring(i, j));
                i = j;
                continue;
            }
            // Bind variables are one token so Oracle :name / :1 never becomes ": name".
            if (c == ':' && i + 1 < n && sql.charAt(i + 1) != ':') {
                int j = i + 1;
                if (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_') {
                    while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) j++;
                    out.add(sql.substring(i, j));
                    i = j;
                    continue;
                }
            }
            // Preserve complete symbol runs, including extension operators and named arguments.
            int operatorEnd = appendOperatorTokens(sql, i, out);
            if (operatorEnd > i) {
                i = operatorEnd;
                continue;
            }
            // Numeric literals are opaque: exponent signs and leading dots must not
            // become standalone operators/punctuation. Never parse or round their value.
            if (isRadixDigit(c, 10) || (c == '.' && i + 1 < n && isRadixDigit(sql.charAt(i + 1), 10))) {
                int j = readNumber(sql, i);
                if (j < 0) return null;
                out.add(sql.substring(i, j));
                i = j;
                continue;
            }
            // 标识符 / 关键字（含 . 以保留 a.b；名称里的数字不启动数值分词）
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                int j = i;
                while (j < n) {
                    char d = sql.charAt(j);
                    if (Character.isLetterOrDigit(d) || d == '_' || d == '$' || d == '.') j++;
                    else break;
                }
                out.add(sql.substring(i, j));
                i = j;
                continue;
            }
            // 单字符符号
            out.add(String.valueOf(c));
            i++;
        }
        return out;
    }

    /** 数值原文结束位置；残缺指数/分组或紧贴未知标识符时返回 -1，避免将其悄悄改写。 */
    private static int readNumber(String sql, int start) {
        int n = sql.length();
        int radix = 10;
        if (sql.charAt(start) == '0' && start + 1 < n) {
            radix = switch (sql.charAt(start + 1)) {
                case 'x', 'X' -> 16;
                case 'o', 'O' -> 8;
                case 'b', 'B' -> 2;
                default -> 10;
            };
        }
        int j;
        if (radix != 10) {
            int digitsStart = start + 2;
            // PostgreSQL permits one underscore immediately after a radix prefix.
            if (digitsStart < n && sql.charAt(digitsStart) == '_') digitsStart++;
            j = readDigits(sql, digitsStart, radix);
            if (j <= digitsStart) return -1;
        } else {
            j = readDigits(sql, start, 10);
            if (j < 0) return -1;
            if (j < n && sql.charAt(j) == '.' && (j + 1 >= n || sql.charAt(j + 1) != '.')) {
                j = readDigits(sql, j + 1, 10);
                if (j < 0) return -1;
            }
            if (j < n && (sql.charAt(j) == 'e' || sql.charAt(j) == 'E')) {
                int digitsStart = j + 1;
                if (digitsStart < n && (sql.charAt(digitsStart) == '+' || sql.charAt(digitsStart) == '-')) {
                    digitsStart++;
                }
                j = readDigits(sql, digitsStart, 10);
                if (j <= digitsStart) return -1;
            }
            // Oracle binary-float / binary-double suffix; retain the original case.
            if (j < n && "fFdD".indexOf(sql.charAt(j)) >= 0) j++;
        }
        if (j < n) {
            char next = sql.charAt(j);
            if (Character.isLetterOrDigit(next) || next == '_' || next == '$') return -1;
        }
        return j;
    }

    /** Iterative scan: underscores must separate digits, with no recursive regex backtracking. */
    private static int readDigits(String sql, int start, int radix) {
        int j = start;
        while (j < sql.length()) {
            char c = sql.charAt(j);
            if (isRadixDigit(c, radix)) {
                j++;
            } else if (c == '_') {
                if (j == start || j + 1 >= sql.length() || !isRadixDigit(sql.charAt(j + 1), radix)) return -1;
                j++;
            } else {
                break;
            }
        }
        return j;
    }

    private static boolean isRadixDigit(char c, int radix) {
        int value = c >= '0' && c <= '9' ? c - '0'
                : c >= 'a' && c <= 'f' ? c - 'a' + 10
                : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
        return value >= 0 && value < radix;
    }

    private static int readBlockComment(String sql, int start) {
        int depth = 1;
        int j = start + 2;
        while (j + 1 < sql.length()) {
            if (sql.charAt(j) == '/' && sql.charAt(j + 1) == '*') {
                depth++;
                j += 2;
            } else if (sql.charAt(j) == '*' && sql.charAt(j + 1) == '/') {
                j += 2;
                if (--depth == 0) return j;
            } else {
                j++;
            }
        }
        return -1;
    }

    /** 0 = 非 dollar 引用，-1 = 已识别起始分隔符但未闭合，其余为结束位置。 */
    private static int readDollarQuoted(String sql, int start) {
        int n = sql.length();
        int j = start + 1;
        if (j < n && sql.charAt(j) != '$'
                && !Character.isLetter(sql.charAt(j)) && sql.charAt(j) != '_') return 0;
        while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) j++;
        if (j >= n || sql.charAt(j) != '$') return 0;
        String delimiter = sql.substring(start, j + 1);
        int end = sql.indexOf(delimiter, j + 1);
        return end < 0 ? -1 : end + delimiter.length();
    }

    private static int readOracleQuoted(String sql, int start) {
        int n = sql.length();
        if (start + 2 >= n) return -1;
        char open = sql.charAt(start + 2);
        if (Character.isWhitespace(open)) return -1;
        char close = switch (open) {
            case '[' -> ']'; case '{' -> '}'; case '(' -> ')'; case '<' -> '>';
            default -> open;
        };
        int end = sql.indexOf(close + "'", start + 3);
        return end < 0 ? -1 : end + 2;
    }

    /**
     * Scan a complete operator run once, stopping before comment openers.
     * PostgreSQL separates trailing +/- unless a non-SQL operator character occurs.
     * Emit those signs here as individual tokens to avoid rescanning long runs of '+'.
     */
    private static int appendOperatorTokens(String sql, int start, List<String> tokens) {
        if (sql.startsWith("::", start) || sql.startsWith(":=", start)) {
            tokens.add(sql.substring(start, start + 2));
            return start + 2;
        }
        int end = start;
        boolean permitsTrailingSign = false;
        while (end < sql.length() && isOperatorCharacter(sql.charAt(end))) {
            if (sql.startsWith("--", end) || sql.startsWith("/*", end)) break;
            permitsTrailingSign |= "~!@#%^&|?".indexOf(sql.charAt(end)) >= 0;
            end++;
        }
        if (end == start) return start;
        int headEnd = end;
        if (!permitsTrailingSign) {
            while (headEnd > start + 1
                    && (sql.charAt(headEnd - 1) == '+' || sql.charAt(headEnd - 1) == '-')) headEnd--;
        }
        tokens.add(sql.substring(start, headEnd));
        for (int i = headEnd; i < end; i++) tokens.add(String.valueOf(sql.charAt(i)));
        return end;
    }

    private static boolean isOperatorCharacter(char c) {
        // Backticks retain the existing quoted-identifier interpretation in this dialect-neutral formatter.
        return "+-*/<>=~!@#%^&|?".indexOf(c) >= 0;
    }

    /** PostgreSQL 续接字符串必须保留片段间的换行，并继承首段的 E 转义模式。 */
    private static int readStringLiteral(String sql, int start, boolean backslashEscapes) {
        int end = readQuoted(sql, start, '\'', backslashEscapes);
        while (end >= 0) {
            int next = end;
            boolean hasLineBreak = false;
            while (next < sql.length()) {
                char c = sql.charAt(next);
                if (Character.isWhitespace(c)) {
                    hasLineBreak |= c == '\n' || c == '\r';
                    next++;
                } else if (sql.startsWith("--", next)) {
                    // PostgreSQL allows line comments between continued fragments.
                    // Block comments do not belong to its quote-continuation rule.
                    next += 2;
                    while (next < sql.length() && sql.charAt(next) != '\n'
                            && sql.charAt(next) != '\r') next++;
                } else {
                    break;
                }
            }
            if (!hasLineBreak || next >= sql.length() || sql.charAt(next) != '\'') return end;
            end = readQuoted(sql, next, '\'', backslashEscapes);
        }
        return -1;
    }

    /** 返回收尾引号之后的下标；未闭合返回 -1。 */
    private static int readQuoted(String s, int start, char q) {
        return readQuoted(s, start, q, false);
    }

    private static int readQuoted(String s, int start, char q, boolean backslashEscapes) {
        int n = s.length();
        int j = start + 1;
        while (j < n) {
            if (backslashEscapes && s.charAt(j) == '\\') {
                j += 2;
                continue;
            }
            if (s.charAt(j) == q) {
                if (j + 1 < n && s.charAt(j + 1) == q) { j += 2; continue; } // 转义
                return j + 1;
            }
            j++;
        }
        return -1;
    }

    // ---------------------------------------------------------------- 排版

    /** 所有生成路径共用预算，包括缩进和 strip 前的尾部空白，不按语句重置。 */
    private static final class BoundedOutput {
        private final StringBuilder text = new StringBuilder();

        private void requireRoom(int additionalLength) {
            if (additionalLength < 0 || additionalLength > MAX_OUTPUT_LENGTH - text.length()) {
                throw new IllegalArgumentException("Formatting result limit exceeded");
            }
        }

        void append(char value) {
            requireRoom(1);
            text.append(value);
        }

        void append(String value) {
            requireRoom(value.length());
            text.append(value);
        }

        void appendSpaces(int count) {
            // Check the complete span before generating it; never allocate unbounded padding.
            requireRoom(count);
            for (int i = 0; i < count; i++) text.append(' ');
        }

        void removeTrailingSpaces() {
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == ' ') end--;
            text.setLength(end);
        }

        int length() { return text.length(); }
        char charAt(int index) { return text.charAt(index); }
        int lastIndexOf(String value) { return text.lastIndexOf(value); }
        @Override public String toString() { return text.toString(); }
    }

    private static final class Renderer {
        private final List<String> tokens;
        private final BoundedOutput sb = new BoundedOutput();
        private int indent = 0;        // 当前块河道的前导缩进（顶层为 0）
        private int plainParenDepth = 0; // 普通圆/方括号深度（>0 时挂起子句处理，保持行内）
        private enum ParenKind { PLAIN, BRACKET, SUBQUERY, WINDOW }
        private record ParenFrame(ParenKind kind, Ctx outer, int closingIndent) {}
        private final Deque<ParenFrame> parenStack = new ArrayDeque<>();
        private int windowDepth;
        private String clause = "";    // 顶层子句（仅在未处于普通括号内时更新）
        private boolean joinLineOpen;  // 当前行是否已由 JOIN 引导词开启
        private boolean betweenPending; // 处于 BETWEEN ... AND 之间，该 AND 不换行
        private boolean deleteInlineFrom; // DELETE 之后紧随的 FROM 保持同一行
        private boolean mergeMode; // MERGE 的 USING / WHEN 动作需要显式分段
        private Deque<Integer> caseIndents = new ArrayDeque<>();
        private boolean atLineStart = true;
        private String prev;

        /** 子查询与窗口定义拥有独立排版状态，闭括号后完整恢复外层上下文。 */
        private static final class Ctx {
            final int indent;
            final String clause;
            final boolean joinLineOpen, betweenPending, deleteInlineFrom, mergeMode;
            final Deque<Integer> caseIndents;
            final int windowDepth;
            final int plainParenDepth;
            Ctx(int indent, String clause, boolean joinLineOpen,
                boolean betweenPending, boolean deleteInlineFrom, boolean mergeMode,
                Deque<Integer> caseIndents, int windowDepth, int plainParenDepth) {
                this.indent = indent;
                this.clause = clause;
                this.joinLineOpen = joinLineOpen;
                this.betweenPending = betweenPending;
                this.deleteInlineFrom = deleteInlineFrom;
                this.mergeMode = mergeMode;
                this.caseIndents = caseIndents;
                this.windowDepth = windowDepth;
                this.plainParenDepth = plainParenDepth;
            }
        }

        Renderer(List<String> tokens) { this.tokens = tokens; }

        String render() {
            for (int idx = 0; idx < tokens.size(); idx++) {
                String tok = tokens.get(idx);
                String u = tok.toUpperCase(Locale.ROOT);
                boolean kw = KEYWORDS.contains(u);
                String out = kw ? u : tok;

                // 行注释必须结束当前物理行；否则后续 SQL 会被 -- 吞掉，实际语义就会改变。
                if (tok.startsWith("--")) {
                    emit(tok, !atLineStart && prev != null);
                    sb.append('\n');
                    atLineStart = true;
                    prev = tok;
                    continue;
                }

                if (kw && handleCase(u)) continue;
                if (kw && windowDepth > 0 && plainParenDepth == 0 && caseIndents.isEmpty()
                        && handleWindowClause(u)) continue;
                // 窗口内只处理窗口子句，不能把 EXCLUDE GROUP 等当作查询级 GROUP BY。
                boolean clauseActive = plainParenDepth == 0 && windowDepth == 0 && caseIndents.isEmpty();

                if (clauseActive && kw && handleClause(u, out)) continue;
                if (plainParenDepth == 0 && windowDepth == 0 && tok.equals(";")) { endStatement(); continue; }
                if (plainParenDepth == 0 && caseIndents.isEmpty() && tok.equals(",")
                        && (clause.equals("SELECT") || clause.equals("SET") || clause.equals("WITH")
                        || clause.equals("GROUP") || clause.equals("ORDER") || clause.equals("RETURNING")
                        || windowDepth > 0)) {
                    emit(",", false);
                    contLine();
                    prev = ",";
                    continue;
                }
                if (tok.equals("(")) { openParen(idx); continue; }
                if (tok.equals(")")) { closeParen(); continue; }
                if (tok.equals("[")) { openBracket(); continue; }
                if (tok.equals("]")) { closeBracket(); continue; }

                emit(out, needsSpaceBeforeToken(tok));
                prev = out;
            }
            return sb.toString().strip();
        }

        /** 结构化括号隔离外层状态；普通函数/列表括号只增加当前块内的深度。 */
        private void openParen(int idx) {
            boolean subquery = isSubqueryAhead(idx);
            boolean window = "OVER".equalsIgnoreCase(prev);
            if (subquery || window) {
                boolean space = subquery
                        ? !atLineStart && prev != null && !prev.equals("(") && !prev.equals("[")
                        : needsSpaceBeforeOpenParen(idx);
                emit("(", space);
                Ctx outer = new Ctx(indent, clause, joinLineOpen, betweenPending, deleteInlineFrom,
                        mergeMode, caseIndents, windowDepth, plainParenDepth);
                int closingIndent = indent + RIVER;
                if (subquery) {
                    indent = caseIndents.isEmpty() ? indent + RIVER + 2
                            : Math.max(indent + RIVER + 2, caseIndents.peek() + 8);
                    closingIndent = indent - 2;
                } else if (!caseIndents.isEmpty()) {
                    indent = Math.max(indent, caseIndents.peek() + 1);
                    closingIndent = indent + RIVER;
                }
                parenStack.push(new ParenFrame(subquery ? ParenKind.SUBQUERY : ParenKind.WINDOW,
                        outer, closingIndent));
                clause = "";
                joinLineOpen = false;
                betweenPending = false;
                deleteInlineFrom = false;
                mergeMode = false;
                // Save the outer stack by reference and give the nested query/window its own.
                caseIndents = new ArrayDeque<>();
                plainParenDepth = 0;
                windowDepth = subquery ? 0 : 1;
                if (!subquery) {
                    sb.append('\n');
                    atLineStart = true;
                }
            } else {
                emit("(", needsSpaceBeforeOpenParen(idx));
                parenStack.push(new ParenFrame(ParenKind.PLAIN, null, 0));
                plainParenDepth++;
            }
            prev = "(";
        }

        /** 闭括号：子查询括号另起一行并对齐到外层河道、恢复外层上下文；普通括号行内收尾。 */
        private void closeParen() {
            if (parenStack.isEmpty() || inDirectBracket()) { emit(")", false); prev = ")"; return; }
            ParenFrame frame = parenStack.pop();
            if (frame.kind() != ParenKind.PLAIN) {
                Ctx c = frame.outer();
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                appendIndent(frame.closingIndent());
                sb.append(')');
                atLineStart = false;
                indent = c.indent;
                clause = c.clause;
                joinLineOpen = c.joinLineOpen;
                betweenPending = c.betweenPending;
                deleteInlineFrom = c.deleteInlineFrom;
                mergeMode = c.mergeMode;
                caseIndents = c.caseIndents;
                windowDepth = c.windowDepth;
                plainParenDepth = c.plainParenDepth;
            } else {
                if (plainParenDepth > 0) plainParenDepth--;
                emit(")", false);
            }
            prev = ")";
        }

        private void openBracket() {
            boolean space = prev != null && (prev.equals(",") || isOperator(prev) || prev.startsWith("/*"));
            emit("[", space);
            parenStack.push(new ParenFrame(ParenKind.BRACKET, null, 0));
            plainParenDepth++;
            prev = "[";
        }

        private void closeBracket() {
            // A mismatched ']' must not consume a function/subquery/window frame.
            if (inDirectBracket()) {
                parenStack.pop();
                plainParenDepth--;
            }
            emit("]", false);
            prev = "]";
        }

        private boolean inDirectBracket() {
            return !parenStack.isEmpty() && parenStack.peek().kind() == ParenKind.BRACKET;
        }

        /** 仅压紧当前下标/数组层的切片冒号，不改变函数参数和已有 :: token。 */
        private boolean needsSpaceBeforeToken(String token) {
            if (inDirectBracket() && prev != null && !token.startsWith("/*")) {
                // Keep separately tokenized colons apart; ': :upper' must not become '::upper'.
                if (prev.endsWith(":") && token.startsWith(":")) return true;
                if (prev.equals(":") || token.equals(":")) return false;
                if (token.startsWith(":") && !token.equals("::") && !prev.equals(",")
                        && !isOperator(prev) && !KEYWORDS.contains(prev.toUpperCase(Locale.ROOT))) return false;
            }
            return needSpaceBefore(prev, token);
        }

        /** 向前看：跳过注释 token 后，首个 token 为 SELECT/WITH 则当前括号为子查询括号。 */
        private boolean isSubqueryAhead(int idx) {
            for (int j = idx + 1; j < tokens.size(); j++) {
                String t = tokens.get(j);
                if (t.startsWith("--") || t.startsWith("/*")) continue;
                String u = t.toUpperCase(Locale.ROOT);
                return u.equals("SELECT") || u.equals("WITH");
            }
            return false;
        }

        /** 处理顶层子句关键字；已消费返回 true。 */
        private boolean handleClause(String u, String out) {
            switch (u) {
                case "SELECT":
                    startClause(out);
                    clause = "SELECT";
                    joinLineOpen = false;
                    prev = out;
                    return true;
                case "WITH":
                    if ("START".equals(prev)) {
                        emit(out, true); // START WITH 是 Oracle 层级查询短语
                        clause = "START WITH";
                        prev = out;
                        return true;
                    }
                    startClause(out);
                    clause = "WITH";
                    joinLineOpen = false;
                    prev = out;
                    return true;
                case "INSERT":
                case "UPDATE":
                case "DELETE":
                    if ("ON".equals(clause) && "ON".equals(prev)) {
                        emit(out, true); // 外键 ON DELETE/UPDATE 动作与 ON 保持同一行
                        prev = out;
                        return true;
                    }
                    startClause(out);
                    clause = "DML";
                    joinLineOpen = false;
                    deleteInlineFrom = u.equals("DELETE");
                    prev = out;
                    return true;
                case "MERGE":
                    startClause(out);
                    clause = "MERGE";
                    mergeMode = true;
                    joinLineOpen = false;
                    prev = out;
                    return true;
                case "FROM":
                    if (deleteInlineFrom) {
                        deleteInlineFrom = false;
                        emit(out, true); // DELETE FROM 保持同一行
                    } else {
                        startClause(out);
                    }
                    clause = "FROM";
                    joinLineOpen = false;
                    prev = out;
                    return true;
                case "START":
                    startClause(out);
                    clause = "START";
                    joinLineOpen = false;
                    prev = out;
                    return true;
                case "CONNECT":
                    startClause(out);
                    clause = "CONNECT";
                    joinLineOpen = false;
                    prev = out;
                    return true;
                case "BY":
                    if ("CONNECT".equals(clause)) {
                        emit(out, true);
                        clause = "CONNECT BY";
                        prev = out;
                        return true;
                    }
                    return false;
                case "USING":
                    if (mergeMode) {
                        startClause(out);
                        clause = "USING";
                        joinLineOpen = false;
                        prev = out;
                        return true;
                    }
                    return false;
                case "WHEN":
                    if (mergeMode) {
                        startClause(out);
                        clause = "WHEN";
                        joinLineOpen = false;
                        prev = out;
                        return true;
                    }
                    return false;
                case "SET":
                    startClause(out);
                    clause = "SET";
                    joinLineOpen = false;
                    prev = out;
                    return true;
                case "GROUP":
                case "ORDER":
                    startClause(out);
                    clause = u;
                    joinLineOpen = false;
                    prev = out;
                    return true;
                case "JOIN":
                    if (joinLineOpen) {
                        emit(out, true); // 续接 LEFT/INNER/... 引导词
                    } else {
                        startClause(out);
                    }
                    clause = "JOIN";
                    joinLineOpen = true;
                    prev = out;
                    return true;
                case "ON":
                    startClause(out); // ON 另起一行，右对齐到河道
                    clause = "ON";
                    joinLineOpen = false;
                    prev = out;
                    return true;
                case "BETWEEN":
                    betweenPending = true;
                    return false; // 其后的 AND 属于 BETWEEN ... AND，交由默认逻辑行内输出
                case "AND":
                case "OR":
                    if (betweenPending && u.equals("AND")) {
                        betweenPending = false;
                        return false; // BETWEEN 的 AND，按普通词行内输出
                    }
                    if (clause.equals("WHERE") || clause.equals("HAVING")
                            || clause.equals("ON") || clause.equals("JOIN")) {
                        startClause(out); // AND / OR 右对齐到河道另起一行
                        prev = out;
                        return true;
                    }
                    return false; // 其它上下文按普通词处理
                default:
                    if (JOIN_LEAD.contains(u)) {
                        startClause(out);
                        clause = "JOIN";
                        joinLineOpen = true;
                        prev = out;
                        return true;
                    }
                    if (LINE_STARTERS.contains(u)) {
                        if (u.equals("UNION") || u.equals("INTERSECT")
                                || u.equals("EXCEPT") || u.equals("MINUS")) startSetOperator(out);
                        else startClause(out);
                        clause = u;
                        joinLineOpen = false;
                        prev = out;
                        return true;
                    }
                    return false;
            }
        }

        /** CASE 分支内缩，END 对齐所属 CASE；嵌套表达式拥有独立缩进。 */
        private boolean handleCase(String u) {
            switch (u) {
                case "CASE" -> {
                    if (!caseIndents.isEmpty()) {
                        caseLine(caseIndents.peek() + 8);
                    } else if (atLineStart && sb.length() > 0 && currentColumn() == 0) {
                        caseLine(indent + RIVER + 1);
                    }
                    emit("CASE", needSpaceBefore(prev, "CASE"));
                    caseIndents.push(currentColumn() - "CASE".length());
                    prev = "CASE";
                    return true;
                }
                case "WHEN", "ELSE", "END" -> {
                    if (caseIndents.isEmpty()) return false;
                    int base = caseIndents.peek();
                    if (u.equals("END")) caseIndents.pop();
                    caseLine(u.equals("END") ? base : base + 4);
                    emit(u, false);
                    prev = u;
                    return true;
                }
                default -> { return false; }
            }
        }

        private int currentColumn() {
            return sb.length() - sb.lastIndexOf("\n") - 1;
        }

        /** 不在行注释后另添空行，也不留下尚未填充的列表续行空白。 */
        private void caseLine(int spaces) {
            if (atLineStart) {
                sb.removeTrailingSpaces();
            } else {
                sb.append('\n');
            }
            appendIndent(spaces);
            atLineStart = true;
        }

        /** 窗口定义的主要分区/排序/边界子句各占一行，避免复杂 OVER(...) 挤成一行。 */
        private boolean handleWindowClause(String u) {
            if (!Set.of("PARTITION", "ORDER", "ROWS", "RANGE", "GROUPS", "EXCLUDE").contains(u)) {
                return false;
            }
            if (!atLineStart) sb.append('\n');
            appendIndent(indent + RIVER + 1);
            sb.append(u);
            atLineStart = false;
            prev = u;
            return true;
        }

        private void endStatement() {
            emit(";", false);
            sb.append("\n\n");    // 与后续语句间空一行
            atLineStart = true;
            clause = "";
            joinLineOpen = false;
            betweenPending = false;
            deleteInlineFrom = false;
            mergeMode = false;
            caseIndents.clear();
            prev = ";";
        }

        /** 主子句起新行：前导关键字右对齐到河道列（含当前缩进），形成 PL/SQL 风格的竖直对齐。 */
        private void startClause(String lead) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            int pad = Math.max(0, (indent + RIVER) - lead.length());
            appendIndent(pad);
            sb.append(lead);
            atLineStart = false; // 其后内容以空格续接同一行
        }

        private void startSetOperator(String lead) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            appendIndent(indent);
            sb.append(lead);
            atLineStart = false;
        }

        private void appendIndent(int spaces) {
            sb.appendSpaces(spaces);
        }

        /** 列表续行：缩进到河道右侧一格（含当前缩进），使续行与首项左对齐。 */
        private void contLine() {
            sb.append('\n');
            int spaces = indent + RIVER + 1;
            appendIndent(spaces);
            atLineStart = true; // 下一 token 无前导空格
        }

        private void emit(String tok, boolean spaceBefore) {
            if (atLineStart) {
                if (!caseIndents.isEmpty() && currentColumn() == 0) appendIndent(caseIndents.peek() + 4);
                sb.append(tok);
                atLineStart = false;
            } else {
                if (spaceBefore) sb.append(' ');
                sb.append(tok);
            }
        }

        private boolean needsSpaceBeforeOpenParen(int idx) {
            if (atLineStart || prev == null || prev.equals("(") || prev.equals("[") || prev.equals(".")) return false;
            if (inDirectBracket() && prev.equals(",")) return true;
            String upper = prev.toUpperCase(Locale.ROOT);
            if (Set.of("SELECT", "FROM", "WHERE", "JOIN", "ON", "AND", "OR", "IN", "INSERT", "KEY",
                    "NOT", "EXISTS", "VALUES", "SET", "OVER", "AS", "WHEN", "THEN",
                    "ELSE", "RETURNING", "FILTER").contains(upper)) return true;
            if (isOperator(prev)) return true;
            // INSERT INTO table (...) / CREATE TABLE t (...) are declaration lists, not calls.
            return idx >= 2 && (tokens.get(idx - 2).equalsIgnoreCase("INTO")
                    || DECLARATION_PREFIXES.contains(tokens.get(idx - 2).toUpperCase(Locale.ROOT)));
        }

        private static boolean isOperator(String token) {
            if (token.equals("::") || token.equals(":=")) return true;
            if (token.isEmpty()) return false;
            for (int i = 0; i < token.length(); i++) {
                if (!isOperatorCharacter(token.charAt(i))) return false;
            }
            return true;
        }

        private static boolean needSpaceBefore(String prev, String cur) {
            if (prev == null) return false;
            switch (cur) {
                case ",": case ";": case ")": case "]": case ".": return false;
                case "::": return false;
                default:
            }
            if (prev.equals("::") || prev.equals(".") || prev.equals("(") || prev.equals("[")) return false;
            return !prev.equals("(") && !prev.equals(".");
        }
    }
}
