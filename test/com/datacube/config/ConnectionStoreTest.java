package com.datacube.config;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void secondSaveCopiesPreviousValidSnapshotToBackup() {
        Path file = tempDir.resolve("connections.json");
        ConnectionStore store = new ConnectionStore(file);
        ConnConfig first = config("first", DbType.POSTGRESQL);
        ConnConfig second = config("second", DbType.REDIS);
        store.saveAll(List.of(first));

        store.saveAll(List.of(second));

        assertEquals(List.of(first), new ConnectionStore(tempDir.resolve("connections.json.bak")).loadAll());
        assertEquals(List.of(second), store.loadAll());
    }

    @Test
    void replacementFailurePreservesPrimaryAndCleansTemporaryFile() throws Exception {
        Path file = tempDir.resolve("connections.json");
        ConnectionStore original = new ConnectionStore(file);
        ConnConfig first = config("first", DbType.POSTGRESQL);
        original.saveAll(List.of(first));
        String before = Files.readString(file);
        ConnectionStore failing = new ConnectionStore(file,
                (source, target, options) -> { throw new IOException("replace blocked"); });

        assertThrows(IllegalStateException.class,
                () -> failing.saveAll(List.of(config("second", DbType.REDIS))));

        assertEquals(before, Files.readString(file));
        assertEquals(List.of(first), original.loadAll());
        assertEquals(0, temporaryFiles(file));
    }

    @Test
    void writeFailurePreservesPrimaryAndCleansTemporaryFile() throws Exception {
        Path file = tempDir.resolve("connections.json");
        ConnectionStore original = new ConnectionStore(file);
        ConnConfig first = config("first", DbType.POSTGRESQL);
        original.saveAll(List.of(first));
        String before = Files.readString(file);
        ConnectionStore failing = new ConnectionStore(file,
                (source, target, options) -> Files.move(source, target, options),
                (target, json) -> { throw new IOException("write blocked"); });

        assertThrows(IllegalStateException.class,
                () -> failing.saveAll(List.of(config("second", DbType.REDIS))));

        assertEquals(before, Files.readString(file));
        assertEquals(0, temporaryFiles(file));
    }

    @Test
    void fallsBackWhenAtomicMoveIsUnsupported() {
        Path file = tempDir.resolve("connections.json");
        AtomicInteger moves = new AtomicInteger();
        ConnectionStore store = new ConnectionStore(file, (source, target, options) -> {
            if (moves.getAndIncrement() == 0) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
            }
            Files.move(source, target, options);
        });

        store.saveAll(List.of(config("redis", DbType.REDIS)));

        assertEquals(2, moves.get());
        assertEquals("redis", store.loadAll().getFirst().id());
    }

    private static ConnConfig config(String id, DbType type) {
        return new ConnConfig(id, id, type, "localhost", type.defaultPort(), "0", "user", "encrypted", Map.of());
    }

    private static long temporaryFiles(Path file) throws IOException {
        String prefix = file.getFileName() + ".";
        try (var paths = Files.list(file.getParent())) {
            return paths.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(prefix) && name.endsWith(".tmp");
            }).count();
        }
    }
}
