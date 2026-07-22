package com.datacube.spi.model;

import java.util.List;

/**
 * 索引草稿（不可变 record）。
 *
 * @param name    索引名
 * @param unique  是否唯一索引
 * @param columns 索引列（有序）
 */
public record IndexDraft(String name, boolean unique, List<String> columns) {
    public IndexDraft {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
