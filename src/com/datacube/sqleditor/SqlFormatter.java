package com.datacube.sqleditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 美化器：基于词法分词的整形排版，输出多行、带缩进的清晰格式。
 *
 * <p>设计要点：
 * <ul>
 *   <li>纯词法处理，不解析语义，因此<b>永不改变 SQL 语义</b>——只调整空白与关键字大小写；</li>
 *   <li>字符串字面量 {@code '...'}、双引号标识符 {@code "..."}、行注释 {@code --} 与
 *       块注释 {@code /*..*} 均作为整体保留，不会在其内部插入换行；</li>
 *   <li>采用 PL/SQL Developer 风格的“河道”对齐：SELECT / FROM / WHERE / GROUP BY /
 *       ORDER BY / JOIN 等主子句的前导关键字<b>右对齐</b>到同一列（= "SELECT" 宽度），
 *       视觉上形成一条竖直的对齐“河道”；</li>
 *   <li>SELECT / SET 列表逐列换行，续行左对齐到河道右侧一格；</li>
 *   <li>JOIN 子句独立成行，{@code ON} 另起一行并右对齐；</li>
 *   <li>WHERE / HAVING / ON 中的 AND / OR 右对齐到河道另起一行；</li>
 *   <li>括号内（函数参数、IN 列表、子查询）保持行内排版，避免破坏函数调用可读性；</li>
 *   <li>关键字统一大写；多语句以分号分隔并各自成段。</li>
 * </ul>
 *
 * <p>无 JavaFX 依赖，可被 CLI 复用。
 */
public final class SqlFormatter {

    private SqlFormatter() {}

    /** PL/SQL Developer 风格的对齐列宽：主子句前导关键字右对齐至此列（= "SELECT" 长度）。 */
    private static final int RIVER = 6;

    /** 需大写的关键字集合（大小写不敏感匹配）。 */
    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET",
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "ALTER", "DROP",
            "TABLE", "VIEW", "INDEX", "SEQUENCE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL",
            "OUTER", "CROSS", "ON", "AS", "AND", "OR", "NOT", "NULL", "IS", "IN", "EXISTS",
            "BETWEEN", "LIKE", "ILIKE", "DISTINCT", "UNION", "INTERSECT", "EXCEPT", "MINUS",
            "ALL", "ANY", "SOME", "CASE", "WHEN", "THEN", "ELSE", "END", "ASC", "DESC",
            "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "CAST", "WITH", "RETURNING",
            "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "DEFAULT", "CONSTRAINT", "UNIQUE",
            "CHECK", "TRUE", "FALSE", "USING", "OVER", "PARTITION");

    /** 触发另起一行（顶层子句）的关键字。 */
    private static final Set<String> LINE_STARTERS = Set.of(
            "WHERE", "HAVING", "LIMIT", "OFFSET",
            "UNION", "INTERSECT", "EXCEPT", "MINUS", "VALUES", "RETURNING");

    /** JOIN 短语的引导词（其后的 OUTER / JOIN 续接同一行）。 */
    private static final Set<String> JOIN_LEAD = Set.of("INNER", "LEFT", "RIGHT", "FULL", "CROSS");

    /** 美化 SQL 脚本（支持多语句）；无法处理的片段仅做保守整形，不会破坏语义。 */
    public static String format(String sql) {
        if (sql == null || sql.isBlank()) return sql;
        List<String> tokens = tokenize(sql);
        if (tokens.isEmpty()) return sql;
        return new Renderer(tokens).render();
    }

    // ---------------------------------------------------------------- 分词

    private static List<String> tokenize(String sql) {
        List<String> out = new ArrayList<>();
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            // 行注释 --...
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int j = i + 2;
                while (j < n && sql.charAt(j) != '\n') j++;
                out.add(sql.substring(i, j));
                i = j;
                continue;
            }
            // 块注释 /* ... */
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int j = i + 2;
                while (j + 1 < n && !(sql.charAt(j) == '*' && sql.charAt(j + 1) == '/')) j++;
                j = Math.min(n, j + 2);
                out.add(sql.substring(i, j));
                i = j;
                continue;
            }
            // 单引号字符串（'' 转义）
            if (c == '\'') {
                int j = readQuoted(sql, i, '\'');
                out.add(sql.substring(i, j));
                i = j;
                continue;
            }
            // 双引号标识符（"" 转义）
            if (c == '"') {
                int j = readQuoted(sql, i, '"');
                out.add(sql.substring(i, j));
                i = j;
                continue;
            }
            // 标识符 / 关键字 / 数字（含 . 以保留 a.b 与 3.14）
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
            // 多字符运算符
            if (i + 1 < n) {
                String two = sql.substring(i, i + 2);
                if (two.equals("::") || two.equals(":=") || two.equals("<=")
                        || two.equals(">=") || two.equals("<>") || two.equals("!=")
                        || two.equals("||")) {
                    out.add(two);
                    i += 2;
                    continue;
                }
            }
            // 单字符符号
            out.add(String.valueOf(c));
            i++;
        }
        return out;
    }

    /** 从 start（引号位置）读取到匹配的收尾引号，返回收尾引号之后的下标。 */
    private static int readQuoted(String s, int start, char q) {
        int n = s.length();
        int j = start + 1;
        while (j < n) {
            if (s.charAt(j) == q) {
                if (j + 1 < n && s.charAt(j + 1) == q) { j += 2; continue; } // 转义
                return j + 1;
            }
            j++;
        }
        return n; // 未闭合：吞到末尾
    }

    // ---------------------------------------------------------------- 排版

    private static final class Renderer {
        private final List<String> tokens;
        private final StringBuilder sb = new StringBuilder();
        private int paren = 0;         // 括号深度
        private String clause = "";    // 顶层子句（仅在 paren==0 更新）
        private boolean joinLineOpen;  // 当前行是否已由 JOIN 引导词开启
        private boolean betweenPending; // 处于 BETWEEN ... AND 之间，该 AND 不换行
        private boolean deleteInlineFrom; // DELETE 之后紧随的 FROM 保持同一行
        private boolean atLineStart = true;
        private String prev;

        Renderer(List<String> tokens) { this.tokens = tokens; }

        String render() {
            for (String tok : tokens) {
                String u = tok.toUpperCase(Locale.ROOT);
                boolean kw = KEYWORDS.contains(u);
                String out = kw ? u : tok;

                if (paren == 0 && kw && handleClause(u, out)) continue;
                if (paren == 0 && tok.equals(";")) { endStatement(); continue; }
                if (paren == 0 && tok.equals(",")
                        && (clause.equals("SELECT") || clause.equals("SET"))) {
                    emit(",", false);
                    contLine();
                    prev = ",";
                    continue;
                }
                if (tok.equals("(")) { emit("(", false); paren++; prev = "("; continue; }
                if (tok.equals(")")) { emit(")", false); if (paren > 0) paren--; prev = ")"; continue; }

                emit(out, needSpaceBefore(prev, tok));
                prev = out;
            }
            return sb.toString().strip();
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
                    startClause(out);
                    clause = "WITH";
                    joinLineOpen = false;
                    prev = out;
                    return true;
                case "INSERT":
                case "UPDATE":
                case "DELETE":
                    startClause(out);
                    clause = "DML";
                    joinLineOpen = false;
                    deleteInlineFrom = u.equals("DELETE");
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
                        startClause(out);
                        clause = u;
                        joinLineOpen = false;
                        prev = out;
                        return true;
                    }
                    return false;
            }
        }

        private void endStatement() {
            emit(";", false);
            sb.append('\n');       // 与后续语句间空一行
            atLineStart = true;
            clause = "";
            joinLineOpen = false;
            betweenPending = false;
            deleteInlineFrom = false;
            prev = ";";
        }

        /** 主子句起新行：前导关键字右对齐到河道列，形成 PL/SQL 风格的竖直对齐。 */
        private void startClause(String lead) {
            if (sb.length() > 0) sb.append('\n');
            int pad = Math.max(0, RIVER - lead.length());
            for (int i = 0; i < pad; i++) sb.append(' ');
            sb.append(lead);
            atLineStart = false; // 其后内容以空格续接同一行
        }

        /** 列表续行：缩进到河道右侧一格，使续行与首项左对齐。 */
        private void contLine() {
            sb.append('\n');
            for (int i = 0; i <= RIVER; i++) sb.append(' '); // RIVER + 1 个空格
            atLineStart = true; // 下一 token 无前导空格
        }

        private void emit(String tok, boolean spaceBefore) {
            if (atLineStart) {
                sb.append(tok);
                atLineStart = false;
            } else {
                if (spaceBefore) sb.append(' ');
                sb.append(tok);
            }
        }

        private static boolean needSpaceBefore(String prev, String cur) {
            if (prev == null) return false;
            switch (cur) {
                case ",": case ";": case ")": case ".": return false;
                case "(": return false;
                default:
            }
            return !prev.equals("(") && !prev.equals(".");
        }
    }
}
