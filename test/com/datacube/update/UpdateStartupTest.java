package com.datacube.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.datacube.update.UpdateTestSupport.*;

class UpdateStartupTest {
    @TempDir Path directory;
    static String argument(Path plan) {
        return "--datacube-update="+Base64.getUrlEncoder().withoutPadding().encodeToString(plan.toString().getBytes(StandardCharsets.UTF_8));
    }
    @Test void onlyExactTargetVersionAndAttemptCanAcknowledgeStartupOnce() throws Exception {
        var f=new UpdateTestSupport();var applier=f.applier();Path app=image(directory.resolve("app"),(byte)42);
        try(var control=control()) {
            var prepared=applier.prepare(release(),"3.0.0",InstallMode.PORTABLE,app,control,null);
            String argument=argument(prepared.plan);Path ack=prepared.workspace.resolve("startup-ack.json");
            assertFalse(UpdateStartup.acknowledge(List.of(argument),"3.0.0",app));
            assertFalse(UpdateStartup.acknowledge(List.of(argument),VERSION,image(directory.resolve("other"),(byte)43)));
            assertFalse(UpdateStartup.acknowledge(List.of(argument,argument),VERSION,app));
            assertFalse(Files.exists(ack));
            assertTrue(UpdateStartup.acknowledge(List.of(argument),VERSION,app));
            var data=(Map<?,?>)MiniJson.parse(Files.readString(ack));
            assertEquals(VERSION,data.get("version"));assertEquals(app.toString(),data.get("appDir"));
            assertEquals(ProcessHandle.current().pid(),((Number)data.get("pid")).longValue());
            assertFalse(UpdateStartup.acknowledge(List.of(argument),VERSION,app));
        }
    }
    @Test void arbitraryCommandLinePathsCannotWriteOutsideOwnedWorkspace() throws Exception {
        Path app=image(directory.resolve("app"),(byte)42);
        Path plan=Files.writeString(directory.resolve("plan.json"),"{}");
        assertFalse(UpdateStartup.acknowledge(List.of(argument(plan)),VERSION,app));
        assertFalse(Files.exists(directory.resolve("startup-ack.json")));
        assertFalse(UpdateStartup.acknowledge(List.of("--datacube-update=not base64!"),VERSION,app));
    }
}
