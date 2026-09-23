package com.datacube.update;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

final class UpdateTestSupport {
    static final String VERSION = "3.0.1";
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);
    static final byte[] SETUP = "synthetic-installer-never-executed".getBytes(StandardCharsets.UTF_8);
    static final Map<String, byte[]> IMAGE = Map.of(
            "DataCube/DataCube.exe", new byte[]{1,2,3}, "DataCube/app/DataCube.cfg", new byte[]{4,5,6},
            "DataCube/runtime/lib/modules", new byte[]{7,8,9}, "DataCube/runtime/bin/java.exe", new byte[]{10,11,12});
    final KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    final byte[] archive = zip(IMAGE);
    final AtomicInteger requests = new AtomicInteger(), launches = new AtomicInteger();
    final UpdateManifest verifier = new UpdateManifest(Map.of("test-key", keys.getPublic()), CLOCK);
    final Map<String, byte[]> responses = new HashMap<>();
    UpdateTestSupport() throws Exception {
        byte[] manifest = manifest(VERSION, SETUP, archive);
        responses.put("datacube-update.manifest", manifest);
        responses.put("datacube-update.manifest.sig", sign(manifest));
        responses.put(UpdateManifest.assetName(VERSION, InstallMode.INSTALLED), SETUP);
        responses.put(UpdateManifest.assetName(VERSION, InstallMode.PORTABLE), archive);
    }
    static ReleaseInfo release() {
        String base = UpdateManifest.baseUrl(VERSION);
        return new ReleaseInfo("v"+VERSION, VERSION, "synthetic", UpdateChecker.releasesPage(),
                base+UpdateManifest.assetName(VERSION,InstallMode.INSTALLED),
                base+UpdateManifest.assetName(VERSION,InstallMode.PORTABLE), true);
    }
    byte[] sign(byte[] bytes) throws Exception {
        Signature signer = Signature.getInstance("Ed25519"); signer.initSign(keys.getPrivate()); signer.update(bytes);
        return signer.sign();
    }
    static byte[] manifest(String version, byte[] setup, byte[] portable) throws Exception {
        return ("datacube-update-v1\nkey=test-key\nversion="+version+"\nplatform=windows-x64\nissued="
                +(CLOCK.instant().getEpochSecond()-60)+"\nexpires="+(CLOCK.instant().getEpochSecond()+86400)+"\n"
                +"setup\t"+UpdateManifest.assetName(version,InstallMode.INSTALLED)+"\t"+setup.length+"\t"+hash(setup)+"\n"
                +"portable\t"+UpdateManifest.assetName(version,InstallMode.PORTABLE)+"\t"+portable.length+"\t"+hash(portable)+"\n")
                .getBytes(StandardCharsets.UTF_8);
    }
    UpdateDownloads downloads() {
        return new UpdateDownloads(uri -> {
            requests.incrementAndGet();
            byte[] bytes=responses.get(uri.getPath().substring(uri.getPath().lastIndexOf('/')+1));
            if(bytes==null) throw new IOException("synthetic missing response");
            return new UpdateDownloads.Response(200, Map.of(), new ByteArrayInputStream(bytes));
        });
    }
    UpdateApplier applier() { return new UpdateApplier(verifier, downloads(), command -> launches.incrementAndGet()); }
    static Path image(Path root, byte marker) throws Exception {
        Files.createDirectories(root);
        for(String path:IMAGE.keySet()) {
            Path file=root.resolve(path.substring("DataCube/".length()));
            Files.createDirectories(file.getParent()); Files.write(file,new byte[]{marker});
        }
        return root;
    }
    static byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream result=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(result)) {
            for(var entry:files.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return result.toByteArray();
    }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static UpdateCancellation control() { return new UpdateCancellation(Duration.ofMinutes(1)); }
}
