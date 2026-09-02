package com.datacube.fx;

import com.datacube.sqleditor.SqlScriptFileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlFileTabRegistryTest {
    @TempDir Path directory;

    @Test
    void committedAndProvisionalBindingsMoveTransactionallyBetweenOwners() throws Exception {
        FxUiTestSupport.call(() -> {
            SqlFileTabRegistry registry = new SqlFileTabRegistry();
            AtomicInteger firstSelections = new AtomicInteger();
            AtomicInteger secondSelections = new AtomicInteger();
            SqlFileTabRegistry.Owner first = registry.createOwner(firstSelections::incrementAndGet);
            SqlFileTabRegistry.Owner second = registry.createOwner(secondSelections::incrementAndGet);
            Path a = directory.resolve("a.sql").toAbsolutePath().normalize();
            Path b = directory.resolve("b.sql").toAbsolutePath().normalize();

            assertTrue(registry.install(first, a));
            assertTrue(registry.install(second, b));
            assertTrue(registry.select(a));
            assertEquals(1, firstSelections.get());

            assertEquals(SqlFileTabRegistry.Claim.COLLISION, registry.claim(first, b));
            assertEquals(1, secondSelections.get());
            assertTrue(registry.select(a), "collision must retain the original binding");

            registry.release(second);
            assertEquals(SqlFileTabRegistry.Claim.CLAIMED, registry.claim(first, b));
            assertTrue(registry.select(a), "provisional Save As must retain A");
            assertTrue(registry.select(b), "provisional Save As must reserve B");
            registry.rollback(first, b);
            assertFalse(registry.select(b));
            assertTrue(registry.select(a));

            assertEquals(SqlFileTabRegistry.Claim.CLAIMED, registry.claim(first, b));
            registry.commit(first, b);
            assertFalse(registry.select(a), "commit must atomically release A");
            assertTrue(registry.select(b));
            assertTrue(registry.install(second, a));

            registry.release(first);
            registry.release(second);
            assertFalse(registry.select(a));
            assertFalse(registry.select(b));
            return null;
        });
    }

    @Test
    void canonicalStorePathsCollapseAliasesAndCloseClearsEveryBinding() throws Exception {
        Path file = Files.writeString(directory.resolve("canonical.sql"), "select 1");
        Path aliasDirectory = directory.resolve("alias");
        try {
            Files.createSymbolicLink(aliasDirectory, directory);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "symbolic links unavailable for this account");
        }
        SqlScriptFileStore store = new SqlScriptFileStore();
        Path canonical = store.load(file).path();
        Path alias = store.load(aliasDirectory.resolve("canonical.sql")).path();
        assertEquals(canonical, alias);

        FxUiTestSupport.call(() -> {
            SqlFileTabRegistry registry = new SqlFileTabRegistry();
            AtomicInteger selections = new AtomicInteger();
            SqlFileTabRegistry.Owner owner = registry.createOwner(selections::incrementAndGet);
            assertTrue(registry.install(owner, canonical));
            assertTrue(registry.select(alias));
            assertEquals(1, selections.get());
            registry.close();
            assertFalse(registry.select(canonical));
            return null;
        });
    }

    @Test
    void everyRegistryOperationRequiresTheFxThreadAndOwnersAreRegistryScoped() throws Exception {
        SqlFileTabRegistry registry = FxUiTestSupport.call(SqlFileTabRegistry::new);
        SqlFileTabRegistry other = FxUiTestSupport.call(SqlFileTabRegistry::new);
        SqlFileTabRegistry.Owner owner = FxUiTestSupport.call(
                () -> registry.createOwner(() -> { }));
        Path path = directory.resolve("scope.sql").toAbsolutePath().normalize();

        assertThrows(IllegalStateException.class, () -> registry.install(owner, path));
        FxUiTestSupport.call(() -> {
            assertThrows(IllegalArgumentException.class, () -> other.install(owner, path));
            return null;
        });
    }
}
