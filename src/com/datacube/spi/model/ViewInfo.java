package com.datacube.spi.model;

/**
 * 视图信息。
 */
public record ViewInfo(String schema, String name, String definition) {
    public TableRef ref() {
        return new TableRef(schema, name);
    }
}
