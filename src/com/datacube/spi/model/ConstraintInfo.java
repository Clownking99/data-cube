package com.datacube.spi.model;

import java.util.List;

/**
 * 约束信息：主键/唯一/外键/检查。
 */
public record ConstraintInfo(String name, Type type, List<String> columns, String definition) {
    public enum Type { PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK }

    public ConstraintInfo {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
