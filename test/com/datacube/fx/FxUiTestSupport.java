package com.datacube.fx;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class FxUiTestSupport {
    private FxUiTestSupport() {}

    static <T> T call(Callable<T> action) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "JavaFX controls require an available display");
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyStarted) {
            ready.countDown();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "FX startup timed out");
        if (Platform.isFxApplicationThread()) return action.call();
        FutureTask<T> task = new FutureTask<>(() -> {
            Platform.setImplicitExit(false);
            return action.call();
        });
        Platform.runLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }
}
