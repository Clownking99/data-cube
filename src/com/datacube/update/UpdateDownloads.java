package com.datacube.update;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Bounded HTTPS transfers, including every redirect, with injectable transport. */
final class UpdateDownloads {
    record Response(int status, Map<String, List<String>> headers, InputStream body) implements AutoCloseable {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream()).findFirst().orElse(null);
        }
        @Override public void close() throws IOException { body.close(); }
    }
    @FunctionalInterface interface Transport { Response open(URI uri) throws Exception; }
    private final Transport transport;
    UpdateDownloads(Transport transport) { this.transport = transport; }
    static UpdateDownloads http() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new UpdateDownloads(uri -> {
            var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "DataCube-Updater").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            return new Response(response.statusCode(), response.headers().map(), response.body());
        });
    }
    static URI allowed(String value) {
        URI uri;
        try { uri = URI.create(value); } catch (RuntimeException invalid) { throw UpdateManifest.rejected(); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || uri.getFragment() != null || uri.getPort() != -1
                || !Set.of("github.com", "api.github.com", "release-assets.githubusercontent.com",
                           "objects.githubusercontent.com").contains(uri.getHost())) throw UpdateManifest.rejected();
        return uri;
    }
    byte[] bytes(String url, int limit, UpdateCancellation control) throws Exception {
        var output = new ByteArrayOutputStream();
        transfer(url, output, limit, -1, control, null);
        return output.toByteArray();
    }
    void asset(String url, Path target, UpdateManifest.Asset asset, UpdateCancellation control,
               UpdateApplier.ProgressListener progress) throws Exception {
        boolean created = false;
        try {
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                transfer(url, output, asset.size(), asset.size(), control, progress);
            }
            control.check();
            verifyFile(target, asset);
        } catch (Exception failure) {
            if (created) Files.deleteIfExists(target);
            throw failure;
        }
    }
    static void verifyFile(Path target, UpdateManifest.Asset asset) throws Exception {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != asset.size()) throw UpdateManifest.rejected();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        if (!MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(asset.sha256()))) throw UpdateManifest.rejected();
    }
    private void transfer(String url, OutputStream output, long max, long expected,
                          UpdateCancellation control, UpdateApplier.ProgressListener progress) throws Exception {
        URI uri = allowed(url);
        for (int redirects = 0; redirects <= 5; redirects++) {
            control.check();
            try (Response response = transport.open(uri)) {
                control.attach(response.body());
                try {
                    if (Set.of(301, 302, 303, 307, 308).contains(response.status())) {
                        String location = response.header("location");
                        if (location == null) throw UpdateManifest.rejected();
                        uri = allowed(uri.resolve(location).toString());
                        continue;
                    }
                    if (response.status() != 200) throw new IOException("更新下载失败（HTTP " + response.status() + "）");
                    String lengthHeader = response.header("content-length");
                    long length = lengthHeader == null ? -1 : Long.parseLong(lengthHeader);
                    if (lengthHeader != null && length < 0 || length > max
                            || expected >= 0 && length >= 0 && length != expected) throw UpdateManifest.rejected();
                    long total = 0; byte[] buffer = new byte[65536]; int count;
                    while ((count = response.body().read(buffer)) != -1) {
                        control.check();
                        if (count > max - total) throw UpdateManifest.rejected();
                        output.write(buffer, 0, count); total += count;
                        if (progress != null) progress.onProgress(total, expected);
                    }
                    control.check();
                    if (length >= 0 && total != length || expected >= 0 && total != expected) throw UpdateManifest.rejected();
                    return;
                } finally { control.detach(response.body()); }
            }
        }
        throw new IOException("更新下载重定向次数超限");
    }
}
