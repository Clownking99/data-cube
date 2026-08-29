package com.datacube.spi.model;

import java.sql.Types;

public record ResultColumn(int index, String label, int jdbcType, String jdbcTypeName) {
    public ResultColumn {
        if (index < 0) throw new IllegalArgumentException("index must be non-negative");
        label = label == null ? "" : label;
        jdbcTypeName = jdbcTypeName == null ? "" : jdbcTypeName;
    }

    public static ResultColumn unknown(int index, String label) {
        return new ResultColumn(index, label, Types.OTHER, "OTHER");
    }
}
