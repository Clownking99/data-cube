package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedPaneConstructionContractTest {

    @Test
    void everyResourceOwningManagedPaneUsesConstructionTransaction() throws Exception {
        for (String pane : List.of(
                "SqlEditorPane", "DataGridPane", "TableDesignerPane", "RedisKeyBrowserPane",
                "RedisConsolePane", "DdlViewPane", "ObjectEditorPane", "SequenceDesignerPane")) {
            String source = Files.readString(Path.of("src/com/datacube/fx/" + pane + ".java"));
            assertTrue(source.contains("try (ConstructionOwner construction"), pane);
            assertTrue(source.contains("construction.commit()"), pane);
        }
    }
}
