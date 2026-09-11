package com.datacube.sqleditor;

import com.datacube.spi.model.DbType;
import com.datacube.spi.model.TableRef;

/** Pure template generation for the two supported SQL databases; never resolves a provider. */
public final class TableSelectSql {
    public static final int MAX_IDENTIFIER = 1024;
    private TableSelectSql() {}

    public static String generate(DbType type, TableRef table) {
        if (type != DbType.POSTGRESQL && type != DbType.ORACLE)
            throw new IllegalArgumentException("仅支持 PostgreSQL / Oracle 的表和视图。");
        if (table == null) throw invalidName();
        return "SELECT *\nFROM " + quote(table.schema()) + "." + quote(table.name()) + ";";
    }

    private static String quote(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_IDENTIFIER) throw invalidName();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isISOControl(ch) || ch == '\u2028' || ch == '\u2029') throw invalidName();
            if (Character.isHighSurrogate(ch)) {
                if (++i == name.length() || !Character.isLowSurrogate(name.charAt(i))) throw invalidName();
            } else if (Character.isLowSurrogate(ch)) throw invalidName();
        }
        // Both PostgreSQL and Oracle use double quotes; never fold or split metadata identifiers.
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static IllegalArgumentException invalidName() {
        return new IllegalArgumentException("Schema 或对象名称为空、超过 1024 个字符，或包含无法原样保留的字符。");
    }
}
