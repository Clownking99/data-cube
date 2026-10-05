package com.datacube.update;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;

final class UpdatePaths {
    private UpdatePaths() { }
    static Path noLinks(Path value) throws IOException {
        Path path = value.toAbsolutePath().normalize();
        for (Path current = path; current != null; current = current.getParent()) {
            final BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                continue; // New staging descendants may not exist; still inspect every ancestor.
            }
            // On Windows isOther includes non-symlink reparse points (e.g. junctions) and devices.
            // Real-path spelling may differ solely because of an ordinary 8.3 filename alias.
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("更新路径包含链接或重解析目录");
            }
        }
        return path;
    }
    static Path canonicalExisting(Path value) throws IOException {
        // Validate the original spelling before resolving it; do not hide a junction in an alias.
        Path canonical = noLinks(value).toRealPath(LinkOption.NOFOLLOW_LINKS);
        return noLinks(canonical);
    }
    static Path image(Path directory) throws IOException {
        Path path = noLinks(directory);
        if (path.getParent() == null || path.getParent().getParent() == null) throw new IOException("无法确认更新目标目录");
        for (String required : new String[]{"DataCube.exe", "app/DataCube.cfg", "runtime/lib/modules", "runtime/bin/java.exe"}) {
            Path file = noLinks(path.resolve(required));
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) == 0) throw new IOException("更新镜像结构不完整");
        }
        return path;
    }
}
