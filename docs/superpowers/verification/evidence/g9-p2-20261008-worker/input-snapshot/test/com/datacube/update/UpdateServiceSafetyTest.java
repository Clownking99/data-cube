package com.datacube.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.datacube.update.UpdateTestSupport.*;

class UpdateServiceSafetyTest {
    @TempDir Path directory;
    static final class Callbacks implements UpdateService.ApplyCallback {
        final AtomicInteger progress=new AtomicInteger(),ready=new AtomicInteger(),manual=new AtomicInteger(),errors=new AtomicInteger(),cancelled=new AtomicInteger();
        String page;
        public void onProgress(long read,long total){progress.incrementAndGet();}
        public void onReadyToRestart(){ready.incrementAndGet();}
        public void onOpenPage(String url){page=url;manual.incrementAndGet();}
        public void onError(Exception e){errors.incrementAndGet();}
        public void onCancelled(){cancelled.incrementAndGet();}
    }
    UpdateService service(Executor executor,UpdateApplier applier,Path app) {
        return new UpdateService(executor,Runnable::run,new UpdateChecker(),applier,()->InstallMode.PORTABLE,
                ()->Optional.of(app),()->"3.0.0",()->true);
    }
    @Test void missingTrustProvidesExplicitManualRouteWithoutAnyNetworkOrProcess() throws Exception {
        var f=new UpdateTestSupport();Callbacks callbacks=new Callbacks();
        var disabled=new UpdateApplier(new UpdateManifest(Map.of(),CLOCK),f.downloads(),c->f.launches.incrementAndGet());
        try(var svc=service(Runnable::run,disabled,directory.resolve("never-read"))) {
            assertFalse(svc.canAutomaticallyUpdate(release()));svc.downloadAndApply(release(),callbacks);
        }
        assertEquals(1,callbacks.manual.get());assertEquals(UpdateChecker.releasesPage(),callbacks.page);
        assertEquals(0,f.requests.get());assertEquals(0,f.launches.get());assertEquals(0,callbacks.ready.get());
    }
    @Test void closeBeforeQueuedTaskPreventsAllIoAndCallbacks() throws Exception {
        var f=new UpdateTestSupport();List<Runnable> queue=new ArrayList<>();Callbacks callbacks=new Callbacks();
        var svc=service(queue::add,f.applier(),directory.resolve("never-read"));
        svc.downloadAndApply(release(),callbacks);svc.close();queue.getFirst().run();
        assertEquals(0,f.requests.get());assertEquals(0,callbacks.progress.get());assertEquals(0,callbacks.ready.get());
        assertEquals(0,callbacks.errors.get());assertEquals(0,f.launches.get());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void cancellationOrCloseDuringBodyReadClosesStreamRejectsSecondRequestAndNeverLaunches(boolean close) throws Exception {
        var f=new UpdateTestSupport();Path app=image(directory.resolve("app"),(byte)42);
        CountDownLatch entered=new CountDownLatch(1),closed=new CountDownLatch(1);
        AtomicBoolean streamClosed=new AtomicBoolean();
        var transport=new UpdateDownloads(uri->{
            String name=uri.getPath().substring(uri.getPath().lastIndexOf('/')+1);
            if(name.endsWith("portable.zip")) {
                return new UpdateDownloads.Response(200,Map.of(),new InputStream(){
                    public int read() throws IOException {
                        entered.countDown();
                        try { if(!closed.await(5,TimeUnit.SECONDS)) throw new IOException("test timeout"); }
                        catch(InterruptedException interrupted){throw new IOException("cancelled");}
                        throw new IOException("closed");
                    }
                    public void close(){streamClosed.set(true);closed.countDown();}
                });
            }
            return new UpdateDownloads.Response(200,Map.of(),new ByteArrayInputStream(f.responses.get(name)));
        });
        Callbacks callbacks=new Callbacks(),second=new Callbacks();
        try(var executor=Executors.newSingleThreadExecutor();
            var svc=service(executor,new UpdateApplier(f.verifier,transport,c->f.launches.incrementAndGet()),app)) {
            svc.downloadAndApply(release(),callbacks);
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            svc.downloadAndApply(release(),second);assertEquals(1,second.errors.get());
            if(close) svc.close(); else assertTrue(svc.cancelDownload());
            executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));
            assertTrue(streamClosed.get());assertEquals(close?0:1,callbacks.cancelled.get());assertEquals(0,f.launches.get());
            assertEquals(0,callbacks.ready.get());assertArrayEquals(new byte[]{42},Files.readAllBytes(app.resolve("DataCube.exe")));
        } finally {closed.countDown();}
    }
    @Test void authenticatedHappyPathReportsOnlyHandoffAndLauncherFailureNeverReportsReady() throws Exception {
        var f=new UpdateTestSupport();Path app=image(directory.resolve("app"),(byte)42);
        Callbacks okay=new Callbacks();
        try(var svc=service(Runnable::run,f.applier(),app)){
            svc.downloadAndApply(release(),okay);
            var repeated=new Callbacks();int requests=f.requests.get();
            svc.downloadAndApply(release(),repeated);
            assertEquals(1,repeated.errors.get());assertEquals(requests,f.requests.get());
            assertFalse(svc.cancelDownload());
        }
        assertEquals(1,okay.ready.get());assertEquals(0,okay.errors.get());assertEquals(1,f.launches.get());
        Callbacks failure=new Callbacks();
        try(var svc=service(Runnable::run,new UpdateApplier(f.verifier,f.downloads(),c->{throw new IOException("synthetic");}),app)){
            svc.downloadAndApply(release(),failure);
        }
        assertEquals(1,failure.errors.get());assertEquals(0,failure.ready.get());
        assertArrayEquals(new byte[]{42},Files.readAllBytes(app.resolve("DataCube.exe")));
    }
}
