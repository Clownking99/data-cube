package com.datacube.update;

import java.nio.file.*;
import java.io.IOException;

final class UpdatePaths {
    private UpdatePaths() { }
    static Path noLinks(Path value) throws IOException {
        Path path = value.toAbsolutePath().normalize();
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current) || !current.toRealPath().equals(current))) {
                throw new IOException("更新路径包含链接或重解析目录");
            }
        }
        return path;
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
