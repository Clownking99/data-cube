package com.datacube.update;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static com.datacube.update.UpdateTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateApplierTest {
    @TempDir Path directory;
    @Test
    void temporaryFilesAreUniqueAndCannotEscapeTheirWorkspace() throws Exception {
        var applier = new UpdateApplier();
        var first = applier.tempFile("package.zip");
        var second = applier.tempFile("package.zip");
        try {
            assertNotEquals(first, second, "independent update attempts must never share a temporary path");
            assertNotEquals(first.getParent(), second.getParent());
            assertThrows(IllegalArgumentException.class, () -> applier.tempFile("../outside.zip"));
        } finally {
            // Only remove fresh empty per-attempt directories; never the shared OS temp directory.
            if (first.getParent().getFileName().toString().startsWith("datacube-update-")) Files.delete(first.getParent());
            if (!second.getParent().equals(first.getParent())
                    && second.getParent().getFileName().toString().startsWith("datacube-update-")) Files.delete(second.getParent());
        }
    }

    @ParameterizedTest @EnumSource(value=InstallMode.class,names={"PORTABLE","INSTALLED"})
    void onlyExactAuthenticatedArtifactCanBeHandedOffOnce(InstallMode mode) throws Exception {
        var fixture=new UpdateTestSupport();var applier=fixture.applier();
        Path target=image(directory.resolve("app 用户%&'"),(byte)42);
        try(var control=control()) {
            var ready=applier.prepare(release(),"3.0.0",mode,target,control,null);
            assertEquals(3,fixture.requests.get());assertEquals(0,fixture.launches.get());
            assertArrayEquals(new byte[]{42},Files.readAllBytes(target.resolve("DataCube.exe")));
            applier.launch(ready,control);
            assertEquals(1,fixture.launches.get());
            assertThrows(IllegalStateException.class,()->applier.launch(ready,control));
            assertFalse(control.cancel(),"handoff is not reported as a cancelled installation");
        }
    }

    @Test void missingTrustAndBadSignatureNeverFetchAnExecutableOrLaunch() throws Exception {
        var f=new UpdateTestSupport();Path target=image(directory.resolve("app"),(byte)42);
        var disabled=new UpdateApplier(new UpdateManifest(java.util.Map.of(),CLOCK),f.downloads(),c->f.launches.incrementAndGet());
        try(var control=control()) {assertThrows(Exception.class,()->disabled.prepare(release(),"3.0.0",InstallMode.PORTABLE,target,control,null));}
        assertEquals(0,f.requests.get());
        f.responses.put("datacube-update.manifest.sig",new byte[64]);
        try(var control=control()) {assertThrows(Exception.class,()->f.applier().prepare(release(),"3.0.0",InstallMode.PORTABLE,target,control,null));}
        assertEquals(2,f.requests.get());assertEquals(0,f.launches.get());
        assertArrayEquals(new byte[]{42},Files.readAllBytes(target.resolve("DataCube.exe")));
    }

    @Test void changedDownloadedArtifactAndCancellationBeforeHandoffNeverLaunchOrTouchCurrentImage() throws Exception {
        var f=new UpdateTestSupport();var applier=f.applier();Path target=image(directory.resolve("app"),(byte)42);
        try(var control=control()) {
            var ready=applier.prepare(release(),"3.0.0",InstallMode.PORTABLE,target,control,null);
            Files.write(ready.asset,new byte[]{0});
            assertThrows(Exception.class,()->applier.launch(ready,control));
        }
        try(var control=control()) {
            var ready=applier.prepare(release(),"3.0.0",InstallMode.PORTABLE,target,control,null);
            control.cancel();assertThrows(java.util.concurrent.CancellationException.class,()->applier.launch(ready,control));
        }
        assertEquals(0,f.launches.get());assertArrayEquals(new byte[]{42},Files.readAllBytes(target.resolve("DataCube.exe")));
    }

    @Test void differentApplierCannotConsumePreparedHandoffAndStartFailureKeepsOldImage() throws Exception {
        var f=new UpdateTestSupport();Path target=image(directory.resolve("app"),(byte)42);
        var first=new UpdateApplier(f.verifier,f.downloads(),c->{throw new java.io.IOException("synthetic launch failure");});
        try(var control=control()) {
            var ready=first.prepare(release(),"3.0.0",InstallMode.PORTABLE,target,control,null);
            assertThrows(IllegalStateException.class,()->f.applier().launch(ready,control));
            assertThrows(java.io.IOException.class,()->first.launch(ready,control));
            assertArrayEquals(new byte[]{42},Files.readAllBytes(target.resolve("DataCube.exe")));
            assertTrue(Files.isDirectory(ready.workspace.resolve("new/DataCube")));
        }
    }
}
