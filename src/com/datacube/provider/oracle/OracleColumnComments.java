package com.datacube.provider.oracle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Oracle 结果列注释解析：best-effort，与 {@code PgColumnComments} 对等。
 *
 * <p>按每列底层 schema/table/column（Oracle thin 驱动为映射到真实表列的字段填充），
 * 用一条 {@code ALL_COL_COMMENTS} 查询批量取回注释。表达式、别名、聚合列取不到，
 * 对应位置为 {@code null}。任何异常静默兜底，返回 {@code null}。
 */
final class OracleColumnComments {

    private OracleColumnComments() {}

    /**
     * 返回与列平行的注释列表（元素可为 null）。若无任何表列或发生异常，返回 {@code null}。
     *
     * <p>Oracle thin 驱动的 {@link ResultSetMetaData} 不返回表名/模式名（恒为空串），
     * 故标准元数据路径在 Oracle 上取不到；此时回退到 {@link #resolveBySingleTable}，
     * 解析单表 {@code FROM} 并按列名回填。
     */
    static List<String> resolve(Connection conn, ResultSetMetaData md, String sql, String defaultSchema) {
        try {
            int colCount = md.getColumnCount();
            String[] schemas = new String[colCount];
            String[] tables = new String[colCount];
            String[] cols = new String[colCount];
            Set<String> pairs = new LinkedHashSet<>(); // 去重的 schema\u0000table
            boolean any = false;
            for (int i = 1; i <= colCount; i++) {
                String s = trimToNull(md.getSchemaName(i));
                String t = trimToNull(md.getTableName(i));
                String c = trimToNull(md.getColumnName(i));
                if (s != null && t != null && c != null) {
                    schemas[i - 1] = s;
                    tables[i - 1] = t;
                    cols[i - 1] = c;
                    pairs.add(s + '\u0000' + t);
                    any = true;
                }
            }
            if (!any) return resolveBySingleTable(conn, md, sql, defaultSchema);

            Map<String, String> commentByKey = queryComments(conn, pairs);
            if (commentByKey.isEmpty()) return null;

            List<String> out = new ArrayList<>(colCount);
            for (int i = 0; i < colCount; i++) {
                if (schemas[i] == null) {
                    out.add(null);
                } else {
                    out.add(commentByKey.get(schemas[i] + '\u0000' + tables[i] + '\u0000' + cols[i]));
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 对给定 (owner,table) 集合查询全部列注释，键为 owner\u0000table\u0000column。 */
    private static Map<String, String> queryComments(Connection conn, Set<String> pairs) throws Exception {
        Map<String, String> map = new HashMap<>();
        StringBuilder sql = new StringBuilder(
                "SELECT OWNER, TABLE_NAME, COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS "
                + "WHERE COMMENTS IS NOT NULL AND (OWNER, TABLE_NAME) IN (");
        for (int i = 0; i < pairs.size(); i++) {
            sql.append(i == 0 ? "(?,?)" : ",(?,?)");
        }
        sql.append(')');

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (String pair : pairs) {
                int nul = pair.indexOf('\u0000');
                ps.setString(idx++, pair.substring(0, nul));      // owner
                ps.setString(idx++, pair.substring(nul + 1));     // table
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String descr = rs.getString("COMMENTS");
                    if (descr == null || descr.isEmpty()) continue;
                    map.put(rs.getString("OWNER") + '\u0000' + rs.getString("TABLE_NAME")
                            + '\u0000' + rs.getString("COLUMN_NAME"), descr);
                }
            }
        }
        return map;
    }

    /**
     * Oracle 回退：元数据拿不到表名时，解析 SQL 的单表 {@code FROM}，
     * 用 {@code ALL_COL_COMMENTS} 取该表列注释，再按结果列名（大小写不敏感）回填。
     * 仅处理单表 SELECT；含 JOIN/多表/子查询时放弃，避免错配。
     */
    private static List<String> resolveBySingleTable(Connection conn, ResultSetMetaData md,
                                                     String sql, String defaultSchema) throws Exception {
        String[] ot = parseSingleTable(sql);
        if (ot == null) return null;
        String owner = ot[0] != null ? unquote(ot[0]) : trimToNull(defaultSchema);
        if (owner == null) owner = trimToNull(conn.getSchema());
        if (owner == null) return null;
        String table = unquote(ot[1]);
        // 未加引号的标识符在 Oracle 中以大写存储
        String ownerKey = isQuoted(ot[0]) ? owner : owner.toUpperCase();
        String tableKey = isQuoted(ot[1]) ? table : table.toUpperCase();

        Map<String, String> byCol = new HashMap<>();
        String q = "SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS "
                 + "WHERE OWNER = ? AND TABLE_NAME = ? AND COMMENTS IS NOT NULL";
        try (PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, ownerKey);
            ps.setString(2, tableKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String d = rs.getString("COMMENTS");
                    if (d != null && !d.isEmpty()) byCol.put(rs.getString("COLUMN_NAME"), d);
                }
            }
        }
        if (byCol.isEmpty()) return null;

        int colCount = md.getColumnCount();
        List<String> out = new ArrayList<>(colCount);
        boolean anyHit = false;
        for (int i = 1; i <= colCount; i++) {
            String col = trimToNull(md.getColumnName(i));
            String c = null;
            if (col != null) {
                c = byCol.get(col);
                if (c == null) c = byCol.get(col.toUpperCase());
            }
            if (c != null) anyHit = true;
            out.add(c);
        }
        return anyHit ? out : null;
    }

