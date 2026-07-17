package com.datacube.spi.model;

/**
 * 函数/过程信息。
 */
public record RoutineInfo(String schema, String name, Kind kind, String returnType) {
    public enum Kind { FUNCTION, PROCEDURE }

    public RoutineRef ref() {
        return new RoutineRef(schema, name);
    }
}
