package com.datacube.spi.model;

/**
 * 脚本执行结果包装：序号 + 原 SQL + 执行结果。
 */
public record ScriptOutcome(int index, String sql, QueryResult result) {

    @Override
    public String toString() {
        return "[" + index + "] " + result;
    }
}
