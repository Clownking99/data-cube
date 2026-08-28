package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;

/** A UI projection of existing admission state; does not open or pin a connection. */
record SqlConnectionGuidance(boolean hasConnection, String text) {
    static SqlConnectionGuidance from(ConnConfig pinned, ConnConfig candidate) {
        if (pinned != null && pinned.type() != DbType.REDIS) {
            return new SqlConnectionGuidance(true, "");
        }
        if (candidate == null) {
            return new SqlConnectionGuidance(false,
                    "请先在左侧选择 PostgreSQL 或 Oracle 连接，再执行 SQL");
        }
        if (candidate.type() == DbType.REDIS) {
            return new SqlConnectionGuidance(false, "Redis 不支持 SQL，请使用其控制台");
        }
        return new SqlConnectionGuidance(true,
                "首次执行或会话操作将固定当前连接，之后切换左侧连接不影响此页");
    }

    boolean blocksExecution(boolean busy) { return busy || !hasConnection; }
}
