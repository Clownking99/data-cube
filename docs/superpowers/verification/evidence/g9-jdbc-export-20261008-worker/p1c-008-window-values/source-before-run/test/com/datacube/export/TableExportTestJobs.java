package com.datacube.export;

import com.datacube.service.ConnectionManager;
import java.nio.file.*;
import java.io.IOException;

/** Production TableExporter + real writers, with only pg_dump's external call substituted. */
public final class TableExportTestJobs {
    private TableExportTestJobs() {}
    static java.nio.file.attribute.BasicFileAttributes withoutKey(Path path) throws IOException {
        var value = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new java.nio.file.attribute.BasicFileAttributes() {
            public java.nio.file.attribute.FileTime lastModifiedTime() { return value.lastModifiedTime(); }
            public java.nio.file.attribute.FileTime lastAccessTime() { return value.lastAccessTime(); }
            public java.nio.file.attribute.FileTime creationTime() { return value.creationTime(); }
            public boolean isRegularFile() { return value.isRegularFile(); }
            public boolean isDirectory() { return value.isDirectory(); }
            public boolean isSymbolicLink() { return value.isSymbolicLink(); }
            public boolean isOther() { return value.isOther(); }
            public long size() { return value.size(); }
            public Object fileKey() { return null; }
        };
    }
    public static SafeResultFilePublisher cleanupWarningPublisher() {
        var moved = new java.util.concurrent.atomic.AtomicBoolean();
        return new SafeResultFilePublisher((temporary, target) -> {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved.set(true);
        }, Files::deleteIfExists, ignored -> {}, path -> {
            if (moved.get() && path.getFileName().toString().endsWith(".identity"))
                throw new AccessDeniedException("synthetic witness metadata failure");
            return withoutKey(path);
        });
    }
    public static Path tableWithCleanupWarning(ConnectionManager manager, TableExporter.Request request,
                                              ResultExportOperation operation) throws Exception {
        return TableExporter.export(manager, request, operation, cleanupWarningPublisher(),
                (config, password, table, content, temporary) -> { throw new AssertionError("No pg_dump"); },
                Files::newOutputStream);
    }
    public static Path unsupportedWitness(ConnectionManager manager, TableExporter.Request request,
                                          ResultExportOperation operation) throws Exception {
        var publisher = new SafeResultFilePublisher((temporary, target) -> { throw new AssertionError("No move"); },
                temporary -> { throw new AssertionError("No unsafe cleanup"); }, ignored -> {},
                TableExportTestJobs::withoutKey, (link, existing) -> { throw new FileSystemException("synthetic unsupported hardlink"); });
        return TableExporter.export(manager, request, operation, publisher,
                (config, password, table, content, temporary) -> { throw new AssertionError("No process"); },
                temporary -> { throw new AssertionError("No writer"); });
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
