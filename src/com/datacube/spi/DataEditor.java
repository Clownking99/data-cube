package com.datacube.spi;

import com.datacube.spi.model.EditableColumn;
import com.datacube.spi.model.RowKey;
import com.datacube.spi.model.TableRef;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 数据编辑能力：单表行级 INSERT / UPDATE / DELETE（参数化）。
 *
 * <p>与只读的 {@link DataAccessor} 对称。所有写操作用 {@code PreparedStatement}
 * 参数化执行，杜绝注入；类型/NULL/二进制处理集中在实现层，业务/UI 不出现 SQL。
 *
 * <p>值传参约定：{@link LinkedHashMap} 的 value 为文本（{@code String}）；
 * value 为 {@code null} 表示 SQL NULL。实现按列 {@code jdbcType} 把文本强转为
 * 目标 Java 值再绑定。
 */
public interface DataEditor {

    /**
     * 探测表的可编辑列元数据（类型 / 主键 / 可编辑性）。
     *
     * <p>用 {@code SELECT * FROM t WHERE 1=0} 取 {@code ResultSetMetaData}，
     * 叠加主键信息；LOB/二进制/ROWID/只读列标记为不可编辑。列顺序与
     * {@code DataAccessor.page} 的 {@code SELECT *} 一致。
     */
    List<EditableColumn> columns(TableRef t) throws SQLException;

    /**
     * 插入一行。{@code values} 仅含用户实际填写过的列，未触碰列一律省略，
     * 让数据库默认值/序列/自增生效。
     *
     * @return 影响行数（正常为 1）
     */
    int insert(TableRef t, LinkedHashMap<String, String> values) throws SQLException;

    /**
     * 更新一行。{@code newValues} 为被改动的列及其新文本值；{@code key} 定位目标行。
     * 事务内校验「仅影响 1 行」，否则回滚并抛
     * {@link com.datacube.provider.jdbc.RowGuardException}。
     *
     * @return 影响行数（正常为 1）
     */
    int update(TableRef t, LinkedHashMap<String, String> newValues, RowKey key) throws SQLException;

    /**
     * 删除一行。{@code key} 定位目标行；事务内校验「仅影响 1 行」，否则回滚。
     *
     * @return 影响行数（正常为 1）
     */
    int delete(TableRef t, RowKey key) throws SQLException;
}
