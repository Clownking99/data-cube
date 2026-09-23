package com.datacube.update;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Extracts to a fresh owned directory; never follows archive links or replaces files. */
final class PortableArchive {
    static final int MAX_ENTRIES = 10000;
    static final long MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024;
    private PortableArchive() { }
    static Path extract(Path archive, Path destination, UpdateCancellation control) throws Exception {
        return extract(archive, destination, control, MAX_ENTRIES, MAX_EXPANDED_BYTES);
    }
    static Path extract(Path archive, Path destination, UpdateCancellation control, int maxEntries, long maxBytes) throws Exception {
        Path root = UpdatePaths.noLinks(destination);
        Files.createDirectory(root);
        Set<String> seen = new HashSet<>();
        long expanded = 0; int entries = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS))) {
            control.attach(input);
            try {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    control.check();
                    if (++entries > maxEntries) throw new IOException("更新压缩包条目数超限");
                    String name = entry.getName();
                    if (entry.isDirectory() && name.endsWith("/")) name = name.substring(0, name.length() - 1);
                    validateName(name);
                    if (!seen.add(name.toLowerCase(Locale.ROOT))) throw new IOException("更新压缩包包含重复或大小写冲突路径");
                    Path target = root.resolve(name).normalize();
                    if (!target.startsWith(root)) throw new IOException("更新压缩包路径越界");
                    if (entry.isDirectory()) {
                        if (input.read() != -1) throw new IOException("更新压缩包目录条目包含数据");
                        Files.createDirectories(target); continue;
                    }
                    Files.createDirectories(target.getParent());
                    try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer = new byte[65536]; int count;
                        while ((count = input.read(buffer)) != -1) {
                            control.check();
                            if (count > maxBytes - expanded) throw new IOException("更新压缩包解压大小超限");
                            output.write(buffer, 0, count); expanded += count;
                        }
                    }
                    input.closeEntry();
                }
            } finally { control.detach(input); }
        }
        return UpdatePaths.image(root.resolve("DataCube"));
    }
    private static void validateName(String name) throws IOException {
        String[] components = name.split("/", -1);
        if (components.length == 0 || !components[0].equals("DataCube")) throw new IOException("更新压缩包根目录无效");
        for (String part : components) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.matches(".*[\\\\:<>\u0000-\u001f\"|?*].*")
                    || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
                throw new IOException("更新压缩包包含不安全路径");
        }
    }
}
