import com.datacube.fx.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.config.AppSettings;
import com.datacube.service.*;
import com.datacube.spi.model.*;
import javafx.application.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Programmatic synthetic row drafts and mock JDBC timing, followed by native save/confirm/cancel/close. */
public final class SolGridDesktopProbe extends Application {
    SolGridSaveBoundary probe;
    DataGridPane pane;
    FxTaskRunner runner = new FxTaskRunner();
    CountDownLatch release = new CountDownLatch(1);
    boolean block = Boolean.getBoolean("sol.block.write");
    static Object field(Object owner,String name) throws Exception {var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static void invoke(Object owner,String name) {try{var m=owner.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(owner);}catch(Exception e){throw new RuntimeException(e);}}
    void report(String event) {
        System.out.println(event+" opens="+probe.jdbc.opens+" closes="+probe.jdbc.closes+" writes="+probe.jdbc.executes+" commits="+probe.jdbc.commits+" rollbacks="+probe.jdbc.rollbacks);
    }
    public static final class Launcher {public static void main(String[] args){Application.launch(SolGridDesktopProbe.class,args);}}
    @Override public void start(Stage stage) throws Exception {
        Path profile=Path.of(System.getProperty("user.home"));
        if(!profile.getFileName().toString().equals("sol-grid-profile") || Files.exists(profile.resolve(".datacube")))throw new IllegalStateException("Fresh profile required");
        System.setOut(new java.io.PrintStream(Files.newOutputStream(profile.resolve("desktop-runtime.log"),StandardOpenOption.CREATE_NEW),true,java.nio.charset.StandardCharsets.UTF_8));
        probe=new SolGridSaveBoundary(DbType.POSTGRESQL,false,"PRODUCTION");
        if(block)probe.jdbc.onExecute=()->{
            report("MOCK_WRITE_ENTERED");
            boolean interrupted=false;
            while(true)try{release.await();break;}catch(InterruptedException e){interrupted=true;System.out.println("MOCK_WRITE_INTERRUPTED_STILL_OWNED");}
            if(interrupted)Thread.currentThread().interrupt();
            report("MOCK_WRITE_RELEASED");
        };
        pane=new DataGridPane(new DataBrowseService(probe.manager),probe.edit,"target","synthetic",new TableRef("synthetic","items"),new AppSettings(profile.resolve("settings")),false,runner);
        @SuppressWarnings("unchecked") var grid=(TableView<EditableGridModel.Row>)field(pane,"grid");
        var seeded=new java.util.concurrent.atomic.AtomicBoolean();
        grid.itemsProperty().addListener((o,a,b)->{
            if(b.isEmpty() || !seeded.compareAndSet(false,true))return;
            try {
                var model=(EditableGridModel)field(pane,"model");
                for(int i=0;i<2;i++){var row=b.get(i); var cell=row.cell(1);var set=cell.getClass().getDeclaredMethod("setText",String.class);set.setAccessible(true);set.invoke(cell,"synthetic-edit-"+i);var reconcile=model.getClass().getDeclaredMethod("reconcile",EditableGridModel.Row.class);reconcile.setAccessible(true);reconcile.invoke(model,row);}
                invoke(pane,"updateChanges"); report("PROGRAMMATIC_TWO_ROW_DRAFTS no save/fire");
            }catch(Exception e){throw new RuntimeException(e);}
        });
        Button unblock=new Button("夹具：释放 mock 写屏障"); unblock.setOnAction(e->{release.countDown();report("NATIVE_FIXTURE_RELEASE");});
        Button tighten=new Button("夹具：配置收紧只读");tighten.setOnAction(e->{probe.tighten();report("NATIVE_FIXTURE_CONFIG_TIGHTEN");});
        Button mandatory=new Button("夹具：请求强制关闭");mandatory.setOnAction(e->{
            var closing=pane.requestMandatoryClose().toCompletableFuture();
            report("MANDATORY_REQUEST done="+closing.isDone());
            closing.whenComplete((outcome,error)->Platform.runLater(()->{report("MANDATORY_RETURN outcome="+outcome);if(error==null && outcome==CloseGuardOutcome.APPROVED)finish(stage);}));
        });
        var root=new VBox(6,new FlowPane(8,6,unblock,tighten,mandatory),pane.getNode()); VBox.setVgrow(pane.getNode(),Priority.ALWAYS);
        var scene=new Scene(root,900,680); var theme=new ThemeManager(new AppSettings(profile.resolve("theme")));theme.register(scene);
        scene.focusOwnerProperty().addListener((o,a,b)->System.out.println("FOCUS="+(b==null?"none":b.getClass().getSimpleName()+":"+b.getId())));
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED,e->System.out.println("MAIN_KEY="+e.getCode()+" shift="+e.isShiftDown()));
        stage.setTitle("DataCube SOL 生产保存验收");stage.setScene(scene);stage.setOnCloseRequest(e->{e.consume();
            pane.requestClose().whenComplete((outcome,error)->Platform.runLater(()->{report("INTERACTIVE_CLOSE outcome="+outcome);if(outcome==CloseGuardOutcome.APPROVED)finish(stage);}));
        });stage.show();report("READY outputScale="+stage.getOutputScaleX());
    }
    void finish(Stage stage) {
        invoke(pane,"finalizeCloseOnFx");
        Thread.ofVirtual().start(()->{runner.close();probe.close();report("SHUTDOWN_COMPLETED");
            if(probe.jdbc.opens.get()!=probe.jdbc.closes.get())throw new AssertionError("Unbalanced JDBC");
            Platform.runLater(()->{stage.setOnCloseRequest(null);stage.close();});
        });
    }
}
