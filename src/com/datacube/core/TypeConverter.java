package com.datacube.core;

import java.util.Map;

public class TypeConverter {

    public static String convertType(String type, int len, int prec, int scale) {
        switch (type) {
            case "VARCHAR2": case "NVARCHAR2": return "VARCHAR(" + len + ")";
            case "CHAR": case "NCHAR": return "CHAR(" + len + ")";
            case "NUMBER":
                if (prec == 0 && scale == 0) return "NUMERIC";
                if (scale > 0) return "NUMERIC(" + prec + "," + scale + ")";
                return "NUMERIC(" + prec + ")";
            case "INTEGER": return "INTEGER";
            case "FLOAT": case "BINARY_FLOAT": case "BINARY_DOUBLE": return "DOUBLE PRECISION";
            case "DATE": case "TIMESTAMP": return "TIMESTAMP";
            case "TIMESTAMP WITH TIME ZONE": return "TIMESTAMPTZ";
            case "CLOB": case "NCLOB": case "LONG": return "TEXT";
            case "BLOB": case "RAW": case "LONG RAW": return "BYTEA";
            case "ROWID": return "VARCHAR(18)";
            case "XMLTYPE": return "XML";
            default: return type;
        }
    }

    public static String convertDefault(String v) {
        if ("SYSDATE".equalsIgnoreCase(v) || "SYSTIMESTAMP".equalsIgnoreCase(v)) return "CURRENT_TIMESTAMP";
        if (v.startsWith("SYS_GUID()")) return "gen_random_uuid()";
        return v;
    }

    public static boolean isBoolComment(Map<String, Map<String, String>> commentsCache, String table, String colName) {
        Map<String, String> cols = commentsCache.get(table);
        if (cols == null) return false;
        String comment = cols.get(colName);
        if (comment == null) return false;
        String c = comment.toLowerCase();
        if (comment.contains("是") && comment.contains("否")) return true;
        if (c.contains("true") && c.contains("false")) return true;
        if (c.contains("yes") && c.contains("no")) return true;
        if (c.contains("y/n") || c.contains("y\\n")) return true;
        return false;
    }

    public static String escapeComment(String s) {
        if (s == null) return "";
        return s.replace("*/", "* /").replace("/*", "/ *");
    }
}
