package com.datacube.spi.model;

/**
 * 列草稿：表设计器的可变编辑态快照单元（不可变 record）。
 *
 * <p>类型以「类型文本」承载（如 {@code VARCHAR2(50)} / {@code numeric(10,2)}），
 * 不建模各方言类型系统；合法性由数据库执行时校验。
 *
 * @param name         列名
 * @param typeText     类型文本
 * @param nullable     是否可空
 * @param defaultValue 默认值 SQL 片段（可空/空白表示无默认）
 * @param comment      列注释（可空/空白表示无注释）
 */
public record ColumnDraft(
        String name,
        String typeText,
        boolean nullable,
        String defaultValue,
        String comment) {
}
