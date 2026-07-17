package com.datacube.spi.model;

/**
 * 表/视图引用：schema + 名称。跨层传递的轻量定位符。
 */
public record TableRef(String schema, String name) {
    public String qualified() {
        return (schema == null || schema.isEmpty()) ? name : schema + "." + name;
    }
}
