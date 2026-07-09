package com.datacube.sqleditor;

import java.util.ArrayList;
import java.util.List;

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

    public static List<String> split(String sql) {
        if (sql == null || sql.isEmpty()) return new ArrayList<>();
        return new SplitState().run(sql);
    }

    private enum State {
        NORMAL, IN_QUOTE, IN_DQUOTE, IN_LINE_COMMENT, IN_BLOCK_COMMENT, IN_DOLLAR
    }

    /** 单次 split 调用的可变状态。 */
    private static final class SplitState {
        private final StringBuilder cur = new StringBuilder();
        private final List<String> stmts = new ArrayList<>();
        private State state = State.NORMAL;
        private String dollarTag;

        List<String> run(String sql) {
            int n = sql.length();
            int i = 0;

            while (i < n) {
                char c = sql.charAt(i);

                switch (state) {
                    case NORMAL:
                        if (c == '\'') {
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
                            cur.append(c);
                            i++;
                        } else if (c == '$') {
                            int tagEnd = findDollarTagEnd(sql, i);
                            if (tagEnd > i) {
                                String tag = sql.substring(i, tagEnd + 1);
                                cur.append(tag);
                                state = State.IN_DOLLAR;
                                dollarTag = tag;
                                i = tagEnd + 1;
                            } else {
                                cur.append(c);
                                i++;
                            }
                        } else if (c == ';') {
                            String s = cur.toString().trim();
                            if (!s.isEmpty()) stmts.add(s);
                            cur.setLength(0);
                            i++;
                        } else {
                            cur.append(c);
                            i++;
                        }
                        break;

                    case IN_QUOTE:
                        cur.append(c);
                        if (c == '\'') {
                            if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                                cur.append('\'');
                                i += 2;
                            } else {
                                state = State.NORMAL;
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
                        if (c == '\n') state = State.NORMAL;
                        i++;
                        break;

                    case IN_BLOCK_COMMENT:
                        cur.append(c);
                        if (c == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                            cur.append('/');
                            state = State.NORMAL;
                            i += 2;
                        } else {
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
                }
            }

            String tail = cur.toString().trim();
            if (!tail.isEmpty()) stmts.add(tail);
            return stmts;
        }

        /**
         * 从 {@code $} 开始扫描，匹配形如 {@code $tag$} 的结束位置（含两个 $）。
         * 若不存在合法的 dollar tag（如孤立的 {@code $5}），返回 -1。
         */
        private static int findDollarTagEnd(String sql, int start) {
            int n = sql.length();
            if (start + 1 >= n) return -1;
            int j = start + 1;
            while (j < n) {
                char c = sql.charAt(j);
                if (c == '$') return j;
                if (!(Character.isLetterOrDigit(c) || c == '_')) return -1;
                j++;
            }
            return -1;
        }
    }
}