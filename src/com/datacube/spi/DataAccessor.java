package com.datacube.spi;

import com.datacube.spi.model.PagedResult;
import com.datacube.spi.model.SortKey;
import com.datacube.spi.model.TableRef;

import java.sql.SQLException;
import java.util.List;

/**
 * 数据访问能力：表数据分页读取（一期只读）。
 */
public interface DataAccessor {

    /**
     * 分页读取表数据。
     *
     * @param t      目标表
     * @param offset 起始偏移
     * @param limit  每页行数
     * @param sorts  排序键（可空）
     * @param filter WHERE 过滤片段（可空，不含 WHERE 关键字）
     */
    PagedResult page(TableRef t, long offset, int limit, List<SortKey> sorts, String filter) throws SQLException;

    /** 统计总行数（可选带过滤）。 */
    long count(TableRef t, String filter) throws SQLException;
}
