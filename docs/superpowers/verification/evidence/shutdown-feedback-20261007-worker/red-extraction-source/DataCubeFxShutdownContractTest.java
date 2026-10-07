package com.datacube.fx;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class DataCubeFxShutdownContractTest {
    @ParameterizedTest @CsvSource({"dark,900,600","light,900,600","dark,1200,800","light,1200,800"})
    void partialFailureLeavesPersistentReadableFeedbackOutsideDisabledBody(String theme,int width,int height) throws Exception {
        var result=new CompletableFuture<ShutdownOutcome>();var requests=new AtomicInteger();
        var fixture=FxUiTestSupport.call(() -> {
            var stage=new Stage();var body=new BorderPane(new Label("synthetic body"));
            var controller=new WindowShutdownController(stage,body,() -> false,() -> {requests.incrementAndGet();return result;});
            var scene=new Scene(controller.getRoot(),width,height);
            scene.getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                    ThemeManager.class.getResource("theme-"+theme+".css").toExternalForm());
            stage.setScene(scene);stage.show();stage.setWidth(width);stage.setHeight(height);return new Fixture(stage,body,controller);
        });
        try {
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {closeRequest(fixture.stage);assertTrue(fixture.body.isDisabled());return null;});
            result.complete(ShutdownOutcome.FAILED_PARTIAL);awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var stage=fixture.stage;var root=fixture.controller.getRoot();root.applyCss();root.layout();
                assertEquals(width,stage.getWidth(),1);assertEquals(height,stage.getHeight(),1);assertTrue(stage.isShowing());
                assertTrue(fixture.body.isDisabled());assertEquals(1,requests.get());
                System.out.println("SHUTDOWN geometry theme="+theme+" stage="+stage.getWidth()+"x"+stage.getHeight()
                        +" scene="+stage.getScene().getWidth()+"x"+stage.getScene().getHeight()+" bodyDisabled="+fixture.body.isDisabled());
                var notice=root.lookup("#shutdown-failure-notice");
                assertNotNull(notice,"partial exit leaves a disabled main window with no visible explanation");
                assertFalse(notice.isDisabled());assertInScene(stage,notice);return null;
            });
        } finally {FxUiTestSupport.call(() -> {fixture.stage.setOnCloseRequest(null);fixture.stage.close();return null;});}
    }
    private static void closeRequest(Stage stage) {stage.fireEvent(new WindowEvent(stage,WindowEvent.WINDOW_CLOSE_REQUEST));}
    private static void assertInScene(Stage stage,javafx.scene.Node node) {
        var bounds=node.localToScene(node.getBoundsInLocal());var screen=node.localToScreen(node.getBoundsInLocal());
        System.out.println("SHUTDOWN node="+node.getId()+" bounds="+bounds+" screen="+screen);
        assertNotNull(screen);assertTrue(bounds.getMinX()>=-1 && bounds.getMaxX()<=stage.getScene().getWidth()+1
                && bounds.getMinY()>=-1 && bounds.getMaxY()<=stage.getScene().getHeight()+1,"outside shown scene: "+bounds);
    }
    private static void awaitLayoutPulses() throws Exception {
        var ready=new CountDownLatch(1);
        FxUiTestSupport.call(() -> {new javafx.animation.AnimationTimer() {
            int frames;
            @Override public void handle(long now){if(++frames>=3){stop();ready.countDown();}}
        }.start();return null;});assertTrue(ready.await(5,TimeUnit.SECONDS));
    }
    private record Fixture(Stage stage,BorderPane body,WindowShutdownController controller) {}
}