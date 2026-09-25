package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.migration.*;
import javafx.application.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real migration pane, synthetic operations, disposable profile; never uses JDBC or user settings. */
public final class G8MigrationDesktopFixture extends Application {
    public static final class Launcher {public static void main(String[] args){Application.launch(G8MigrationDesktopFixture.class,args);}}
    @Override public void start(Stage stage)throws Exception {
        Path profile=Path.of(System.getProperty("user.home"));
        if(!profile.getFileName().toString().equals("g8-migration-desktop-profile"))throw new IllegalStateException("Disposable G8 profile required");
        Files.createDirectories(profile);var runner=new FxTaskRunner();var theme=new ThemeManager(new AppSettings(profile.resolve("settings")));
        var hold=new java.util.concurrent.atomic.AtomicBoolean(); AtomicInteger released=new AtomicInteger(); AtomicInteger writes=new AtomicInteger(),network=new AtomicInteger();Label counts=new Label("仅合成夹具：真实连接=0，模拟导入=0");
        var controller=new MainController(runner.scope(),r->runner.submit(r),logger->new MigrationOperations(logger,(u,n,p)->{network.incrementAndGet();throw new AssertionError("No JDBC allowed");}) {
            @Override public MigrationPlan prepare(MigrationRequest request,MigrationCancellation cancellation)throws Exception{if(hold.get()){var gate=new java.util.concurrent.CountDownLatch(1);cancellation.register(()->{released.incrementAndGet();gate.countDown();});System.out.println("SYNTHETIC_PREPARE_WAITING");gate.await();cancellation.checkCancelled();}return MigrationPlanFixture.create(request);}
            @Override public void test(MigrationRequest r,MigrationCancellation c){logger.logInfo("合成连接检查；未访问网络");}
            @Override public void export(MigrationRequest r,MigrationCancellation c,int concurrency,boolean data)throws Exception{MigrationPlanFixture.create(r);}
            @Override public PgVerifier.Statistics statistics(MigrationRequest r,MigrationCancellation c){logger.logInfo("合成目标端统计：1 表，估算行数未知，非一致性结论");return new PgVerifier.Statistics(1,null,0,0);}
            @Override public MigrationRun execute(MigrationPlan plan,MigrationPlan.Approval approval,MigrationRequest request,MigrationCancellation cancellation){writes.incrementAndGet();return MigrationPlanFixture.result(plan,MigrationRun.State.COMMIT_UNKNOWN);}
        },null);
        VBox content=controller.createMigrationContent();
        field(content,"source.url").setText("jdbc:synthetic:source");field(content,"source.user").setText("synthetic");
        field(content,"target.url").setText("jdbc:synthetic:target");field(content,"target.user").setText("synthetic");
        field(content,"schema").setText("synthetic");field(content,"directory").setText(profile.resolve("exports").toString());
        Button toggle=new Button("切换明暗"),narrow=new Button("窄/宽窗口"),check=new Button("刷新合成计数");
        toggle.setOnAction(e->theme.toggle());narrow.setOnAction(e->stage.setWidth(stage.getWidth()>850?760:1100));
        check.setOnAction(e->counts.setText("仅合成夹具：真实连接="+network.get()+"，模拟导入="+writes.get()));
        CheckBox block=new CheckBox("阻塞合成预检查，等待取消（无网络）");block.setOnAction(e->hold.set(block.isSelected()));VBox root=new VBox(6,new javafx.scene.layout.FlowPane(8,4,toggle,narrow,check,block),counts,content);VBox.setVgrow(content,Priority.ALWAYS);
        Scene scene=new Scene(root,1100,850);theme.register(scene);theme.installWindowHook();stage.setScene(scene);stage.setTitle("DataCube G8 合成迁移取消验收");
        stage.setOnShown(e->System.out.println("SYNTHETIC_OUTPUT_SCALE="+stage.getOutputScaleX()+"x"+stage.getOutputScaleY()));
        stage.setOnCloseRequest(e->{controller.shutdown();runner.close();System.out.println("NETWORK="+network.get()+" SYNTHETIC_IMPORTS="+writes.get()+" CLEANUPS="+released.get());});stage.show();
    }
    private static TextField field(Parent root,String id){return (TextField)find(root,"migration."+id);}
    private static Node find(Parent root,String id){if(root instanceof TitledPane titled && titled.getContent() instanceof Parent content){Node found=find(content,id);if(found!=null)return found;}for(Node child:root.getChildrenUnmodifiable()){if(id.equals(child.getId()))return child;if(child instanceof Parent parent){Node found=find(parent,id);if(found!=null)return found;}}return null;}
}
