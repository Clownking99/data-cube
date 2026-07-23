package com.datacube.spi.model;

/**
 * 序列编辑草稿：承载序列的完整可编辑属性，兼作“读取结果”与“编辑态”双用途（同 {@link TableDraft} 做法）。
 *
 * <p>数值字段用 {@code String} 承载（Oracle 序列取值可达 28 位，超出 {@code long}），
 * DDL 生成时按需拼接；{@code cacheSize} 为 int（0 表示 NOCACHE）。{@code order} 仅
 * Oracle 有意义（PG 无此概念，恒为 false）。
 *
 * @param schema      所有者/模式
 * @param name        序列名
 * @param minValue    最小值
 * @param maxValue    最大值
 * @param incrementBy 递增值
 * @param nextValue   下一个数字（Oracle=LAST_NUMBER；PG=last_value 推算）
 * @param cacheSize   缓存大小（0 表示 NOCACHE）
 * @param cycle       是否循环
 * @param order       是否有序（仅 Oracle）
 */
public record SequenceDraft(String schema, String name, String minValue, String maxValue,
                            String incrementBy, String nextValue, int cacheSize,
                            boolean cycle, boolean order) {
}
