package com.datacube.sqleditor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlScriptDocumentTest {

    @TempDir Path directory;

    @Test
    void unboundDocumentUsesCallerBaselineAndFallbackTitle() {
        SqlScriptDocument document = new SqlScriptDocument("select 1\n");

        assertNull(document.path());
        assertNull(document.target());
        assertFalse(document.dirty("select 1\n"));
        assertEquals("New SQL", document.title("New SQL", "select 1\n"));
        assertEquals("New SQL*", document.title("New SQL", "select 1\r\n"));
        assertFalse(new SqlScriptDocument().dirty(""));
    }

    @Test
    void comparesTextExactlyAndRevertingToBaselineClearsDirty() {
        SqlScriptDocument document = new SqlScriptDocument("select Name\r\n");

        assertTrue(document.dirty("select Name\n"));
        assertTrue(document.dirty("select name\r\n"));
        assertTrue(document.dirty(" select Name\r\n"));
        assertFalse(document.dirty("select Name\r\n"));
    }

    @Test
    void attachBindsFilenameAndCapturedSnapshot() throws Exception {
        SqlScriptDocument document = new SqlScriptDocument("unbound");
        SqlScriptFileStore.Loaded loaded = loaded("orders.sql", "select * from orders");

        document.attach(loaded);

        assertEquals(loaded.path(), document.path());
        assertEquals(loaded.target(), document.target());
        assertFalse(document.dirty("select * from orders"));
        assertEquals("orders.sql", document.title("New SQL", "select * from orders"));
        assertEquals("orders.sql*", document.title("New SQL", "select * from orders;"));
    }

    @Test
    void savedSnapshotWinsOverLaterEditorChangesAndSaveAsRebinds() throws Exception {
        SqlScriptDocument document = new SqlScriptDocument();
        SqlScriptFileStore.Loaded first = loaded("first.sql", "captured save text");
        SqlScriptFileStore.Loaded second = loaded("second.sql", "save as snapshot");

        document.attach(first);
        document.saved(first); // The editor may now contain text typed after this save began.
        assertFalse(document.dirty("captured save text"));
        assertTrue(document.dirty("captured save text plus later edit"));

        document.saved(second);
        assertEquals(second.path(), document.path());
        assertEquals(second.target(), document.target());
        assertEquals("second.sql", document.title("New SQL", "save as snapshot"));
        assertTrue(document.dirty("captured save text plus later edit"));
        assertFalse(document.dirty("save as snapshot"));
    }

    @Test
    void rejectsNullRequiredInputs() throws Exception {
        SqlScriptDocument document = new SqlScriptDocument();
        SqlScriptFileStore.Loaded loaded = loaded("valid.sql", "text");

        assertThrows(NullPointerException.class, () -> new SqlScriptDocument(null));
        assertThrows(NullPointerException.class, () -> document.dirty(null));
        assertThrows(NullPointerException.class, () -> document.title(null, "text"));
        assertThrows(NullPointerException.class, () -> document.title("New SQL", null));
        assertThrows(NullPointerException.class, () -> document.attach(null));
        assertThrows(NullPointerException.class, () -> document.saved(null));
        document.attach(loaded);
    }

    @Test
    void rejectedLoadedLeavesThePreviousBindingAndBaselineIntact() throws Exception {
        SqlScriptDocument document = new SqlScriptDocument();
        SqlScriptFileStore.Loaded original = loaded("original.sql", "original text");
        SqlScriptFileStore.Loaded invalid = new SqlScriptFileStore.Loaded(
                directory.resolve("replacement.sql"), null,
                new SqlScriptFileStore().capture(directory.resolve("replacement.sql")));
        document.attach(original);

        assertThrows(NullPointerException.class, () -> document.saved(invalid));

        assertEquals(original.path(), document.path());
        assertEquals(original.target(), document.target());
        assertFalse(document.dirty("original text"));
        assertTrue(document.dirty("replacement text"));
        assertEquals("original.sql", document.title("New SQL", "original text"));
    }

    private SqlScriptFileStore.Loaded loaded(String filename, String text) throws Exception {
        SqlScriptFileStore store = new SqlScriptFileStore();
        SqlScriptFileStore.Target target = store.capture(directory.resolve(filename));
        return new SqlScriptFileStore.Loaded(target.path(), text, target);
    }
}
