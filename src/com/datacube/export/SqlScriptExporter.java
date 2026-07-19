package com.datacube.export;

import com.datacube.spi.SqlDialect;
import com.datacube.spi.model.TableRef;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * SQL 脚本导出：DDL + 批量 INSERT。
 *
 * <p>标识符引用交给 {@link SqlDialect#quoteIdentifier}；值字面量按 PG 兼容格式转义。
 * 每 {@link #BATCH} 行合并为一条多值 INSERT，兼顾体积与可读性。
 */
public final class SqlScriptExporter {

    private static final int BATCH = 100;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SqlScriptExporter() {
    }

    /**
     * 写出 SQL 脚本。
     *
     * @param out     目标文件
     * @param t       表引用（用于 INSERT 目标）
     * @param content 导出内容（结构/数据/两者）
     * @param ddl     建表 DDL（需要结构时不为空；否则忽略）
     * @param columns 列名（数据导出时的列序）
     * @param feed    数据行来源（仅数据导出时使用）
     * @param dialect 方言（标识符引用）
     */
    public static void write(File out, TableRef t, ExportContent content, String ddl,
                             List<String> columns, RowFeed feed, SqlDialect dialect) throws Exception {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(out), StandardCharsets.UTF_8))) {

            w.write("-- DataCube 导出\n");
            w.write("-- 表: " + t.qualified() + "\n");
            w.write("-- 时间: " + LocalDateTime.now().format(TS) + "\n\n");

            if (content.includesStructure() && ddl != null && !ddl.isBlank()) {
                String d = ddl.strip();
                w.write(d);
                if (!d.endsWith(";")) w.write(";");
                w.write("\n\n");
            }

            if (content.includesData()) {
                writeInserts(w, t, columns, feed, dialect);
            }
        }
    }

    private static void writeInserts(Writer w, TableRef t, List<String> columns,
                                     RowFeed feed, SqlDialect dialect) throws Exception {
        String target = qualifiedName(t, dialect);
        StringBuilder colList = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) colList.append(", ");
            colList.append(dialect.quoteIdentifier(columns.get(i)));
        }
        String prefix = "INSERT INTO " + target + " (" + colList + ") VALUES\n";

        int[] inBatch = {0};
        feed.forEach(values -> {
            try {
                if (inBatch[0] == 0) {
                    w.write(prefix);
                } else {
                    w.write(",\n");
                }
                w.write("  (");
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) w.write(", ");
                    w.write(literal(values.get(i)));
                }
                w.write(")");
                inBatch[0]++;
                if (inBatch[0] >= BATCH) {
                    w.write(";\n");
                    inBatch[0] = 0;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        if (inBatch[0] > 0) {
            w.write(";\n");
        }
    }

    private static String qualifiedName(TableRef t, SqlDialect dialect) {
        String name = dialect.quoteIdentifier(t.name());
        if (t.schema() == null || t.schema().isEmpty()) return name;
        return dialect.quoteIdentifier(t.schema()) + "." + name;
    }

    /** 值 → SQL 字面量。 */
    static String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean b) return b ? "TRUE" : "FALSE";
        if (v instanceof byte[] bytes) return "'\\x" + hex(bytes) + "'";
        String s = v.toString();
        return "'" + s.replace("'", "''") + "'";
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
