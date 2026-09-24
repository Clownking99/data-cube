package com.datacube.migration;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.HexFormat;

/** Bounded file identity for reviewed migration inputs. No links, no directory traversal. */
public final class MigrationFiles {
    public static final long MAX_FILE_BYTES = 1024L*1024*1024;
    public record Snapshot(Path path, long bytes, String sha256, Object fileKey) {
        @Override public String toString() { return "MigrationInput[bytes="+bytes+"]"; }
    }
    private MigrationFiles() { }
    public static Snapshot inspect(Path directory, String table, MigrationCancellation cancellation) throws IOException {
        cancellation.checkCancelled();
        if (!MigrationPreflight.simpleName(table)) throw new IOException("Unsupported migration file name");
        Path root=directory.toAbsolutePath().normalize();
        Path path=root.resolve("data").resolve(table+".sql");
        checkParents(path);
        BasicFileAttributes before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size()>MAX_FILE_BYTES) throw new IOException("Invalid or oversized migration input");
        MessageDigest digest=digest(); long total=0;
        try(InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer=new byte[65536]; int read;
            while((read=in.read(buffer))!=-1) { cancellation.checkCancelled(); total+=read;
                if(total>MAX_FILE_BYTES) throw new IOException("Migration input exceeds limit"); digest.update(buffer,0,read); }
        }
        BasicFileAttributes after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(total!=before.size() || !java.util.Objects.equals(before.fileKey(),after.fileKey())
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw new IOException("Migration input changed");
        return new Snapshot(path,total,HexFormat.of().formatHex(digest.digest()),before.fileKey());
    }
    public static void verify(Snapshot expected, MigrationCancellation cancellation) throws IOException {
        String file=expected.path().getFileName().toString();
        Snapshot actual=inspect(expected.path().getParent().getParent(),file.substring(0,file.length()-4),cancellation);
        if(!expected.equals(actual)) throw new IOException("Migration input changed after review");
    }
    static void checkParents(Path path) throws IOException {
        for(Path current=path;current!=null;current=current.getParent()) {
            if(!Files.exists(current,LinkOption.NOFOLLOW_LINKS))continue;
            var attributes=Files.readAttributes(current,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(attributes.isSymbolicLink() || attributes.isOther())throw new IOException("Linked migration input is not supported");
        }
    }
    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
