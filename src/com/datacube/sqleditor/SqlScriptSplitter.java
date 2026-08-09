package com.datacube.sqleditor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 脚本分句器：基于状态机，正确处理字符串 / 注释中的分号。
 *
 * <p>比 {@code SqlUtils.splitSql} 更鲁棒：
 * <ul>
 *   <li>单引号字符串内的 {@code ;} 不切分（支持 {@code ''} 转义）</li>
 *   <li>{@code --} 行注释至行尾</li>
 *   <li>{@code /* ... *}{@code /} 块注释跨行</li>
 *   <li>PostgreSQL Dollar-quoted strings：{@code $tag$ ... $tag$}</li>
 * </ul>
 */
public final class SqlScriptSplitter {

    private SqlScriptSplitter() {}

    /** PL/SQL 块起始识别（语句起始处，忽略大小写）：DECLARE/BEGIN 或 CREATE ... 各类命名块。 */
    private static final Pattern PLSQL_START = Pattern.compile(
            "(?:DECLARE|BEGIN|CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:EDITIONABLE\\s+|NONEDITIONABLE\\s+)?"
                    + "(?:PROCEDURE|FUNCTION|PACKAGE\\s+BODY|PACKAGE|TRIGGER|TYPE\\s+BODY|TYPE))\\b",
            Pattern.CASE_INSENSITIVE);

    public static List<String> split(String sql) {
        return split(sql, false);
    }

    /**
     * 分句；{@code plsql=true} 时启用 Oracle/SQL*Plus 语义：
     * <ul>
     *   <li>单独成行的 {@code /} 作为语句终止符（不计入语句文本）；</li>
     *   <li>DECLARE/BEGIN 或 CREATE ... PROCEDURE|FUNCTION|PACKAGE|TRIGGER|TYPE 等 PL/SQL 块
     *       内部的 {@code ;} 不参与切分，仅遇 {@code /} 行或 EOF 终止。</li>
     * </ul>
     * {@code plsql=false} 时与历史行为一致（PG 用）。
     */
    public static List<String> split(String sql, boolean plsql) {
        if (sql == null || sql.isEmpty()) return new ArrayList<>();
        return new SplitState(plsql).run(sql);
    }

    /** 剥离方言注释后是否仍含可执行内容。 */
    static boolean hasExecutableContent(String sql, boolean oracleMode) {
        return SqlLexicalRules.triviaStatus(sql, oracleMode)
                == SqlLexicalRules.TriviaStatus.EXECUTABLE;
    }

    private enum State {
        NORMAL, IN_QUOTE, IN_DQUOTE, IN_LINE_COMMENT, IN_BLOCK_COMMENT, IN_DOLLAR,
        IN_ORACLE_Q_QUOTE
    }

    /** 单次 split 调用的可变状态。 */
    private static final class SplitState {
        private final StringBuilder cur = new StringBuilder();
        private final List<String> stmts = new ArrayList<>();
        private State state = State.NORMAL;
        private String dollarTag;
        private boolean backslashEscapes;
        private int blockCommentDepth;
        private char oracleQuoteClose;
        private final boolean plsql;
        private boolean plsqlBlock;

        SplitState(boolean plsql) {
            this.plsql = plsql;
        }

        List<String> run(String sql) {
            int n = sql.length();
            int i = 0;
            Matcher blockMatcher = plsql ? PLSQL_START.matcher(sql) : null;

            while (i < n) {
                char c = sql.charAt(i);

                switch (state) {
                    case NORMAL:
                        // 语句起始处探测 PL/SQL 块：命中后块内 ; 不再切分
                        if (plsql && !plsqlBlock && !Character.isWhitespace(c) && isBlank(cur)
                                && blockMatcher.region(i, n).lookingAt()) {
                            plsqlBlock = true;
                        }
                        SqlLexicalRules.OracleQuote oracleQuote =
                                SqlLexicalRules.oracleQuoteAt(sql, i, plsql);
                        if (oracleQuote != null) {
                            cur.append(sql, i, i + oracleQuote.prefixLength());
                            oracleQuoteClose = oracleQuote.closingDelimiter();
                            state = State.IN_ORACLE_Q_QUOTE;
                            i += oracleQuote.prefixLength();
                        } else if (c == '\'') {
                            backslashEscapes = SqlLexicalRules.isPostgresEscapeStringQuote(
                                    sql, i, plsql);
                            state = State.IN_QUOTE;
                            cur.append(c);
                            i++;
                        } else if (c == '"') {
                            state = State.IN_DQUOTE;
                            cur.append(c);
                            i++;
                        } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                            state = State.IN_LINE_COMMENT;
                            cur.append(c);
                            i++;
                        } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                            state = State.IN_BLOCK_COMMENT;
                            blockCommentDepth = 1;
                            cur.append("/*");
                            i += 2;
                        } else if (plsql && c == '/' && isLineAloneSlash(sql, i)) {
                            // SQL*Plus 终止符：单独成行的 /
                            flush();
                            plsqlBlock = false;
                            i = advancePastLine(sql, i);
                        } else if (c == '$') {
                            String tag = SqlLexicalRules.dollarDelimiterAt(sql, i, plsql);
                            if (tag != null) {
                                cur.append(tag);
                                state = State.IN_DOLLAR;
                                dollarTag = tag;
                                i += tag.length();
                            } else {
                                cur.append(c);
                                i++;
                            }
                        } else if (c == ';' && !plsqlBlock) {
                            flush();
                            i++;
                        } else {
                            cur.append(c);
                            i++;
                        }
                        break;

                    case IN_QUOTE:
                        cur.append(c);
                        if (backslashEscapes && c == '\\' && i + 1 < n) {
                            cur.append(sql.charAt(i + 1));
                            i += 2;
                        } else if (c == '\'') {
                            if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                                cur.append('\'');
                                i += 2;
                            } else {
                                state = State.NORMAL;
                                backslashEscapes = false;
                                i++;
                            }
                        } else {
                            i++;
                        }
                        break;

                    case IN_DQUOTE:
                        cur.append(c);
                        if (c == '"') {
                            if (i + 1 < n && sql.charAt(i + 1) == '"') {
                                cur.append('"');
                                i += 2;
                            } else {
                                state = State.NORMAL;
                                i++;
                            }
                        } else {
                            i++;
                        }
                        break;

                    case IN_LINE_COMMENT:
                        cur.append(c);
                        if (c == '\n' || c == '\r') state = State.NORMAL;
                        i++;
                        break;

                    case IN_BLOCK_COMMENT:
                        if (!plsql && c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                            cur.append("/*");
                            blockCommentDepth++;
                            i += 2;
                        } else if (c == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                            cur.append("*/");
                            blockCommentDepth--;
                            if (blockCommentDepth == 0) state = State.NORMAL;
                            i += 2;
                        } else {
                            cur.append(c);
                            i++;
                        }
                        break;

                    case IN_DOLLAR:
                        if (c == '$' && dollarTag != null
                                && i + dollarTag.length() <= n
                                && sql.substring(i, i + dollarTag.length()).equals(dollarTag)) {
                            cur.append(dollarTag);
                            state = State.NORMAL;
                            i += dollarTag.length();
                            dollarTag = null;
                        } else {
                            cur.append(c);
                            i++;
                        }
                        break;

                    case IN_ORACLE_Q_QUOTE:
                        cur.append(c);
                        if (c == oracleQuoteClose && i + 1 < n && sql.charAt(i + 1) == '\'') {
                            cur.append('\'');
                            state = State.NORMAL;
                            i += 2;
                        } else {
                            i++;
                        }
                        break;
                }
            }

            flush();
            return stmts;
        }

        private void flush() {
            String s = cur.toString().trim();
            // 仅含注释/空白的单元不作为语句：整段被注释掉的 SQL 不应发往数据库
            // （Oracle 对纯注释文本报 ORA-00900）。
            if (!s.isEmpty() && hasExecutableContent(s, plsql)) stmts.add(s);
            cur.setLength(0);
        }

        /**
         * 剥离 {@code --} 行注释与 {@code /* ... *}{@code /} 块注释后是否仍有非空白内容。
         * 引号内的注释起始符不误判（如 {@code SELECT '--'} 为可执行语句）。
         */
        private static boolean isBlank(StringBuilder sb) {
            for (int k = 0; k < sb.length(); k++) {
                if (!Character.isWhitespace(sb.charAt(k))) return false;
            }
            return true;
        }

        /** {@code i} 处为 {@code /}，且该行除首尾空白外仅有此 {@code /}（SQL*Plus 终止符）。 */
        private static boolean isLineAloneSlash(String sql, int i) {
            int k = i - 1;
            while (k >= 0 && (sql.charAt(k) == ' ' || sql.charAt(k) == '\t')) k--;
            if (k >= 0 && sql.charAt(k) != '\n' && sql.charAt(k) != '\r') return false;
            int j = i + 1;
            int n = sql.length();
            while (j < n && (sql.charAt(j) == ' ' || sql.charAt(j) == '\t')) j++;
            return j >= n || sql.charAt(j) == '\n' || sql.charAt(j) == '\r';
        }

        /** {@code i} 所在行换行符之后的位置（无换行则 EOF）。 */
        private static int advancePastLine(String sql, int i) {
            int n = sql.length();
            int j = i + 1;
            while (j < n && sql.charAt(j) != '\n') j++;
            return j < n ? j + 1 : n;
        }

    }
}
