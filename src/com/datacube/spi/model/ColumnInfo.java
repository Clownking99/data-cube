package com.datacube.spi.model;

/**
 * 列元数据（用于对象树/DDL）。与 {@code core.ColumnInfo}（迁移专用、可变）区分。
 */
public record ColumnInfo(
        String name,
        String typeName,
        boolean nullable,
        String defaultValue,
        int ordinal,
        boolean primaryKey,
        String comment) {
}
