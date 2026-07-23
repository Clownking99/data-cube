package com.datacube.provider.postgres;

import com.datacube.spi.SequenceDdlBuilder;
import com.datacube.spi.model.SequenceDraft;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 序列 DDL 构建器：diff 生成 {@code ALTER SEQUENCE} 脚本。
 *
 * <p>标识符双引号（保留传入的真实大小写）；循环用 {@code CYCLE}/{@code NO CYCLE}；
 * 下一个数字变更用 {@code RESTART WITH n}。PG 无 ORDER 概念，忽略该字段。
 * PG 缓存下限为 1（{@code cacheSize<1} 时按 1 生成）。
 */
public final class PgSequenceDdlBuilder implements SequenceDdlBuilder {

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
            clauses.add("CACHE " + Math.max(1, edited.cacheSize()));
        }
        if (original.cycle() != edited.cycle()) {
            clauses.add(edited.cycle() ? "CYCLE" : "NO CYCLE");
        }
        if (changed(original.nextValue(), edited.nextValue())) {
            clauses.add("RESTART WITH " + edited.nextValue().trim());
        }

        if (clauses.isEmpty()) return "";
        return "ALTER SEQUENCE " + seq + " " + String.join(" ", clauses) + ";";
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
