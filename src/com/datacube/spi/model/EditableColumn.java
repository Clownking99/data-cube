package com.datacube.spi.model;

/**
 * 列的可编辑元数据：网格内联编辑所需的类型/约束信息。
 *
 * <p>由 {@code DataEditor.columns(TableRef)} 一次性探测（{@code SELECT * WHERE 1=0}
 * 的 {@code ResultSetMetaData} + 主键信息）后交给 UI 配置列可编辑性、
 * 类型强转与 WHERE 定位。
 *
 * @param name          列名（与网格列一一对应）
 * @param jdbcType      {@link java.sql.Types} 整型，用于文本→值强转与绑定
 * @param typeName      数据库类型名（诊断/提示用）
 * @param nullable      是否允许 NULL
 * @param primaryKey    是否主键列
 * @param autoIncrement 是否自增/标识列（INSERT 时可省略）
 * @param editable      是否可在网格内编辑（LOB/二进制/ROWID/只读列为 false）
 * @param comment       列注释（best-effort；无则为 null）
 */
public record EditableColumn(
        String name,
        int jdbcType,
        String typeName,
        boolean nullable,
        boolean primaryKey,
        boolean autoIncrement,
        boolean editable,
        String comment) {
}
