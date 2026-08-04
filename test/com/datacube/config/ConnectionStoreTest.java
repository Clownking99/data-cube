package com.datacube.config;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsUnicodeEscapesAndRedisConfiguration() {
        Path file = tempDir.resolve("connections.json");
        ConnectionStore store = new ConnectionStore(file);
        ConnConfig redis = new ConnConfig("redis-一", "缓存 } \" \\ 换行\n名称", DbType.REDIS,
                "redis.local", 6380, "5", "用户", "enc\\密文\n", Map.of());

        store.saveAll(List.of(redis));

        assertEquals(List.of(redis), store.loadAll());
    }

    @Test
    void roundTripsEmptyList() {
        ConnectionStore store = new ConnectionStore(tempDir.resolve("connections.json"));

        store.saveAll(List.of());

        assertEquals(List.of(), store.loadAll());
    }
}
