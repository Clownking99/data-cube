package com.datacube.provider.oracle;

import com.datacube.spi.SequenceDdlBuilder;
import com.datacube.spi.model.SequenceDraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Oracle 序列 DDL 构建器：diff 生成 {@code ALTER SEQUENCE} 脚本。
 *
 * <p>标识符双引号（保留传入的真实大小写）。缓存为 0 时用 {@code NOCACHE}；
 * 循环/有序用 {@code NOCYCLE}/{@code NOORDER}。下一个数字变更用
 * {@code RESTART START WITH n}（Oracle 12.2+；旧版本不支持时请手动调整）。
 */
public final class OracleSequenceDdlBuilder implements SequenceDdlBuilder {

    @Override
    public String alterScript(SequenceDraft original, SequenceDraft edited) {
        String seq = qualified(edited.schema(), edited.name());
        List<String> clauses = new ArrayList<>();

        if (changed(original.incrementBy(), edited.incrementBy())) {
            clauses.add("INCREMENT BY " + edited.incrementBy().trim());
        }
        if (changed(original.minValue(), edited.minValue())) {
            clauses.add("MINVALUE " + edited.minValue().trim());
        }
        if (changed(original.maxValue(), edited.maxValue())) {
            clauses.add("MAXVALUE " + edited.maxValue().trim());
        }
        if (original.cacheSize() != edited.cacheSize()) {
            clauses.add(edited.cacheSize() > 0 ? "CACHE " + edited.cacheSize() : "NOCACHE");
        }
        if (original.cycle() != edited.cycle()) {
            clauses.add(edited.cycle() ? "CYCLE" : "NOCYCLE");
        }
        if (original.order() != edited.order()) {
            clauses.add(edited.order() ? "ORDER" : "NOORDER");
        }

        List<String> stmts = new ArrayList<>();
        if (!clauses.isEmpty()) {
            stmts.add("ALTER SEQUENCE " + seq + " " + String.join(" ", clauses) + ";");
        }
        // 下一个数字变更：RESTART START WITH（Oracle 12.2+）
        if (changed(original.nextValue(), edited.nextValue())) {
            stmts.add("ALTER SEQUENCE " + seq + " RESTART START WITH " + edited.nextValue().trim() + ";");
        }
        return String.join("\n", stmts);
    }

    private static boolean changed(String a, String b) {
        return !norm(a).equals(norm(b));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    private static String quote(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static String qualified(String schema, String name) {
        return (schema == null || schema.isEmpty()) ? quote(name) : quote(schema) + "." + quote(name);
    }
}
