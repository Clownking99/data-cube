package com.datacube.fx;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DataCubeFxShutdownContractTest {
    @ParameterizedTest @CsvSource({"dark,900,600","light,900,600","dark,1200,800","light,1200,800"})
    void pendingCloseLeavesReadableWaitingFeedbackOutsideDisabledBody(String theme,int width,int height) throws Exception {
        var result=new CompletableFuture<ShutdownOutcome>();var requests=new AtomicInteger();
        try(var f=new Fixture(theme,width,height,() -> false,() -> true,() -> {requests.incrementAndGet();return result;})) {
            awaitLayoutPulses();
            var original=FxUiTestSupport.call(() -> f.body.localToScene(f.body.getBoundsInLocal()));
            FxUiTestSupport.call(() -> {closeRequest(f.stage);return null;});awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                assertEquals(width,f.stage.getWidth(),1);assertEquals(height,f.stage.getHeight(),1);
                assertEquals(1,requests.get());assertTrue(f.body.isDisabled());assertFalse(result.isDone());
                System.out.println("PENDING shownStage="+f.stage.getWidth()+"x"+f.stage.getHeight()+" scene="+f.stage.getScene().getWidth()+"x"+f.stage.getScene().getHeight()+" body="+f.body.localToScene(f.body.getBoundsInLocal())+" requests="+requests.get());
                assertPendingFeedback(f.stage,f.body);
                assertEquals(original,f.body.localToScene(f.body.getBoundsInLocal()),"waiting feedback must preserve body layout");
                var notice=f.controller.getRoot().lookup("#shutdown-pending-notice");
                Color background=(Color)((javafx.scene.layout.Region)notice).getBackground().getFills().getFirst().getFill();
                Color foreground=(Color)((Label)notice.lookup("#shutdown-pending-state")).getTextFill();
                assertEquals(Color.web(theme.equals("dark") ? "#E8E8ED" : "#2E3440"),foreground);
                assertEquals(Color.web(theme.equals("dark") ? "#151520" : "#ECEFF4"),background);assertTrue(contrast(foreground,background)>=4.5);
                closeRequest(f.stage);closeRequest(f.stage);assertEquals(1,requests.get());assertSame(notice,f.controller.getRoot().lookup("#shutdown-pending-notice"));return null;
            });
        }
    }
    static void assertPendingFeedback(Stage stage,Parent body) {
        var root=(Parent)stage.getScene().getRoot();root.applyCss();root.layout();
        assertTrue(stage.isShowing());assertTrue(body.isDisabled());
        var notice=root.lookup("#shutdown-pending-notice");assertNotNull(notice,"pending exit disables the window without readable waiting feedback");
        assertNull(root.lookup("#shutdown-failure-notice"));assertFalse(notice.isDisabled());assertEquals(1,notice.getOpacity());assertInScene(stage,notice);
        String[] ids={"title","state","caution"};String[] messages={"正在退出，请稍候","正在处理退出请求。若出现确认对话框，请先完成选择；无需重复关闭窗口。","等待期间请勿强制结束进程，以免中断尚在进行的操作或丢失未保存内容。"};
        for(int i=0;i<ids.length;i++) {
            var label=(Label)root.lookup("#shutdown-pending-"+ids[i]);assertNotNull(label);assertEquals(messages[i],label.getText());
            assertFalse(label.isDisabled());assertEquals(1,label.getOpacity());assertTrue(label.getFont().getSize()>=13);assertTrue(label.isWrapText());assertInScene(stage,label);
            var rendered=(javafx.scene.text.Text)label.lookup(".text");assertNotNull(rendered);assertEquals(messages[i],rendered.getText(),"full waiting text without ellipsis");assertInScene(stage,rendered);
            var textBounds=rendered.localToScene(rendered.getLayoutBounds());var labelBounds=label.localToScene(label.getLayoutBounds());
            assertTrue(textBounds.getMinX()>=labelBounds.getMinX()-1 && textBounds.getMaxX()<=labelBounds.getMaxX()+1
                    && textBounds.getMinY()>=labelBounds.getMinY()-1 && textBounds.getMaxY()<=labelBounds.getMaxY()+1,"waiting text outside label: "+textBounds+" label "+labelBounds);
        }
        assertTrue(notice.lookupAll(".button").isEmpty());
    }
    @ParameterizedTest @CsvSource({"dark,900,600","light,900,600","dark,1200,800","light,1200,800"})
    void partialFailureLeavesPersistentReadableFeedbackOutsideDisabledBody(String theme,int width,int height) throws Exception {
        var result=new CompletableFuture<ShutdownOutcome>();var requests=new AtomicInteger();
        try(var f=new Fixture(theme,width,height,() -> false,() -> true,() -> {requests.incrementAndGet();return result;})) {
            awaitLayoutPulses();
            var bodyBounds=FxUiTestSupport.call(() -> {
                var bounds=f.body.localToScene(f.body.getBoundsInLocal());
                assertEquals(0,bounds.getMinX(),1);assertEquals(0,bounds.getMinY(),1);
                assertEquals(f.stage.getScene().getWidth(),bounds.getWidth(),1);assertEquals(f.stage.getScene().getHeight(),bounds.getHeight(),1);
                assertNull(f.controller.getRoot().lookup("#shutdown-failure-notice"),"notice reserves no normal body space");return bounds;
            });
            FxUiTestSupport.call(() -> {closeRequest(f.stage);assertTrue(f.body.isDisabled());return null;});
            var finished=new CountDownLatch(1);
            Thread.startVirtualThread(() -> {result.complete(ShutdownOutcome.FAILED_PARTIAL);finished.countDown();});
            assertTrue(finished.await(5,TimeUnit.SECONDS));awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                assertEquals(width,f.stage.getWidth(),1);assertEquals(height,f.stage.getHeight(),1);assertEquals(1,requests.get());
                assertFailureFeedback(f.stage,f.body);
                assertEquals(bodyBounds,f.body.localToScene(f.body.getBoundsInLocal()),"feedback must not resize the body");
                var notice=f.controller.getRoot().lookup("#shutdown-failure-notice");
                Color background=(Color)((javafx.scene.layout.Region)notice).getBackground().getFills().getFirst().getFill();
                Color foreground=(Color)((Label)notice.lookup("#shutdown-failure-state")).getTextFill();
                assertEquals(Color.web(theme.equals("dark") ? "#E8E8ED" : "#2E3440"),foreground);
                assertEquals(Color.web(theme.equals("dark") ? "#151520" : "#ECEFF4"),background);
                assertTrue(contrast(foreground,background)>=4.5,"persistent guidance remains readable in both themes");
                snapshotIfRequested(f.stage,theme,width,height);
                closeRequest(f.stage);closeRequest(f.stage);assertEquals(1,requests.get());
                assertSame(notice,f.controller.getRoot().lookup("#shutdown-failure-notice"));
                assertFalse(result.complete(ShutdownOutcome.COMPLETED),"late settlement cannot replace the fatal result");return null;
            });awaitLayoutPulses();
            FxUiTestSupport.call(() -> {assertFailureFeedback(f.stage,f.body);assertEquals(1,requests.get());return null;});
        }
    }
    @Test void pendingRepeatedCloseRequestsDoNotRepeatCleanupAndCompletionClosesOnce() throws Exception {
        var result=new CompletableFuture<ShutdownOutcome>();var requests=new AtomicInteger();var hidden=new AtomicInteger();
        try(var f=new Fixture("dark",900,600,() -> false,() -> true,() -> {requests.incrementAndGet();return result;})) {
            FxUiTestSupport.call(() -> {
                f.stage.setOnHidden(event -> hidden.incrementAndGet());closeRequest(f.stage);closeRequest(f.stage);closeRequest(f.stage);
                assertEquals(1,requests.get());assertTrue(f.body.isDisabled());assertTrue(f.stage.isShowing());
                assertNull(f.controller.getRoot().lookup("#shutdown-failure-notice"));return null;
            });
            var finished=new CountDownLatch(1);
            Thread.startVirtualThread(() -> {result.complete(ShutdownOutcome.COMPLETED);finished.countDown();});
            assertTrue(finished.await(5,TimeUnit.SECONDS));awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                assertFalse(f.stage.isShowing());assertEquals(1,hidden.get());assertEquals(1,requests.get());
                assertTrue(f.body.isDisabled());assertNull(f.stage.getOnCloseRequest());return null;
            });
        }
    }
    @ParameterizedTest @ValueSource(strings={"cancelled","exception","null"})
    void preTeardownCancellationOrFailureRestoresInteractionAndAllowsAnotherClose(String outcome) throws Exception {
        var first=new CompletableFuture<ShutdownOutcome>();var second=new CompletableFuture<ShutdownOutcome>();var requests=new AtomicInteger();
        try(var f=new Fixture("light",900,600,() -> false,() -> true,() -> requests.incrementAndGet()==1 ? first : second)) {
            FxUiTestSupport.call(() -> {closeRequest(f.stage);assertTrue(f.body.isDisabled());return null;});
            var finished=new CountDownLatch(1);
            Thread.startVirtualThread(() -> {
                if(outcome.equals("exception"))first.completeExceptionally(new IllegalStateException("synthetic SECRET_SQL select password; /private/profile"));
                else first.complete(outcome.equals("null") ? null : ShutdownOutcome.CANCELLED);
                finished.countDown();
            });assertTrue(finished.await(5,TimeUnit.SECONDS));awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                assertTrue(f.stage.isShowing());assertFalse(f.body.isDisabled());assertNull(f.controller.getRoot().lookup("#shutdown-failure-notice"));
                assertFalse(f.controller.getRoot().toString().contains("SECRET_SQL"));closeRequest(f.stage);closeRequest(f.stage);
                assertEquals(2,requests.get());assertTrue(f.body.isDisabled());return null;
            });second.complete(ShutdownOutcome.COMPLETED);awaitLayoutPulses();
            FxUiTestSupport.call(() -> {assertFalse(f.stage.isShowing());assertEquals(2,requests.get());return null;});
        }
    }
    @Test void migrationConfirmationRejectsBeforeDisablingOrRequestingShutdown() throws Exception {
        var approvals=new AtomicInteger();var requests=new AtomicInteger();var allowed=new java.util.concurrent.atomic.AtomicBoolean();
        var result=new CompletableFuture<ShutdownOutcome>();
        try(var f=new Fixture("dark",900,600,() -> true,() -> {approvals.incrementAndGet();return allowed.get();},() -> {requests.incrementAndGet();return result;})) {
            FxUiTestSupport.call(() -> {
                closeRequest(f.stage);assertEquals(1,approvals.get());assertEquals(0,requests.get());assertFalse(f.body.isDisabled());assertTrue(f.stage.isShowing());
                allowed.set(true);closeRequest(f.stage);closeRequest(f.stage);assertEquals(2,approvals.get());assertEquals(1,requests.get());assertTrue(f.body.isDisabled());return null;
            });result.complete(ShutdownOutcome.CANCELLED);awaitLayoutPulses();
            FxUiTestSupport.call(() -> {assertTrue(f.stage.isShowing());assertFalse(f.body.isDisabled());return null;});
        }
    }
    static void assertFailureFeedback(Stage stage,Parent body) {
        var root=(Parent)stage.getScene().getRoot();root.applyCss();root.layout();
        assertTrue(stage.isShowing());assertTrue(body.isDisabled());
        var notice=root.lookup("#shutdown-failure-notice");assertNotNull(notice,"partial exit leaves a disabled main window with no visible explanation");
        assertFalse(notice.isDisabled());assertEquals(1,notice.getOpacity());assertInScene(stage,notice);
        StringBuilder text=new StringBuilder();
        for(String id:List.of("title","state","uncertain","next")) {
            var label=(Label)root.lookup("#shutdown-failure-"+id);assertNotNull(label);assertFalse(label.isDisabled());
            assertEquals(1,label.getOpacity());assertTrue(label.getFont().getSize()>=13);assertTrue(label.isWrapText());
            assertInScene(stage,label);
            var rendered=(javafx.scene.text.Text)label.lookup(".text");assertNotNull(rendered);assertEquals(label.getText(),rendered.getText(),"no ellipsis or omitted guidance");
            assertInScene(stage,rendered);
            var textBounds=rendered.localToScene(rendered.getLayoutBounds());var labelBounds=label.localToScene(label.getLayoutBounds());
            assertTrue(textBounds.getMinX()>=labelBounds.getMinX()-1 && textBounds.getMaxX()<=labelBounds.getMaxX()+1
                    && textBounds.getMinY()>=labelBounds.getMinY()-1 && textBounds.getMaxY()<=labelBounds.getMaxY()+1,
                    "real text layout exceeds label: "+textBounds+" label "+labelBounds);text.append(label.getText());
        }
        String message=text.toString();assertTrue(message.contains("重复关闭不会重试"));assertTrue(message.contains("可能已部分完成"));
        assertTrue(message.contains("无法确认"));assertTrue(message.contains("独立工具"));assertTrue(message.contains("手动结束"));assertTrue(message.contains("可能丢失"));
        assertFalse(message.contains("SECRET_SQL"));assertFalse(message.contains("select password"));assertFalse(message.contains("/private/profile"));
        assertFalse(message.contains("已回滚"));assertFalse(message.contains("已保存"));
        assertTrue(notice.lookupAll(".button").isEmpty(),"fatal feedback must not offer retry or automatic exit");
        System.out.println("SHUTDOWN geometry stage="+stage.getWidth()+"x"+stage.getHeight()+" scene="+stage.getScene().getWidth()+"x"+stage.getScene().getHeight()
                +" bodyDisabled="+body.isDisabled()+" guidance="+message);
    }
    static void closeRequest(Stage stage) {stage.fireEvent(new WindowEvent(stage,WindowEvent.WINDOW_CLOSE_REQUEST));}
    private static void assertInScene(Stage stage,javafx.scene.Node node) {
        var bounds=node.localToScene(node.getBoundsInLocal());var screen=node.localToScreen(node.getBoundsInLocal());
        System.out.println("SHUTDOWN node="+node.getId()+" bounds="+bounds+" screen="+screen);
        assertNotNull(screen);assertTrue(bounds.getMinX()>=-1 && bounds.getMaxX()<=stage.getScene().getWidth()+1
                && bounds.getMinY()>=-1 && bounds.getMaxY()<=stage.getScene().getHeight()+1,"outside shown scene: "+bounds);
        var window=stage.getScene().getRoot().localToScreen(stage.getScene().getRoot().getBoundsInLocal());
        assertTrue(screen.getMinX()>=window.getMinX()-1 && screen.getMaxX()<=window.getMaxX()+1
                && screen.getMinY()>=window.getMinY()-1 && screen.getMaxY()<=window.getMaxY()+1,"outside actual screen content bounds: "+screen+" content "+window);
    }
    private static void snapshotIfRequested(Stage stage,String theme,int width,int height) throws Exception {
        String destination=System.getenv("SHUTDOWN_FEEDBACK_SNAPSHOT_DIR");if(destination==null || width!=900 || height!=600)return;
        var directory=java.nio.file.Path.of(destination);java.nio.file.Files.createDirectories(directory);
        var image=stage.getScene().snapshot(null);var pixels=image.getPixelReader();
        var rendered=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<rendered.getHeight();y++)for(int x=0;x<rendered.getWidth();x++)rendered.setRGB(x,y,pixels.getArgb(x,y));
        var target=directory.resolve("synthetic-scene-900x600-"+theme+".png");
        assertFalse(java.nio.file.Files.exists(target),"snapshot evidence must be new");
        assertTrue(javax.imageio.ImageIO.write(rendered,"png",target.toFile()));
        System.out.println("SYNTHETIC_FX_SCENE_SNAPSHOT path="+target+" image="+rendered.getWidth()+"x"+rendered.getHeight()+" notNativeDesktop=true");
    }
    private static double contrast(Color first,Color second) {
        double one=luminance(first),two=luminance(second);return (Math.max(one,two)+0.05)/(Math.min(one,two)+0.05);
    }
    private static double luminance(Color color) {return 0.2126*linear(color.getRed())+0.7152*linear(color.getGreen())+0.0722*linear(color.getBlue());}
    private static double linear(double value) {return value<=0.04045 ? value/12.92 : Math.pow((value+0.055)/1.055,2.4);}
    static void awaitLayoutPulses() throws Exception {
        var ready=new CountDownLatch(1);
        FxUiTestSupport.call(() -> {new javafx.animation.AnimationTimer() {
            int frames;
            @Override public void handle(long now){if(++frames>=3){stop();ready.countDown();}}
        }.start();return null;});assertTrue(ready.await(5,TimeUnit.SECONDS));
    }
    private static final class Fixture implements AutoCloseable {
        final Stage stage;final BorderPane body;final WindowShutdownController controller;
        Fixture(String theme,int width,int height,java.util.function.BooleanSupplier running,java.util.function.BooleanSupplier confirm,
                java.util.function.Supplier<java.util.concurrent.CompletionStage<ShutdownOutcome>> shutdown) throws Exception {
            var data=FxUiTestSupport.call(() -> {
                var window=new Stage();var content=new BorderPane(new Label("synthetic body"));
                var handler=new WindowShutdownController(window,content,running,shutdown,confirm);
                var scene=new Scene(handler.getRoot(),width,height);scene.getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-"+theme+".css").toExternalForm());
                window.setScene(scene);window.show();window.setWidth(width);window.setHeight(height);return new Object[]{window,content,handler};
            });stage=(Stage)data[0];body=(BorderPane)data[1];controller=(WindowShutdownController)data[2];
        }
        @Override public void close() throws Exception {FxUiTestSupport.call(() -> {stage.setOnCloseRequest(null);stage.close();return null;});}
    }
}
