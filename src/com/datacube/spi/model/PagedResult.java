package com.datacube.spi.model;

import java.util.List;

/**
 * 分页数据结果：列名 + 当前页数据行 + 是否还有更多。
 */
public record PagedResult(List<String> columns, List<List<Object>> rows, boolean hasMore) {
    public PagedResult {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
