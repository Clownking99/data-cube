package com.datacube.spi.model;

/**
 * 函数/过程引用：schema + 名称。
 */
public record RoutineRef(String schema, String name) {
    public String qualified() {
        return (schema == null || schema.isEmpty()) ? name : schema + "." + name;
    }
}
