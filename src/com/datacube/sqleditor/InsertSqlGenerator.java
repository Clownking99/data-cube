package com.datacube.sqleditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 查询结果 → INSERT 语句生成器（纯文本，不依赖数据库/UI）。
 *
 * <p>两个职责：
 * <ul>
 *   <li>{@link #singleTableName(String)}：从单表 SELECT 解析目标表名
 *       （含 schema 限定与引号原样保留）；JOIN/逗号多表/子查询/集合操作等
 *       无法唯一定表时返回 {@code null}，由调用方回退为人工指定。</li>
 *   <li>{@link #generate(String, List, List)}：按结果集逐行生成
 *       {@code INSERT INTO t (c1, c2) VALUES (v1, v2);}。</li>
 * </ul>
 *
 * <p>值渲染规则：{@code null → NULL}；数字/布尔不加引号；其余（含日期时间的
 * 字符串形态）以单引号包裹并对 {@code '} 做双写转义。
 */
public final class InsertSqlGenerator {

    private InsertSqlGenerator() {
    }

    /** FROM 单表之后允许出现的子句起始词（到此即结束别名/多表判定）。 */
    private static final Set<String> CLAUSE_STARTERS = Set.of(
            "WHERE", "GROUP", "ORDER", "HAVING", "FETCH", "LIMIT", "OFFSET",
            "FOR", "CONNECT", "START", "WINDOW", "QUALIFY");

    /** 出现即代表多表连接的关键字。 */
    private static final Set<String> JOIN_WORDS = Set.of(
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL",
            "OUTER", "ON", "USING", "LATERAL");

    /** 顶层集合操作：结果来源不再是单表。 */
    private static final Set<String> SET_OPS = Set.of("UNION", "INTERSECT", "EXCEPT", "MINUS");

    // ---------- 单表来源解析 ----------

    /**
     * 解析单表 SELECT 的目标表名（原样保留 schema 限定与双引号）。
     * 无法唯一确定单表（JOIN、逗号多表、子查询、UNION、WITH、非 SELECT 等）
     * 时返回 {@code null}。
     */
    public static String singleTableName(String sql) {
        if (sql == null || sql.isBlank()) return null;
        List<Token> ts = scan(sql);
        if (ts.isEmpty()) return null;
        Token first = ts.get(0);
        if (first.kind != Kind.WORD || !"SELECT".equalsIgnoreCase(first.text)) return null;

        int fromIdx = -1;
        for (int i = 1; i < ts.size(); i++) {
            Token t = ts.get(i);
            if (t.depth != 0 || t.kind != Kind.WORD) continue;
            String u = t.text.toUpperCase();
            if (u.equals("FROM")) {
                if (fromIdx >= 0) return null;      // 顶层出现第二个 FROM（如 UNION 后半）
                fromIdx = i;
            } else if (SET_OPS.contains(u)) {
                return null;                        // 顶层集合操作
            }
        }
        if (fromIdx < 0 || fromIdx + 1 >= ts.size()) return null;

        Token tbl = ts.get(fromIdx + 1);
        // 子查询 FROM ( ... ) 的 "(" 深度为 1，直接判掉；字符串/子句词也不是表名
        if (tbl.depth != 0 || (tbl.kind != Kind.WORD && tbl.kind != Kind.QUOTED)) return null;
        if (tbl.kind == Kind.WORD && (CLAUSE_STARTERS.contains(tbl.text.toUpperCase())
                || JOIN_WORDS.contains(tbl.text.toUpperCase()))) return null;

        // 表名之后到子句边界：只允许一个可选别名（可带 AS）；逗号/JOIN 词即多表
        boolean aliasSeen = false;
        for (int i = fromIdx + 2; i < ts.size(); i++) {
            Token t = ts.get(i);
            if (t.depth > 0) continue;
            if (t.kind == Kind.WORD) {
                String u = t.text.toUpperCase();
                if (CLAUSE_STARTERS.contains(u)) break;
                if (JOIN_WORDS.contains(u) || SET_OPS.contains(u)) return null;
                if (u.equals("AS")) continue;
                if (aliasSeen) return null;
                aliasSeen = true;
            } else if (t.kind == Kind.QUOTED) {
                if (aliasSeen) return null;         // 引号别名
                aliasSeen = true;
            } else if (t.kind == Kind.SYMBOL) {
                if (t.text.equals(",") || t.text.equals("@")) return null;  // 多表 / dblink
                if (t.text.equals(";")) break;
            } else {
                return null;                        // FROM 区出现字符串字面量等异常形态
            }
        }
        return tbl.text;
    }

    // ---------- INSERT 生成 ----------

    /**
     * 按行生成 INSERT 语句，每行一条、分号结尾换行分隔。
     * {@code columns} 与每行元素按下标对齐，行短缺的列补 {@code NULL}。
     */
    public static String generate(String table, List<String> columns, List<List<Object>> rows) {
        StringBuilder head = new StringBuilder("INSERT INTO ").append(table).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) head.append(", ");
            head.append(quoteIdentIfNeeded(columns.get(i)));
        }
        head.append(") VALUES (");

        StringBuilder sb = new StringBuilder();
        for (List<Object> row : rows) {
            sb.append(head);
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(literal(i < row.size() ? row.get(i) : null));
            }
            sb.append(");\n");
        }
        return sb.toString();
    }

    /** 单个值 → SQL 字面量。 */
    private static String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean b) return b ? "TRUE" : "FALSE";
        return "'" + v.toString().replace("'", "''") + "'";
    }

    /** 常规标识符原样输出；含空格/特殊字符时加双引号（内部双引号双写）。 */
    private static String quoteIdentIfNeeded(String name) {
        if (name == null || name.isEmpty()) return "\"\"";
        boolean plain = Character.isLetter(name.charAt(0)) || name.charAt(0) == '_';
        for (int i = 0; plain && i < name.length(); i++) {
            char c = name.charAt(i);
            plain = Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
        }
        return plain ? name : "\"" + name.replace("\"", "\"\"") + "\"";
    }

    // ---------- 轻量扫描器 ----------

    private enum Kind { WORD, QUOTED, STRING, SYMBOL }

    /** 词法单元：文本 + 所处括号深度（顶层为 0）。 */
    private record Token(Kind kind, String text, int depth) {
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    /**
     * 扫描 SQL 为 token 流：跳过注释；字符串整体为一个 token；
     * {@code schema.table} 与 {@code "S"."T"} 合并为单个标识符 token；
     * 括号只影响深度计数（子查询内容深度 > 0）。
     */
    private static List<Token> scan(String s) {
        List<Token> out = new ArrayList<>();
        int n = s.length();
        int depth = 0;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    if (s.charAt(j) == '\'') {
                        if (j + 1 < n && s.charAt(j + 1) == '\'') j += 2;   // '' 转义
                        else break;
                    } else {
                        j++;
                    }
                }
                out.add(new Token(Kind.STRING, s.substring(i, Math.min(j + 1, n)), depth));
                i = j + 1;
            } else if (c == '(') {
                depth++;
                out.add(new Token(Kind.SYMBOL, "(", depth));
                i++;
            } else if (c == ')') {
                out.add(new Token(Kind.SYMBOL, ")", depth));
                depth = Math.max(0, depth - 1);
                i++;
            } else if (c == '"' || isWordChar(c)) {
                StringBuilder sb = new StringBuilder();
                boolean quoted = false;
                while (i < n) {
                    char ch = s.charAt(i);
                    if (ch == '"') {
                        quoted = true;
                        int j = s.indexOf('"', i + 1);
                        if (j < 0) j = n - 1;
                        sb.append(s, i, j + 1);
                        i = j + 1;
                    } else if (isWordChar(ch)) {
                        while (i < n && isWordChar(s.charAt(i))) sb.append(s.charAt(i++));
                    } else {
                        break;
                    }
                    if (i < n && s.charAt(i) == '.') {  // 限定名：继续拼下一段
                        sb.append('.');
                        i++;
                    } else {
                        break;
                    }
                }
                out.add(new Token(quoted ? Kind.QUOTED : Kind.WORD, sb.toString(), depth));
            } else {
                out.add(new Token(Kind.SYMBOL, String.valueOf(c), depth));
                i++;
            }
        }
        return out;
    }
}
