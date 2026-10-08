package com.datacube.config;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlFavoriteStoreTest {
    @TempDir Path temp;
    Path root() { return temp.resolve("favorites"); }
    SqlFavorite favorite(int id,String sql) { return new SqlFavorite(new UUID(0,id),"查询 "+id,"测试",sql,id); }
    Path file(SqlFavorite v) { return root().resolve(v.id()+".favorite"); }
    Path backup(SqlFavorite v) { return root().resolve(v.id()+".favorite-backup"); }
    @Test void distinctIdentityRoundTripsUnicodeAndPreservesPreviousVersionAndDeletesExplicitly() throws Exception {
        var first=favorite(1,"  select '😀中文';\r\n"); var second=favorite(2,first.sql());
        var edited=new SqlFavorite(first.id(),"修改名称","分组", "select 'new'",3);
        try (var store=SqlFavoriteStore.open(root())) { store.save(first,null); store.save(second,null); store.save(edited,first); }
        try (var store=SqlFavoriteStore.open(root())) {
            assertEquals(List.of(edited,second),store.snapshot().favorites());
            assertEquals(first,SqlFavoriteCodec.decode(Files.readAllBytes(backup(first))));
            assertThrows(UnsupportedOperationException.class,() -> store.snapshot().favorites().clear());
            store.delete(edited); assertFalse(Files.exists(file(first))); assertFalse(Files.exists(backup(first)));
            assertEquals(List.of(second),store.snapshot().favorites());
        }
    }
    @Test void staleSaveAndDeleteDoNotOverwriteNewerData() throws Exception {
        var first=favorite(1,"select 1"); var newer=new SqlFavorite(first.id(),"new","", "select 2",4);
        try (var store=SqlFavoriteStore.open(root())) {
            store.save(first,null); store.save(newer,first); byte[] before=Files.readAllBytes(file(first));
            assertEquals(SqlFavoriteStore.Code.CHANGED,assertThrows(SqlFavoriteStore.Failure.class,() -> store.save(first,first)).code());
            assertThrows(SqlFavoriteStore.Failure.class,() -> store.delete(first));
            assertArrayEquals(before,Files.readAllBytes(file(first)));
        }
    }
    @Test void failedAtomicPublishLeavesCurrentAndRecoverablePreviousIntact() throws Exception {
        var first=favorite(1,"select 1");
        try (var store=SqlFavoriteStore.open(root())) { store.save(first,null); }
        try (var store=new SqlFavoriteStore(SqlDraftDirectory.open(root(),SqlDraftDirectory::writeForced,(source,target) -> {
            if (target.getFileName().toString().endsWith(".favorite")) throw new AtomicMoveNotSupportedException("synthetic","synthetic","injected");
            SqlDraftDirectory.moveAtomic(source,target);
        },Files::deleteIfExists))) {
            assertThrows(IOException.class,() -> store.save(new SqlFavorite(first.id(),"new","","select 2",9),first));
            assertEquals(List.of(first),store.snapshot().favorites());
            assertEquals(first,SqlFavoriteCodec.decode(Files.readAllBytes(backup(first))));
        }
        try (var entries=Files.list(root())) { assertTrue(entries.noneMatch(p -> p.toString().endsWith(".tmp"))); }
    }
    @Test void corruptPrimaryRequiresExplicitCopyRecoveryAndOriginalBytesRemain() throws Exception {
        var first=favorite(1,"select 1");
        try (var store=SqlFavoriteStore.open(root())) { store.save(first,null); store.save(new SqlFavorite(first.id(),"new","","select 2",9),first); }
        byte[] corrupt=Files.readAllBytes(file(first)); corrupt[corrupt.length-1]^=1; Files.write(file(first),corrupt);
        try (var store=SqlFavoriteStore.open(root())) {
            assertTrue(store.snapshot().favorites().isEmpty()); assertEquals(1,store.snapshot().protectedCount());
            assertEquals(List.of(first),store.snapshot().recoverable());
            assertEquals(SqlFavoriteStore.Code.PROTECTED,assertThrows(SqlFavoriteStore.Failure.class,() -> store.save(first,null)).code());
            var restored=store.recover(first.id(),10); assertNotEquals(first.id(),restored.id()); assertEquals(first.sql(),restored.sql());
            assertArrayEquals(corrupt,Files.readAllBytes(file(first))); assertEquals(List.of(restored),store.snapshot().favorites());
        }
    }
    @Test void unknownBackupCannotBeSilentlyOverwrittenOrDeleted() throws Exception {
        var first=favorite(1,"select 1");
        try (var store=SqlFavoriteStore.open(root())) { store.save(first,null); }
        byte[] unknown={1,2,3}; Files.write(backup(first),unknown);
        try (var store=SqlFavoriteStore.open(root())) {
            assertEquals(List.of(first),store.snapshot().favorites()); assertEquals(1,store.snapshot().protectedCount());
            assertThrows(SqlFavoriteStore.Failure.class,() -> store.save(first,first)); assertThrows(SqlFavoriteStore.Failure.class,() -> store.delete(first));
            assertArrayEquals(unknown,Files.readAllBytes(backup(first))); assertEquals(first.sql(),store.recover(first.id(),3).sql());
        }
    }
    @Test void countCapacityRefusesNewWithoutEvictionAndDeletionReleasesSlot() throws Exception {
        try (var store=SqlFavoriteStore.open(root())) {
            for (int i=1;i<=100;i++) store.save(favorite(i,"select 1"),null);
            assertEquals(100,store.snapshot().favorites().size());
            assertEquals(SqlFavoriteStore.Code.CAPACITY,assertThrows(SqlFavoriteStore.Failure.class,() -> store.save(favorite(101,"select 2"),null)).code());
            store.delete(favorite(1,"select 1")); store.save(favorite(101,"select 2"),null); assertEquals(100,store.snapshot().favorites().size());
        }
    }
    @Test void totalByteBudgetIncludesRecordsWithoutRaisingHeapOrEvicting() throws Exception {
        String text="x".repeat(SqlFavoriteStore.MAX_SQL_BYTES);
        try (var store=SqlFavoriteStore.open(root())) {
            for (int i=1;i<=63;i++) store.save(favorite(i,text),null);
            assertEquals(SqlFavoriteStore.Code.CAPACITY,assertThrows(SqlFavoriteStore.Failure.class,() -> store.save(favorite(64,text),null)).code());
            assertEquals(63,store.snapshot().favorites().size());
        }
    }
    @Test void utf8LimitMalformedTextAndExclusiveOwnershipAreEnforced() throws Exception {
        assertEquals(favorite(1,"x".repeat(SqlFavoriteStore.MAX_SQL_BYTES)),SqlFavoriteCodec.decode(SqlFavoriteCodec.encode(favorite(1,"x".repeat(SqlFavoriteStore.MAX_SQL_BYTES)))));
        assertThrows(IOException.class,() -> SqlFavoriteCodec.encode(favorite(1,"x".repeat(SqlFavoriteStore.MAX_SQL_BYTES+1))));
        assertThrows(IOException.class,() -> SqlFavoriteCodec.encode(favorite(1,"中".repeat(SqlFavoriteStore.MAX_SQL_BYTES/3+1))));
        assertThrows(IOException.class,() -> SqlFavoriteCodec.encode(favorite(1,"bad\ud800")));
        try (var store=SqlFavoriteStore.open(root())) {
            assertThrows(IOException.class,() -> SqlFavoriteStore.open(root())); store.save(favorite(1,"select 1"),null);
        }
        try (var reopened=SqlFavoriteStore.open(root())) { assertEquals(1,reopened.snapshot().favorites().size()); }
    }
}
