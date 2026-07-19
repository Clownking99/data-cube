package com.datacube.export;

/**
 * 喂行抽象：统一“查询结果内存行”与“单表分页读取”两种数据源。
 *
 * <p>实现方将每一行交给 {@link RowSink}，writer 按需写出，避免全量驻留内存。
 */
@FunctionalInterface
public interface RowFeed {
    void forEach(RowSink sink) throws Exception;
}
