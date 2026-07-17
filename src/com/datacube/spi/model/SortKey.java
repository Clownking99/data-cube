package com.datacube.spi.model;

/**
 * 排序键：列名 + 升降序。用于表数据浏览的排序请求。
 */
public record SortKey(String column, boolean ascending) {
}
