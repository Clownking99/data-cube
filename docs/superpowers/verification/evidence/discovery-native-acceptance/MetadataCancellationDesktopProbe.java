package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.SchemaMetadataSearch.*;
import com.datacube.spi.model.*;
import java.nio.file.*;
import java.sql.Statement;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/** Visible synthetic acceptance only; the Loader never creates a connection. */
public final class MetadataCancellationDesktopProbe extends Application {
    public static final class Launcher {
        public static void main(String[] args) { Application.launch(MetadataCancellationDesktopProbe.class,args); }
    }
    private final CountDownLatch readRelease=new CountDownLatch(1),cancelRelease=new CountDownLatch(1);
    private final FxTaskRunner runner=new FxTaskRunner();
    private final AtomicInteger reads=new AtomicInteger(),cancels=new AtomicInteger();
    @Override public void start(Stage unused) throws Exception {
        Path profile=Path.of(System.getProperty("user.home"));
        if(!profile.getFileName().toString().equals("metadata-cancellation-profile") || Files.exists(profile.resolve(".datacube")))
            throw new IllegalStateException("Exclusive new synthetic profile required");
        var target=new ConnConfig("synthetic","合成离线检索",DbType.POSTGRESQL,"example.invalid",1,"demo","","",Map.of());
        var view=new SchemaMetadataSearchDialog(target,"demo",null,runner,(request,control) -> {
            int call=reads.incrementAndGet(); System.out.println("READ_START="+call);
            if(call==1) {
                var statement=(Statement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Statement.class},(p,m,a) -> {
                    if(m.getName().equals("cancel")) {
                        cancels.incrementAndGet(); System.out.println("CANCEL_ENTER");
                        cancelRelease.await(); System.out.println("CANCEL_RETURN");
                    }
                    return null;
                });
                var token=control.activate(statement,0);
                try { readRelease.await(); } finally { control.release(token); System.out.println("READ_RETURN=1"); }
            }
            return new Result(List.of(new Hit(new TableInfo("demo",call==1?"discarded_old":"retry_table",TableInfo.Kind.TABLE,null),
                    request.mode(),"customer_id",request.term())),false);
        },() -> true);
        var dialog=view.dialog(); dialog.setTitle("DataCube 检索取消合成验收");
        var pane=dialog.getDialogPane();
        pane.getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                ThemeManager.class.getResource("theme-dark.css").toExternalForm());
        var read=new Button("完成合成读取"); read.setOnAction(e -> {readRelease.countDown(); read.setDisable(true);});
        var cancel=new Button("完成合成取消"); cancel.setOnAction(e -> {cancelRelease.countDown(); cancel.setDisable(true);});
        var theme=new Button("切换明暗"); theme.setOnAction(e -> {
            boolean dark=pane.getStylesheets().getLast().contains("dark");
            pane.getStylesheets().set(1,ThemeManager.class.getResource(dark?"theme-light.css":"theme-dark.css").toExternalForm());
        });
        ((VBox)pane.getContent()).getChildren().add(new VBox(4,new Label("合成验收控制（不会联网）"),new FlowPane(8,4,read,cancel,theme)));
        var original=dialog.getOnHidden();
        dialog.setOnHidden(e -> {original.handle(e); readRelease.countDown(); cancelRelease.countDown(); Platform.exit();});
        dialog.show();
        System.out.println("OUTPUT_SCALE="+pane.getScene().getWindow().getOutputScaleX());
    }
    @Override public void stop() {
        readRelease.countDown(); cancelRelease.countDown(); runner.close();
        System.out.println("CLOSED reads="+reads.get()+" cancels="+cancels.get()+" realConnections=0");
    }
}
