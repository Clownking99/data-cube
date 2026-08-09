package com.datacube.config;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void loadsBackupWhenPrimaryStructureIsCorruptWithoutOverwritingPrimary() throws Exception {
        Path file = tempDir.resolve("connections.json");
        ConnectionStore store = new ConnectionStore(file);
        ConnConfig first = config("first", DbType.POSTGRESQL);
        store.saveAll(List.of(first));
        store.saveAll(List.of(config("second", DbType.REDIS)));
        String corrupt = "[] trailing ]";
        Files.writeString(file, corrupt);

        assertEquals(List.of(first), store.loadAll());
        assertEquals(corrupt, Files.readString(file));
    }

    @Test
    void savingOverCorruptPrimaryKeepsLastValidBackup() throws Exception {
        Path file = tempDir.resolve("connections.json");
        Path backup = tempDir.resolve("connections.json.bak");
        ConnectionStore store = new ConnectionStore(file);
        ConnConfig first = config("first", DbType.POSTGRESQL);
        store.saveAll(List.of(first));
        store.saveAll(List.of(config("second", DbType.REDIS)));
        Files.writeString(file, "corrupt");

        store.saveAll(List.of(config("third", DbType.ORACLE)));

        assertEquals(List.of(first), new ConnectionStore(backup).loadAll());
        assertEquals("third", store.loadAll().getFirst().id());
    }

    @Test
    void skipsMalformedEntryAndKeepsValidSibling() throws Exception {
        Path file = tempDir.resolve("connections.json");
        Files.writeString(file, """
                [
                  {"name":"bad","type":"REDIS","port":6379},
                  {"id":"ok","name":"ok","type":"REDIS","host":"localhost","port":6379,"database":"0","username":"","encryptedPassword":""}
                ]
                """);

        List<ConnConfig> loaded = new ConnectionStore(file).loadAll();

        assertEquals(1, loaded.size());
        assertEquals("ok", loaded.getFirst().id());
    }

    @Test
    void persistsOnlyRelationalSafetyPropertiesAndKeepsRedisShape() throws Exception {
        Path file = tempDir.resolve("connections.json");
        ConnectionStore store = new ConnectionStore(file);
        ConnConfig postgres = new ConnConfig("pg", "pg", DbType.POSTGRESQL, "localhost", 5432,
                "db", "user", "encrypted", Map.of(
                        "environment", "PRODUCTION",
                        "readOnly", "true",
                        "queryTimeoutSeconds", "15",
                        "__plainPassword", "secret",
                        "driverFlag", "not-persistent"));
        ConnConfig redis = new ConnConfig("redis", "redis", DbType.REDIS, "localhost", 6379,
                "0", "", "encrypted", Map.of("environment", "PRODUCTION"));

        store.saveAll(List.of(postgres, redis));

        String json = Files.readString(file);
        List<ConnConfig> loaded = store.loadAll();
        assertEquals(Map.of(
                "environment", "PRODUCTION",
                "readOnly", "true",
                "queryTimeoutSeconds", "15"), loaded.get(0).props());
        assertEquals(Map.of(), loaded.get(1).props());
        assertFalse(json.contains("__plainPassword"));
        assertFalse(json.contains("driverFlag"));
    }

    @Test
    void defaultsMissingAndInvalidSafetyValuesFromLegacyRelationalJson() throws Exception {
        Path file = tempDir.resolve("connections.json");
        Files.writeString(file, """
                [
                  {"id":"missing","name":"missing","type":"POSTGRESQL","host":"localhost","port":5432,"database":"db","username":"user","encryptedPassword":"encrypted"},
                  {"id":"invalid","name":"invalid","type":"ORACLE","host":"localhost","port":1521,"database":"svc","username":"user","encryptedPassword":"encrypted","environment":"unknown","readOnly":"not-boolean","queryTimeoutSeconds":"3601"}
                ]
                """);

        List<ConnConfig> loaded = new ConnectionStore(file).loadAll();
        Map<String, String> defaults = Map.of(
                "environment", "DEVELOPMENT",
                "readOnly", "false",
                "queryTimeoutSeconds", "60");

        assertEquals(defaults, loaded.get(0).props());
        assertEquals(defaults, loaded.get(1).props());
    }

    @Test
    void acceptsNonCanonicalTimeoutTextWithoutInvalidValueWarning() throws Exception {
        Path file = tempDir.resolve("connections.json");
        Files.writeString(file, """
                [
                  {"id":"leading-zero","name":"leading-zero","type":"POSTGRESQL","host":"localhost","port":5432,"database":"db","username":"user","encryptedPassword":"encrypted","environment":"DEVELOPMENT","readOnly":"false","queryTimeoutSeconds":"060"},
                  {"id":"leading-plus","name":"leading-plus","type":"ORACLE","host":"localhost","port":1521,"database":"svc","username":"user","encryptedPassword":"encrypted","environment":"TEST","readOnly":"true","queryTimeoutSeconds":"+60"}
                ]
                """);
        Logger logger = Logger.getLogger(ConnectionStore.class.getName());
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);

        List<ConnConfig> loaded;
        try {
            loaded = new ConnectionStore(file).loadAll();
        } finally {
            logger.removeHandler(handler);
        }

        assertEquals("60", loaded.get(0).props().get("queryTimeoutSeconds"));
        assertEquals("60", loaded.get(1).props().get("queryTimeoutSeconds"));
        assertFalse(messages.stream().anyMatch(message -> message.contains("查询超时值无效")));
    }

    private static ConnConfig config(String id, DbType type) {
        Map<String, String> props = type == DbType.REDIS ? Map.of() : Map.of(
                "environment", "DEVELOPMENT",
                "readOnly", "false",
                "queryTimeoutSeconds", "60");
        return new ConnConfig(id, id, type, "localhost", type.defaultPort(), "0", "user", "encrypted", props);
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
