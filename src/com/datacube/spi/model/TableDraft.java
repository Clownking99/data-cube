package com.datacube.spi.model;

import java.util.List;

/**
 * 表结构草稿：表设计器编辑态的不可变快照，也是 DDL 生成的输入。
 *
 * <p>{@code primaryKey} 为有序主键列名（空=无主键）；{@code primaryKeyName} 为现有
 * 主键约束名（{@code load} 时填充、新建为 {@code null}，供 PG 删主键用）。
 *
 * @param schema         schema（可空/空白表示未限定）
 * @param name           表名
 * @param tableComment   表注释（可空/空白表示无注释）
 * @param columns        列（有序）
 * @param primaryKey     主键列名（有序，空=无主键）
 * @param primaryKeyName 现有主键约束名（可空）
 * @param indexes        索引
 */
public record TableDraft(
        String schema,
        String name,
        String tableComment,
        List<ColumnDraft> columns,
        List<String> primaryKey,
        String primaryKeyName,
        List<IndexDraft> indexes) {

    public TableDraft {
        columns = columns == null ? List.of() : List.copyOf(columns);
        primaryKey = primaryKey == null ? List.of() : List.copyOf(primaryKey);
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
    }
}
