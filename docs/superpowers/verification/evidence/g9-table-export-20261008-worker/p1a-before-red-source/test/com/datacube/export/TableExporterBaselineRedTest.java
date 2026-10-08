package com.datacube.export;

import com.datacube.service.TableExportMocks;
import com.datacube.spi.model.TableRef;
import java.nio.file.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Kept as regression: the unmodified TableExporter fails both old-byte assertions. */
class TableExporterBaselineRedTest {
    @TempDir Path root;
    @ParameterizedTest @EnumSource(value = ExportFormat.class, names = {"SQL", "XLSX"})
    void middleReadFailureMustKeepExistingDestinationAndNeighbor(ExportFormat format) throws Exception {
        var source = new TableExportMocks();
        source.failure = TableExportMocks.Failure.MIDDLE;
        Path target = Files.writeString(root.resolve("old." + format), "old bytes");
        Path neighbor = Files.writeString(root.resolve("neighbor"), "neighbor bytes");
        assertThrows(Exception.class, () -> TableExporter.export(source.manager, "synthetic",
                new TableRef("synthetic", "items"), ExportContent.DATA, format, target.toFile()));
        assertArrayEquals("old bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8), Files.readAllBytes(target));
        assertEquals("neighbor bytes", Files.readString(neighbor));
        assertTrue(source.pages.get() >= 3, "Failure must happen after the real writer started");
    }
}
