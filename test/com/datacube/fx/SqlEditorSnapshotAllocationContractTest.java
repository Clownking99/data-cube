package com.datacube.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SqlEditorSnapshotAllocationContractTest {
    @Test
    void resultRenderPassesOneSnapshotThroughTableAndToolbarRendering() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));
        String entryMarker = "private void renderResultFilterSnapshot()";
        String snapshotMarker =
                "private void renderResultFilterSnapshot(ResultFilterState.Snapshot snapshot)";
        String toolbarMarker = "private void renderResultFilterToolbar()";
        int entry = source.indexOf(entryMarker);
        int snapshotOverload = source.indexOf(snapshotMarker, entry);
        int toolbar = source.indexOf(toolbarMarker, snapshotOverload);

        assertTrue(entry >= 0);
        assertTrue(snapshotOverload > entry,
                "the render entry point must capture one snapshot and pass it to a snapshot overload");
        assertTrue(toolbar > snapshotOverload);

        String entryBody = source.substring(entry, snapshotOverload);
        String renderBody = source.substring(snapshotOverload, toolbar);
        assertEquals(1, occurrences(entryBody, "resultFilterState.snapshot()"));
        assertEquals(0, occurrences(renderBody, "resultFilterState.snapshot()"));
        assertTrue(renderBody.contains("renderResultFilterToolbar(snapshot)"),
                "table and toolbar rendering must consume the same snapshot");
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
