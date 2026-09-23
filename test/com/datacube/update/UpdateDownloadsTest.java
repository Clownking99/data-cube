package com.datacube.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.datacube.update.UpdateTestSupport.*;

class UpdateDownloadsTest {
    @TempDir Path directory;
    static final String URL="https://github.com/Clownking99/data-cube/releases/download/v3.0.1/package";
    @Test void exactPayloadWithoutContentLengthIsVerifiedAndReportsSignedTotal() throws Exception {
        byte[] data={1,2,3}; var progress=new ArrayList<Long>();
        var downloads=new UpdateDownloads(uri->new UpdateDownloads.Response(200,Map.of(),new ByteArrayInputStream(data)));
        try(var control=control()) {
            Path target=directory.resolve("asset");
            downloads.asset(URL,target,new UpdateManifest.Asset("asset",3,hash(data)),control,(read,total)->{
                assertEquals(3,total);progress.add(read);
            });
            assertArrayEquals(data,Files.readAllBytes(target)); assertEquals(List.of(3L),progress);
        }
    }
    @ParameterizedTest @ValueSource(strings={"truncated","extra","hash","length","negative-length","http","stream-failure"})
    void incompleteOrUntrustedBodiesLeaveNoExecutableFileAndCloseResponse(String kind) throws Exception {
        byte[] data={1,2,3}; AtomicBoolean closed=new AtomicBoolean();
        byte[] body=kind.equals("truncated")?new byte[]{1,2}:kind.equals("extra")?new byte[]{1,2,3,4}:data;
        InputStream input=new ByteArrayInputStream(body) {
            @Override public synchronized int read(byte[] b,int off,int len) {
                if(kind.equals("stream-failure")) throw new java.io.UncheckedIOException(new IOException("synthetic"));
                return super.read(b,off,len);
            }
            @Override public void close() {closed.set(true);}
        };
        Map<String,List<String>> headers=kind.equals("length")?Map.of("Content-Length",List.of("99"))
                :kind.equals("negative-length")?Map.of("Content-Length",List.of("-1")):Map.of();
        var downloads=new UpdateDownloads(uri->new UpdateDownloads.Response(kind.equals("http")?503:200,headers,input));
        try(var control=control()) {
            Path target=directory.resolve("asset");
            assertThrows(Exception.class,()->downloads.asset(URL,target,new UpdateManifest.Asset("asset",3,
                    kind.equals("hash")?"0".repeat(64):hash(data)),control,null));
            assertFalse(Files.exists(target));assertTrue(closed.get());
        }
    }
    @Test void doesNotOverwriteOrDeleteAnExistingFile() throws Exception {
        Path target=directory.resolve("existing");Files.writeString(target,"preserve");
        AtomicInteger opened=new AtomicInteger();
        var downloads=new UpdateDownloads(uri->{opened.incrementAndGet();throw new AssertionError();});
        try(var control=control()) {
            assertThrows(FileAlreadyExistsException.class,()->downloads.asset(URL,target,new UpdateManifest.Asset("asset",1,"0".repeat(64)),control,null));
            assertEquals("preserve",Files.readString(target));assertEquals(0,opened.get());
        }
    }
    @ParameterizedTest @ValueSource(strings={"http://github.com/a","https://evil.invalid/a","https://github.com@evil.invalid/a","file:///C:/a","https://github.com:443/a"})
    void unsafeUrlsAndRedirectsAreRejectedBeforeFollowingThem(String url) throws Exception {
        AtomicInteger opens=new AtomicInteger();AtomicBoolean closed=new AtomicBoolean();
        var downloads=new UpdateDownloads(uri->{opens.incrementAndGet();return new UpdateDownloads.Response(302,
                Map.of("location",List.of(url)),new ByteArrayInputStream(new byte[0]){@Override public void close(){closed.set(true);}});});
        try(var control=control()) {
            assertThrows(Exception.class,()->downloads.bytes(url,10,control));assertEquals(0,opens.get());
            assertThrows(Exception.class,()->downloads.bytes(URL,10,control));assertEquals(1,opens.get());assertTrue(closed.get());
        }
    }
    @Test void redirectsAndMetadataHaveHardBounds() throws Exception {
        AtomicInteger opens=new AtomicInteger();
        var redirect=new UpdateDownloads(uri->{opens.incrementAndGet();return new UpdateDownloads.Response(302,Map.of("location",List.of(URL)),new ByteArrayInputStream(new byte[0]));});
        try(var control=control()) {assertThrows(Exception.class,()->redirect.bytes(URL,10,control));assertEquals(6,opens.get());}
        var huge=new UpdateDownloads(uri->new UpdateDownloads.Response(200,Map.of(),new ByteArrayInputStream(new byte[11])));
        try(var control=control()) {assertThrows(Exception.class,()->huge.bytes(URL,10,control));}
    }
    @Test void cancellationAndDeadlineRejectBeforeTransportOrHandoff() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        var download=new UpdateDownloads(uri->{calls.incrementAndGet();throw new AssertionError();});
        try(var control=control()) {
            control.cancel();
            assertThrows(java.util.concurrent.CancellationException.class,()->download.bytes(URL,10,control));
            assertThrows(java.util.concurrent.CancellationException.class,()->control.handoff(calls::incrementAndGet));
        }
        try(var control=control()) {control.expire();assertThrows(java.util.concurrent.CancellationException.class,control::check);}
        assertEquals(0,calls.get());
    }
    @Test void elapsedDeadlineClosesAnAlreadyBlockedBodyAndRemovesPartialAsset() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var released=new java.util.concurrent.CountDownLatch(1);
        var closed=new AtomicBoolean();
        var download=new UpdateDownloads(uri->new UpdateDownloads.Response(200,Map.of(),new InputStream(){
            public int read() throws IOException {
                entered.countDown();
                try { released.await(5,java.util.concurrent.TimeUnit.SECONDS); }
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                throw new IOException("synthetic closed body");
            }
            public void close(){closed.set(true);released.countDown();}
        }));
        Path asset=directory.resolve("timeout-asset");
        try(var control=new UpdateCancellation(java.time.Duration.ofSeconds(2));
            var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var task=worker.submit(()->{
                control.bindWorker();
                assertThrows(Exception.class,()->download.asset(URL,asset,new UpdateManifest.Asset("asset",1,"0".repeat(64)),control,null));
            });
            assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));
            task.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(control.cancelled());assertTrue(closed.get());assertFalse(Files.exists(asset));
            assertThrows(java.util.concurrent.CancellationException.class,()->control.handoff(()->fail("must not launch")));
        } finally {released.countDown();}
    }
}
