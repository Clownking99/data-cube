package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlConnectionGuidanceTest {
    private static ConnConfig config(String name, DbType type) {
        return new ConnConfig(name, name, type, "example.invalid", type.defaultPort(),
                "db", "user", "sentinel-secret", Map.of());
    }

    @Test void missingConnectionExplainsSelectionAndBlocksExecution() {
        var state = SqlConnectionGuidance.from(null, null);
        assertFalse(state.hasConnection());
        assertEquals("请先在左侧选择 PostgreSQL 或 Oracle 连接，再执行 SQL", state.text());
        assertTrue(state.blocksExecution(false));
        assertTrue(state.blocksExecution(true));
    }

    @Test void redisExplainsConsoleAndBlocksExecution() {
        var state = SqlConnectionGuidance.from(null, config("redis", DbType.REDIS));
        assertFalse(state.hasConnection());
        assertEquals("Redis 不支持 SQL，请使用其控制台", state.text());
        assertTrue(state.blocksExecution(false));
        assertTrue(state.blocksExecution(true));
    }

    @ParameterizedTest @EnumSource(value = DbType.class, names = {"POSTGRESQL", "ORACLE"})
    void candidatesRemainPendingAndBusyNeverReenablesExecution(DbType type) {
        var state = SqlConnectionGuidance.from(null, config("candidate", type));
        assertTrue(state.hasConnection());
        assertEquals("首次执行或会话操作将固定当前连接，之后切换左侧连接不影响此页", state.text());
        assertFalse(state.blocksExecution(false));
        assertTrue(state.blocksExecution(true));
    }

    @ParameterizedTest @EnumSource(value = DbType.class, names = {"POSTGRESQL", "ORACLE"})
    void pinnedConnectionWinsOverMissingRedisOrOtherCandidate(DbType type) {
        for (ConnConfig candidate : new ConnConfig[]{null, config("B", DbType.ORACLE),
                config("redis", DbType.REDIS)}) {
            var state = SqlConnectionGuidance.from(config("A", type), candidate);
            assertTrue(state.hasConnection());
            assertEquals("", state.text());
            assertFalse(state.blocksExecution(false));
            assertTrue(state.blocksExecution(true));
        }
    }
}
