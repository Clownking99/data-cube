package com.datacube.export;

import java.util.List;

/**
 * 行消费者：接收一行数据（按列序）。
 */
@FunctionalInterface
public interface RowSink {
    void row(List<Object> values) throws Exception;
}
