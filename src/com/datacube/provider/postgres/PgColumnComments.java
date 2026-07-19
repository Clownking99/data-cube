package com.datacube.provider.postgres;

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

/**
 * PostgreSQL 结果列注释解析：best-effort。
 *
 * <p>JDBC {@link ResultSetMetaData} 不提供列注释；本类按每列的底层
 * schema/table/column（pgjdbc 会为映射到真实表列的字段填充），
 * 用一条 {@code pg_description} 查询批量取回注释。表达式、别名、聚合列取不到，
 * 对应位置为 {@code null}。任何异常静默兜底，返回 {@code null}。
 */
final class PgColumnComments {

    private PgColumnComments() {}

    /**
     * 返回与列平行的注释列表（元素可为 null）。若无任何表列或发生异常，返回 {@code null}。
     */
    static List<String> resolve(Connection conn, ResultSetMetaData md) {
        try {
            int colCount = md.getColumnCount();
            // 每列底层三元组（schema/table/column），非表列为 null
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
            if (!any) return null;

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

    /** 对给定 (schema,table) 集合查询全部列注释，键为 schema\u0000table\u0000column。 */
    private static Map<String, String> queryComments(Connection conn, Set<String> pairs) throws Exception {
        Map<String, String> map = new HashMap<>();
        StringBuilder sql = new StringBuilder(
                "SELECT n.nspname AS s, c.relname AS t, a.attname AS col, d.description AS descr "
                + "FROM pg_description d "
                + "JOIN pg_class c ON c.oid = d.objoid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid "
                + "WHERE d.objsubid > 0 AND (n.nspname, c.relname) IN (");
        for (int i = 0; i < pairs.size(); i++) {
            sql.append(i == 0 ? "(?,?)" : ",(?,?)");
        }
        sql.append(')');

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (String pair : pairs) {
                int nul = pair.indexOf('\u0000');
                ps.setString(idx++, pair.substring(0, nul));      // schema
                ps.setString(idx++, pair.substring(nul + 1));     // table
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String descr = rs.getString("descr");
                    if (descr == null || descr.isEmpty()) continue;
                    map.put(rs.getString("s") + '\u0000' + rs.getString("t") + '\u0000' + rs.getString("col"), descr);
                }
            }
        }
        return map;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
