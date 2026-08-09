package com.datacube.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlHistoryStoreStrictTest {

    @Test
    void strictRecordSurfacesWriteFailureAndRollsBackMemory(@TempDir Path temp) throws Exception {
        Path parentBlocker = Files.writeString(temp.resolve("not-a-directory"), "block");
        SqlHistoryStore store = new SqlHistoryStore(parentBlocker.resolve("history.txt"));

        assertThrows(IOException.class,
                () -> store.recordStrict("conn", "schema", "select 1"));

        assertTrue(store.recent().isEmpty());
        assertDoesNotThrow(() -> store.record("conn", "schema", "select 1"));
    }
}
