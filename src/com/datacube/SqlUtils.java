package com.datacube;

import java.io.PrintWriter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class SqlUtils {

    /**
     * 生成 INSERT INTO 语句
     */
    public static String insertSql(String table, List<ColumnInfo> cols, ResultSet rs,
                                   boolean convertBool, Map<String, Map<String, String>> commentsCache) throws SQLException {
        StringBuilder sb = new StringBuilder("INSERT INTO " + table.toLowerCase() + " (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(cols.get(i).name.toLowerCase());
        }
        sb.append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(colVal(rs, cols.get(i), convertBool, commentsCache));
        }
        sb.append(");");
        return sb.toString();
    }

    /**
     * 将 ResultSet 中的列值转为 SQL 字面量
     */
    public static String colVal(ResultSet rs, ColumnInfo col, boolean convertBool,
                                Map<String, Map<String, String>> commentsCache) throws SQLException {
        Object val = rs.getObject(col.name);
        if (rs.wasNull() || val == null) return "NULL";

        switch (col.type) {
            case "VARCHAR2": case "NVARCHAR2": case "CHAR": case "NCHAR":
                return escapeStr(val.toString());
            case "NUMBER":
                if (col.precision == 1 && col.scale == 0 && convertBool
                        && TypeConverter.isBoolComment(commentsCache, col.table, col.name)) {
                    return ((Number) val).intValue() == 0 ? "FALSE" : "TRUE";
                }
                return val.toString();
            case "INTEGER": case "FLOAT": case "BINARY_FLOAT": case "BINARY_DOUBLE":
                return val.toString();
            case "DATE": case "TIMESTAMP": case "TIMESTAMP WITH TIME ZONE":
                Timestamp ts = rs.getTimestamp(col.name);
                return ts != null ? "'" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(ts) + "'" : "NULL";
            case "CLOB": case "NCLOB":
                String s = val.toString();
                if (s.length() > 50000) s = s.substring(0, 50000);
                return escapeStr(s);
            case "BLOB": case "RAW":
                if (val instanceof byte[]) {
                    byte[] b = (byte[]) val;
                    if (b.length > 10000) return "NULL";
                    StringBuilder h = new StringBuilder();
                    for (byte x : b) h.append(String.format("%02x", x));
                    return "'\\x" + h + "'::bytea";
                }
                return "NULL";
            default:
                return escapeStr(val.toString());
        }
    }

    /**
     * 字符串转义为 SQL 字面量（含单引号包裹）
     */
    public static String escapeStr(String s) {
        if (s == null) return "NULL";
        return "'" + s.replace("\\", "\\\\").replace("'", "''")
                .replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t").replace("\0", "") + "'";
    }

    /**
     * SQL 字符串转义（不含单引号包裹，用于 COMMENT ON）
     */
    public static String escapeSql(String s) {
        return s != null ? s.replace("\\", "\\\\").replace("'", "''") : "";
    }

    /**
     * 分割 SQL 语句（支持 $$ 美元引用）
     */
    public static List<String> splitSql(String sql) {
        List<String> stmts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inDollar = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (c == '$') {
                int j = i;
                while (j < sql.length() && sql.charAt(j) == '$') j++;
                int cnt = j - i;

                if (cnt >= 2) {
                    if (!inDollar) {
                        inDollar = true;
                        for (int k = 0; k < cnt; k++) cur.append('$');
                        i = j - 1; continue;
                    } else {
                        inDollar = false;
                        for (int k = 0; k < cnt; k++) cur.append('$');
                        i = j - 1; continue;
                    }
                }
            }

            if (inDollar) { cur.append(c); continue; }

            if (c == ';') {
                String s = cur.toString().trim();
                if (!s.isEmpty()) stmts.add(s);
                cur = new StringBuilder();
                continue;
            }
            cur.append(c);
        }

        String last = cur.toString().trim();
        if (!last.isEmpty()) stmts.add(last);
        return stmts;
    }

    /**
     * 写入 SQL 文件头
     */
    public static void header(PrintWriter w, String title) {
        w.println("-- ============================================");
        w.println("-- Oracle → PostgreSQL 迁移脚本");
        w.println("-- " + title);
        w.println("-- 生成时间: " + new Date());
        w.println("-- ============================================");
        w.println();
    }

    /**
     * 列名拼接（小写，逗号分隔）
     */
    public static String joinLower(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).toLowerCase());
        }
        return sb.toString();
    }
}