    // 隔断 FROM 表列的子句关键字（遇到其之一即视为 FROM 段结束）
    private static final String[] CLAUSE_KWS = {
            "where", "group", "order", "having", "connect", "start", "model",
            "union", "intersect", "minus", "fetch", "offset", "for",
            "pivot", "unpivot", "sample", "partition"
    };
    // 单个表引用：可选 owner. + 表名，两者可加双引号
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?:(\"[^\"]+\"|[A-Za-z0-9_$#]+)\\s*\\.\\s*)?(\"[^\"]+\"|[A-Za-z0-9_$#]+)");

    /** 解析单表 SELECT 的 FROM，返回 {owner(可含引号,可为null), table(可含引号)}；非单表返回 null。 */
    private static String[] parseSingleTable(String sql) {
        if (sql == null) return null;
        String s = stripComments(sql);
        int from = indexOfTopLevelWord(s, 0, "from");
        if (from < 0) return null;
        int start = from + 4;
        int end = s.length();
        for (String kw : CLAUSE_KWS) {
            int p = indexOfTopLevelWord(s, start, kw);
            if (p >= 0 && p < end) end = p;
        }
        if (start >= end) return null;
        String seg = s.substring(start, end);
        // 顶层逗号 / 子查询括号 / JOIN → 多表，放弃
        if (seg.indexOf(',') >= 0 || seg.indexOf('(') >= 0) return null;
        if (indexOfTopLevelWord(seg, 0, "join") >= 0) return null;
        String t = seg.trim();
        if (t.isEmpty()) return null;
        String first = t.split("\\s+")[0];
        Matcher tm = TABLE_REF.matcher(first);
        if (!tm.matches()) return null;
        return new String[]{tm.group(1), tm.group(2)};
    }

    /** 从 start 起查找顶层（不在括号/字符串/引号标识内）的整词 word，返回下标或 -1。 */
    private static int indexOfTopLevelWord(String s, int start, String word) {
        int depth = 0;
        boolean inStr = false, inQuo = false;
        int n = s.length();
        for (int i = start; i < n; i++) {
            char ch = s.charAt(i);
            if (inStr) { if (ch == '\'') inStr = false; continue; }
            if (inQuo) { if (ch == '"') inQuo = false; continue; }
            if (ch == '\'') { inStr = true; continue; }
            if (ch == '"') { inQuo = true; continue; }
            if (ch == '(') { depth++; continue; }
            if (ch == ')') { if (depth > 0) depth--; continue; }
            if (depth == 0 && matchesWordAt(s, i, word)) return i;
        }
        return -1;
    }

    private static boolean matchesWordAt(String s, int i, String word) {
        int wl = word.length();
        if (i + wl > s.length()) return false;
        if (!s.regionMatches(true, i, word, 0, wl)) return false;
        if (i > 0 && isIdent(s.charAt(i - 1))) return false;
        if (i + wl < s.length() && isIdent(s.charAt(i + wl))) return false;
        return true;
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    /** 去除行注释(--)与块注释，避免干扰 FROM 定位。 */
    private static String stripComments(String sql) {
        String noBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlock.replaceAll("(?m)--[^\\n]*", " ");
    }

    private static boolean isQuoted(String s) {
        return s != null && s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"';
    }

    private static String unquote(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (isQuoted(t)) return t.substring(1, t.length() - 1);
        return t;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
