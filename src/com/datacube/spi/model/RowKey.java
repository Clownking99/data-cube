package com.datacube.spi.model;

import java.util.List;

/**
 * 行定位符：UPDATE/DELETE 的 WHERE 条件来源。
 *
 * <p>有主键时取主键列，否则取全部可匹配列（排除 LOB/二进制等显示值被裁剪、
 * 无法可靠匹配的列）。{@code columns} 与 {@code values} 按下标一一对应；
 * 某列旧值为 {@code null} 时写层拼 {@code col IS NULL}（不占位符），
 * 否则拼 {@code col = ?} 并绑定该旧值。
 *
 * @param columns 参与匹配的列名（顺序与 values 对应）
 * @param values  对应列的旧值（可含 {@code null} 表示 SQL NULL）
 */
public record RowKey(List<String> columns, List<Object> values) {
    public RowKey {
        columns = columns == null ? List.of() : List.copyOf(columns);
        // values 可能含 null，不能用 List.copyOf（禁 null）；用不可变包装但保留 null。
        values = values == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(values));
    }
}
