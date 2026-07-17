package com.datacube.spi.model;

/**
 * Schema 信息。{@code catalog} 可为 null（不区分 catalog 的数据库）。
 */
public record SchemaInfo(String catalog, String name) {
}
