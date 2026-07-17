package com.datacube.spi.model;

/**
 * 表/视图信息。
 */
public record TableInfo(String schema, String name, Kind kind, String comment) {
    public enum Kind { TABLE, VIEW }

    public TableRef ref() {
        return new TableRef(schema, name);
    }
}
