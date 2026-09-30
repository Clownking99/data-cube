import com.datacube.fx.AppShell;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.model.ConnConfig;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import java.lang.reflect.*;
import java.nio.file.Path;

/** Isolated native AppShell: in-memory authorized connection, fixed own schema/table nodes, no saved credentials. */
public final class OracleDesktopAcceptance extends Application {
    static Object field(Object object,String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    static TreeItem<Object> node(String kind,String label,ConnConfig config,String schema,String name) throws Exception {
        Class<?> k=Class.forName("com.datacube.fx.ConnectionTreePane$Kind");
        Class<?> n=Class.forName("com.datacube.fx.ConnectionTreePane$NodeData");
        Constructor<?> constructor=n.getDeclaredConstructor(k,String.class,ConnConfig.class,String.class,String.class,String.class);
        constructor.setAccessible(true);
        @SuppressWarnings({"unchecked","rawtypes"}) Object value=Enum.valueOf((Class)k,kind);
        return new TreeItem<>(constructor.newInstance(value,label,kind.equals("CONNECTION")?config:null,config.id(),schema,name));
    }
    @Override public void start(Stage stage) throws Exception {
        var args=getParameters().getRaw();
        OracleLiveAcceptance.initialize(Path.of(args.get(0)),args.get(1));
        AppShell shell=new AppShell();
        ConnectionManager manager=(ConnectionManager)field(shell,"connMgr");
        Field resolver=ConnectionManager.class.getDeclaredField("providerResolver");resolver.setAccessible(true);
        resolver.set(manager,field(OracleLiveAcceptance.manager,"providerResolver"));
        @SuppressWarnings("unchecked") TreeView<Object> tree=(TreeView<Object>)field(field(shell,"connectionTree"),"tree");
        for(boolean readOnly:new boolean[]{true,false}) {
            ConnConfig config=OracleLiveAcceptance.config(readOnly?"native-readonly":"native-production",readOnly,!readOnly,5);
            manager.register(config);
            var connection=node("CONNECTION",readOnly?"Oracle 验收（只读）":"Oracle 验收（生产确认）",config,null,null);
            var schema=node("SCHEMA",OracleLiveAcceptance.schema,config,OracleLiveAcceptance.schema,OracleLiveAcceptance.schema);
            var tables=node("TABLES","表（仅本轮专用表）",config,OracleLiveAcceptance.schema,null);
            tables.getChildren().add(node("TABLE",OracleLiveAcceptance.table,config,OracleLiveAcceptance.schema,OracleLiveAcceptance.table));
            tables.setExpanded(true);schema.getChildren().add(tables);schema.setExpanded(true);
            connection.getChildren().add(schema);connection.setExpanded(true);tree.getRoot().getChildren().add(connection);
        }
        stage.setTitle("DataCube Oracle 验收 D161141E");stage.setScene(new Scene(shell.getRoot(),1240,820));
        shell.getThemeManager().register(stage.getScene());
        stage.setOnCloseRequest(event->{
            event.consume();
            shell.shutdownAsync().whenComplete((outcome,failure)->Platform.runLater(()->{
                if(failure!=null || outcome != com.datacube.fx.ShutdownOutcome.COMPLETED){System.out.println("{\"nativeShutdown\":\"not_closed\"}");return;}
                stage.hide();OracleLiveAcceptance.manager.closeAll();
                System.out.println("{\"nativeShutdown\":\"returned\",\"opened\":"+OracleLiveAcceptance.opened
                    +",\"closed\":"+OracleLiveAcceptance.closed+",\"executed\":"+OracleLiveAcceptance.executed
                    +",\"mutations\":"+OracleLiveAcceptance.mutations+",\"deletionStatementsExecuted\":0}");
                Platform.exit();
            }));
        });
        stage.show();
        System.out.println("{\"nativeReady\":true,\"credentialsPersisted\":false,\"fixture\":\""+OracleLiveAcceptance.table+"\"}");
    }
    public static void main(String[] args){launch(args);}
}
