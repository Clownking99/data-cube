package com.datacube.sqleditor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Random;

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
        assertEquals("New SQL", document.title("New SQL", "select 1\r\n"));
        assertFalse(new SqlScriptDocument().dirty(""));
    }

    @Test
    void comparesLogicalTextAndRevertingToBaselineClearsDirty() {
        SqlScriptDocument document = new SqlScriptDocument("select Name\r\n");

        assertFalse(document.dirty("select Name\n"));
        assertTrue(document.dirty("select name\r\n"));
        assertTrue(document.dirty(" select Name\r\n"));
        assertFalse(document.dirty("select Name\r\n"));
    }

    @Test
    void preservesUntouchedPhysicalLineEndingsWhileEditorUsesNormalizedText() throws Exception {
        SqlScriptDocument document = new SqlScriptDocument();
        SqlScriptFileStore.Loaded loaded = loaded("mixed.sql", "one\r\ntwo\rthree\nfour\r\n");

        document.attach(loaded);
        assertEquals("one\ntwo\nthree\nfour\n", document.normalizedText());
        assertFalse(document.dirty(document.normalizedText()));

        document.editorTextChanged(3, "", "!");
        assertEquals("one!\r\ntwo\rthree\nfour\r\n", document.physicalText());
        assertTrue(document.dirty("one!\ntwo\nthree\nfour\n"));
    }

    @Test
    void positionalChangesKeepUntouchedMixedSeparatorsAcrossLinesAndUtf16Text() {
        SqlScriptDocument document = new SqlScriptDocument("甲\r\n乙\r丙\n丁");

        document.editorTextChanged(1, "\n乙\n", "\n😀\n");

        assertEquals("甲\r\n😀\r\n丙\n丁", document.physicalText());
        assertEquals("甲\n😀\n丙\n丁", document.normalizedText());
        assertTrue(document.dirty());
    }

    @Test
    void revertingAPositionalChangeRestoresTheIncrementalCleanState() {
        SqlScriptDocument document = new SqlScriptDocument("select 甲\r\n");

        document.editorTextChanged(7, "", "😀");
        assertTrue(document.dirty());
        document.editorTextChanged(7, "😀", "");

        assertFalse(document.dirty());
        assertEquals("select 甲\r\n", document.physicalText());
    }

    @Test
    void deletionPreservesTwoLogicalNewlinesWhenBareCrAndLfBecomeAdjacent() {
        SqlScriptDocument document = new SqlScriptDocument("a\rb\nc");

        document.editorTextChanged(2, "b", "");

        assertEquals("a\n\nc", document.normalizedText());
        assertEquals("a\r\r\nc", document.physicalText());
        assertEquals("a\n\nc", new SqlScriptDocument(document.physicalText()).normalizedText());
    }

    @Test
    void positionalDeletionAcrossChunkBoundaryPreservesAllCrLfBoundaryCombinations() {
        String prefix = "甲".repeat(4095);
        for (String left : new String[]{"\r", "\n", "\r\n"}) {
            for (String right : new String[]{"\r", "\n", "\r\n"}) {
                String raw = prefix + left + "b" + right + "😀";
                SqlScriptDocument document = new SqlScriptDocument(raw);
                int position = prefix.length() + logicalLength(left);
                String expected = prefix + normalizeForExpectation(left) + normalizeForExpectation(right) + "😀";

                document.editorTextChanged(position, "b", "");

                String persisted = document.physicalText();
                SqlScriptDocument reloaded = new SqlScriptDocument(persisted);
                assertEquals(expected, document.normalizedText(),
                        "document mismatch for " + printable(left) + " + " + printable(right));
                assertEquals(expected, reloaded.normalizedText(),
                        "reload mismatch for " + printable(left) + " + " + printable(right));
                assertEquals(document.incrementalChangeCount(), 1);
            }
        }
    }

    @Test
    void wholeTextReplacementEscapesAnAdjacentBareCrAndLfAsTwoEditorLines() {
        SqlScriptDocument document = new SqlScriptDocument("a\rb\nc");

        document.editorTextChanged(0, "a\nb\nc", "a\n\nc");

        assertEquals("a\n\nc", document.normalizedText());
        assertEquals("a\r\r\nc", document.physicalText());
        assertEquals("a\n\nc", new SqlScriptDocument(document.physicalText()).normalizedText());
    }

    @Test
    void insertionBesideBareCrUsesAnUnambiguousPhysicalEncoding() {
        SqlScriptDocument document = new SqlScriptDocument("x\na\rc");

        document.editorTextChanged(4, "", "\n");

        assertEquals("x\na\n\nc", document.normalizedText());
        assertEquals("x\na\r\r\nc", document.physicalText());
        assertEquals("x\na\n\nc", new SqlScriptDocument(document.physicalText()).normalizedText());
    }

    @Test
    void repeatedSingleCharacterEditsStayChunkBoundedWithoutFullMaterialization() {
        SqlScriptDocument document = new SqlScriptDocument();
        for (int position = 0; position < 10_000; position++) {
            document.editorTextChanged(position, "", "x");
        }
        document.editorTextChanged(2_500, "x".repeat(5_000), "");

        assertEquals(10_001, document.incrementalChangeCount());
        assertEquals(2, document.segmentCount());
        assertTrue(document.maxSegmentLength() <= 4_096);
        assertTrue(document.treeHeight() <= 8);
        assertEquals(0, document.fullTextMaterializationCount());
        assertEquals("x".repeat(5_000), document.normalizedText());
        assertEquals(document.normalizedText(), new SqlScriptDocument(document.physicalText()).normalizedText());
    }

    @Test
    void randomizedNonTailInsertsRemainBalancedAndReloadToTheEditorText() {
        SqlScriptDocument document = new SqlScriptDocument();
        StringBuilder editor = new StringBuilder();
        Random random = new Random(42);
        for (int count = 0; count < 10_000; count++) {
            int position = random.nextInt(editor.length() + 1);
            document.editorTextChanged(position, "", "x");
            editor.insert(position, 'x');
        }

        assertBalanced(document, editor.toString());
    }

    @Test
    void repeatedPrependAfterAFullChunkDoesNotAccumulateTinyLeaves() {
        SqlScriptDocument document = new SqlScriptDocument("x".repeat(4_096));
        for (int count = 0; count < 10_000; count++) {
            document.editorTextChanged(0, "", "x");
        }

        assertBalanced(document, "x".repeat(14_096));
    }

    @Test
    void nearEightMiBNonTailEditsKeepTheLocalStructureBounded() {
        String initial = "x".repeat(4 * 1024 * 1024) + "\r\n" + "y".repeat(4 * 1024 * 1024 - 2);
        SqlScriptDocument document = new SqlScriptDocument(initial);
        StringBuilder editor = new StringBuilder(initial.replace("\r\n", "\n"));
        Random random = new Random(42);
        for (int count = 0; count < 128; count++) {
            int position = random.nextInt(editor.length() + 1);
            document.editorTextChanged(position, "", "z");
            editor.insert(position, 'z');
        }

        assertTrue(document.segmentCount() <= 2 * chunksFor(document.physicalLength()) + 8);
        assertTrue(document.tinySegmentCount(16) <= 8);
        assertTrue(document.maxSegmentLength() <= 4_097);
        assertTrue(document.treeHeight() <= 80);
        assertEquals(0, document.fullTextMaterializationCount());
        assertEquals(editor.toString(), document.normalizedText());
        assertEquals(editor.toString(), new SqlScriptDocument(document.physicalText()).normalizedText());
    }

    @Test
    void nearEightMiBPositionalEditDoesNotMaterializeOrDiffTheWholeDocument() {
        String physical = "x".repeat(4 * 1024 * 1024) + "\r\n"
                + "y".repeat(4 * 1024 * 1024 - 2);
        SqlScriptDocument document = new SqlScriptDocument(physical);

        document.editorTextChanged(4 * 1024 * 1024, "", "!");

        assertEquals(1, document.incrementalChangeCount());
        assertEquals(0, document.fullTextMaterializationCount());
        assertTrue(document.segmentCount() > 1);
        assertTrue(document.maxSegmentLength() <= 4_096);
        assertTrue(document.treeHeight() <= 80);
        assertTrue(document.dirty());
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

    private static int logicalLength(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').length();
    }

    private static String printable(String separator) {
        return separator.replace("\r", "CR").replace("\n", "LF");
    }

    private static String normalizeForExpectation(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void assertBalanced(SqlScriptDocument document, String expectedEditor) {
        assertTrue(document.segmentCount() <= 2 * chunksFor(document.physicalLength()) + 2);
        assertTrue(document.tinySegmentCount(2_048) <= 2);
        assertTrue(document.maxSegmentLength() <= 4_097);
        assertTrue(document.treeHeight() <= 20);
        assertEquals(0, document.fullTextMaterializationCount());
        assertEquals(expectedEditor, document.normalizedText());
        assertEquals(expectedEditor, new SqlScriptDocument(document.physicalText()).normalizedText());
    }

    private static int chunksFor(int length) {
        return Math.max(1, (length + 4_095) / 4_096);
    }
}
