package com.datacube.export;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import static org.junit.jupiter.api.Assertions.*;

class SafeResultFilePublisherIdentityTest {
    @TempDir Path root;
    @Test void hardLinkIdentityBlocksConcurrentAliasAndAtomicReplacementPreservesNeighborBytes() throws Exception {
        Path target = Files.writeString(root.resolve("target"), "old bytes");
        Path alias = root.resolve("hardlink");
        try { Files.createLink(alias, target); }
        catch (UnsupportedOperationException | FileSystemException unavailable) {
            Assumptions.assumeTrue(false, "Hardlink creation unavailable");
        }
        var firstTarget = SafeResultFilePublisher.capture(target);
        var aliasTarget = SafeResultFilePublisher.capture(alias);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var worker = new FutureTask<Path>(() -> new SafeResultFilePublisher().publish(firstTarget,
                new ResultExportOperation(), (temporary, operation) -> {
                    Files.writeString(temporary, "new bytes"); entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }));
        Thread.ofVirtual().start(worker);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var failure = assertThrows(SafeResultFilePublisher.Failure.class, () -> new SafeResultFilePublisher().publish(
                    aliasTarget, new ResultExportOperation(), (temporary, operation) -> fail("Alias must not write")));
            assertEquals(SafeResultFilePublisher.Stage.TARGET_BUSY, failure.stage());
            release.countDown(); worker.get(5, TimeUnit.SECONDS);
            assertEquals("new bytes", Files.readString(target));
            assertEquals("old bytes", Files.readString(alias));
            assertFalse(Files.isSameFile(target, alias));
            new SafeResultFilePublisher().publish(SafeResultFilePublisher.capture(alias), new ResultExportOperation(),
                    (temporary, operation) -> Files.writeString(temporary, "later alias export"));
            assertEquals("new bytes", Files.readString(target));
        } finally { release.countDown(); worker.get(5, TimeUnit.SECONDS); }
    }

    @Test void replacedTemporaryPathIsNeverDeletedOrPublished() throws Exception {
        Path target = Files.writeString(root.resolve("target"), "old");
        var changedTemp = new AtomicReference<Path>();
        var diagnostic = new AtomicReference<Path>();
        var publisher = new SafeResultFilePublisher((temporary, destination) -> fail("Must not publish replacement"),
                path -> { fail("Must not delete someone else's file"); }, diagnostic::set);
        var failure = assertThrows(SafeResultFilePublisher.Failure.class, () -> publisher.publish(
                SafeResultFilePublisher.capture(target), new ResultExportOperation(), (temporary, operation) -> {
                    changedTemp.set(temporary);
                    Files.writeString(temporary, "owned data");
                    Files.move(temporary, root.resolve("owned-relocated"));
                    Files.writeString(temporary, "other owner's replacement");
                }));
        assertEquals(SafeResultFilePublisher.Stage.CLEANUP, failure.stage());
        assertEquals(changedTemp.get(), diagnostic.get());
        assertEquals("other owner's replacement", Files.readString(changedTemp.get()));
        assertEquals("owned data", Files.readString(root.resolve("owned-relocated")));
        assertEquals("old", Files.readString(target));
    }

    @Test void changedParentDirectoryRefusesMoveAndDoesNotCleanReplacementDirectory() throws Exception {
        Path parent = Files.createDirectory(root.resolve("parent"));
        Path target = Files.writeString(parent.resolve("target"), "old");
        var failure = assertThrows(SafeResultFilePublisher.Failure.class, () -> new SafeResultFilePublisher().publish(
                SafeResultFilePublisher.capture(target), new ResultExportOperation(), (temporary, operation) -> {
                    Files.writeString(temporary, "owned data");
                    Files.move(parent, root.resolve("original-parent"));
                    Files.createDirectory(parent);
                    Files.writeString(target, "replacement target");
                    Files.writeString(parent.resolve(temporary.getFileName()), "replacement temporary");
                }));
        assertEquals(SafeResultFilePublisher.Stage.CLEANUP, failure.stage());
        assertEquals("replacement target", Files.readString(target));
        assertEquals("old", Files.readString(root.resolve("original-parent/target")));
        assertEquals("replacement temporary", Files.readString(failure.temporaryPath()));
    }

    @Test void symbolicLinkAncestorIsRejectedBeforeCreatingTemporary() throws Exception {
        Path parent = Files.createDirectory(root.resolve("actual"));
        Path link = root.resolve("alias");
        try { Files.createSymbolicLink(link, parent); }
        catch (UnsupportedOperationException | FileSystemException unavailable) {
            Assumptions.assumeTrue(false, "Directory symbolic link unavailable");
        }
        assertThrows(SafeResultFilePublisher.Failure.class, () -> SafeResultFilePublisher.capture(link.resolve("new")));
        try (var entries = Files.list(parent)) { assertEquals(0, entries.count()); }
    }

    @Test void normalShortTemporaryRootAndCaseDotAliasesRemainCompatible() throws Exception {
        Path shortRoot = Path.of(System.getProperty("java.io.tmpdir"));
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        assertTrue(shortRoot.toString().contains("~"), "Run must actually use an 8.3 alias");
        Path target = Files.writeString(root.resolve("MixedCase.csv"), "old");
        var first = SafeResultFilePublisher.capture(target);
        var alias = SafeResultFilePublisher.capture(root.resolve(".").resolve("mixedcase.CSV"));
        assertEquals(first.path(), alias.path());
        new SafeResultFilePublisher().publish(alias, new ResultExportOperation(),
                (temporary, operation) -> Files.writeString(temporary, "new"));
        assertEquals("new", Files.readString(target));
    }

    @Test void rawLinkThenParentSegmentCannotBeErasedByNormalization() throws Exception {
        Path actual = Files.createDirectories(root.resolve("actual/nested"));
        Path link = root.resolve("link");
        try { Files.createSymbolicLink(link, actual); }
        catch (UnsupportedOperationException | FileSystemException unavailable) {
            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))
                Assumptions.assumeTrue(false, "Directory symlink unavailable");
            Process helper = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                    link.toString(), actual.toString()).redirectErrorStream(true).start();
            try {
                assertTrue(helper.waitFor(5, TimeUnit.SECONDS));
                Assumptions.assumeTrue(helper.exitValue() == 0, "Owned junction unavailable");
            } finally { if (helper.isAlive()) helper.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
        }
        Path lexical = Files.writeString(root.resolve("selected"), "lexical neighbor");
        Path semantic = Files.writeString(root.resolve("actual/selected"), "semantic neighbor");
        Path raw = link.resolve("..").resolve("selected");
        assertEquals(lexical, raw.normalize());
        assertTrue(Files.exists(raw));
        var failure = assertThrows(SafeResultFilePublisher.Failure.class, () -> SafeResultFilePublisher.capture(raw));
        assertEquals(SafeResultFilePublisher.Stage.PREPARE, failure.stage());
        assertEquals("lexical neighbor", Files.readString(lexical));
        assertEquals("semantic neighbor", Files.readString(semantic));
    }

    @Test void confirmedAbsentTemporaryNeedsNoDeleteButMetadataFailureMustReportCleanup() throws Exception {
        Path target = Files.writeString(root.resolve("target"), "old");
        var deletes = new AtomicInteger();
        var disappeared = new AtomicReference<Path>();
        var absentPublisher = new SafeResultFilePublisher((temporary, destination) -> fail("No publication"),
                temporary -> deletes.incrementAndGet(), path -> fail("Confirmed absence is not cleanup failure"));
        var absentFailure = assertThrows(SafeResultFilePublisher.Failure.class, () -> absentPublisher.publish(
                SafeResultFilePublisher.capture(target), new ResultExportOperation(), (temporary, operation) -> {
                    disappeared.set(temporary); Files.delete(temporary); throw new IOException("fixed writer failure");
                }));
        assertEquals(SafeResultFilePublisher.Stage.WRITE, absentFailure.stage());
        assertEquals(0, deletes.get()); assertFalse(Files.exists(disappeared.get()));
        var failReads = new AtomicBoolean();
        var diagnosed = new AtomicReference<Path>();
        var owned = new AtomicReference<Path>();
        var failingPublisher = new SafeResultFilePublisher((temporary, destination) -> fail("No publication"),
                temporary -> fail("Unreadable identity must not be deleted"), diagnosed::set, path -> {
                    if (failReads.get() && path.getFileName().toString().endsWith(".tmp"))
                        throw new AccessDeniedException("synthetic metadata failure");
                    return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                });
        var readFailure = assertThrows(SafeResultFilePublisher.Failure.class, () -> failingPublisher.publish(
                SafeResultFilePublisher.capture(target), new ResultExportOperation(), (temporary, operation) -> {
                    owned.set(temporary); Files.writeString(temporary, "owned partial"); failReads.set(true);
                    throw new IOException("fixed writer failure");
                }));
        assertEquals(SafeResultFilePublisher.Stage.CLEANUP, readFailure.stage());
        assertEquals(owned.get(), readFailure.temporaryPath()); assertEquals(owned.get(), diagnosed.get());
        assertEquals("owned partial", Files.readString(owned.get())); assertEquals("old", Files.readString(target));
    }

    @Test void witnessCleanupAfterSuccessfulMoveNeverReportsUnpublishedFailure() throws Exception {
        Path target = Files.writeString(root.resolve("target"), "old");
        Assumptions.assumeTrue(Files.readAttributes(target, BasicFileAttributes.class).fileKey() == null,
                "Identity witness is only used on filesystems without fileKey");
        var moved = new AtomicBoolean(); var diagnostic = new AtomicReference<Path>();
        var publisher = new SafeResultFilePublisher((temporary, destination) -> {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved.set(true);
        }, Files::deleteIfExists, diagnostic::set, path -> {
            if (moved.get() && path.getFileName().toString().endsWith(".identity"))
                throw new AccessDeniedException("synthetic witness metadata failure");
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        });
        var operation = new ResultExportOperation();
        Path published = publisher.publish(SafeResultFilePublisher.capture(target), operation,
                (temporary, token) -> Files.writeString(temporary, "new"));
        assertTrue(operation.published()); assertTrue(Files.isSameFile(target, published));
        assertEquals("new", Files.readString(target)); assertNotNull(diagnostic.get());
        assertTrue(Files.isSameFile(target, diagnostic.get()));
    }
}
