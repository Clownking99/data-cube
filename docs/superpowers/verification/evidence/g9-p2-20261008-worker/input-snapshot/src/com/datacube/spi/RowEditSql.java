package com.datacube.spi;

import com.datacube.spi.model.RowKey;
import com.datacube.spi.model.TableRef;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** Shared parameterized row SQL for preview and execution; performs no I/O. */
public final class RowEditSql {
    private RowEditSql() { }
    public static String qualify(TableRef table, UnaryOperator<String> quote) {
        return (table.schema() == null || table.schema().isEmpty() ? "" : quote.apply(table.schema()) + ".")
                + quote.apply(table.name());
    }
    public static String insert(String table, List<String> columns, UnaryOperator<String> quote) {
        if (columns.isEmpty()) throw new IllegalArgumentException("新增行至少需要填写一个列值");
        return "INSERT INTO " + table + " (" + columns.stream().map(quote).collect(Collectors.joining(", "))
                + ") VALUES (" + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
    }
    public static String update(String table, List<String> columns, RowKey key, UnaryOperator<String> quote) {
        if (columns.isEmpty()) throw new IllegalArgumentException("没有需要保存的修改");
        return "UPDATE " + table + " SET " + columns.stream().map(c -> quote.apply(c) + " = ?")
                .collect(Collectors.joining(", ")) + where(key, quote);
    }
    public static String delete(String table, RowKey key, UnaryOperator<String> quote) {
        return "DELETE FROM " + table + where(key, quote);
    }
    public static String where(RowKey key, UnaryOperator<String> quote) {
        if (key == null || key.columns().isEmpty() || key.columns().size() != key.values().size()
                || key.columns().stream().distinct().count() != key.columns().size())
            throw new IllegalArgumentException("无法安全定位行");
        StringBuilder sql = new StringBuilder(" WHERE ");
        for (int i = 0; i < key.columns().size(); i++) {
            if (i > 0) sql.append(" AND ");
            sql.append(quote.apply(key.columns().get(i))).append(key.values().get(i) == null ? " IS NULL" : " = ?");
        }
        return sql.toString();
    }
}
