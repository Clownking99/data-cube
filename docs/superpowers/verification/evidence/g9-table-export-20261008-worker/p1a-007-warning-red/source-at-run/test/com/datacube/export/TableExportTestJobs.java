package com.datacube.export;

import com.datacube.service.ConnectionManager;
import java.nio.file.*;
import java.io.IOException;

/** Production TableExporter + real writers, with only pg_dump's external call substituted. */
public final class TableExportTestJobs {
    private TableExportTestJobs() {}
    public static SafeResultFilePublisher cleanupWarningPublisher() {
        var moved = new java.util.concurrent.atomic.AtomicBoolean();
        return new SafeResultFilePublisher((temporary, target) -> {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved.set(true);
        }, Files::deleteIfExists, ignored -> {}, path -> {
            if (moved.get() && path.getFileName().toString().endsWith(".identity"))
                throw new AccessDeniedException("synthetic witness metadata failure");
            return Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        });
    }
    public static Path tableWithCleanupWarning(ConnectionManager manager, TableExporter.Request request,
                                              ResultExportOperation operation) throws Exception {
        return TableExporter.export(manager, request, operation, cleanupWarningPublisher(),
                (config, password, table, content, temporary) -> { throw new AssertionError("No pg_dump"); },
                Files::newOutputStream);
    }
    public static Path localDump(ConnectionManager manager, TableExporter.Request request,
                                 ResultExportOperation operation, Runnable duringDump) throws Exception {
        return TableExporter.export(manager, request, operation, new SafeResultFilePublisher(),
                (config, password, table, content, temporary) -> {
                    Files.writeString(temporary.toPath(), "synthetic pg_dump output");
                    duringDump.run();
                }, Files::newOutputStream);
    }
    public static Path failingDump(ConnectionManager manager, TableExporter.Request request,
                                   ResultExportOperation operation) throws Exception {
        return TableExporter.export(manager, request, operation, new SafeResultFilePublisher(),
                (config, password, table, content, temporary) -> {
                    Files.writeString(temporary.toPath(), "partial synthetic pg_dump output");
                    throw new IOException("synthetic sensitive pg_dump");
                }, Files::newOutputStream);
    }
    public static Path blockedPublication(ConnectionManager manager, TableExporter.Request request,
                                           ResultExportOperation operation, Runnable beforeMove) throws Exception {
        var publisher = new SafeResultFilePublisher((temporary, target) -> {
            beforeMove.run();
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }, Files::deleteIfExists, ignored -> { throw new AssertionError("Unexpected cleanup failure"); });
        return TableExporter.export(manager, request, operation, publisher,
                (config, password, table, content, temporary) -> { throw new AssertionError("No pg_dump"); },
                Files::newOutputStream);
    }
}
