package com.datacube.sqleditor;

import com.datacube.spi.model.DbType;
import com.datacube.spi.model.TableRef;

/** Pure template generation for the two supported SQL databases; never resolves a provider. */
public final class TableSelectSql {
    public static final int MAX_IDENTIFIER = SqlObjectNames.MAX_IDENTIFIER;
    private TableSelectSql() {}

    public static String generate(DbType type, TableRef table) {
        return "SELECT *\nFROM " + SqlObjectNames.qualifiedName(type, table) + ";";
    }
}
