import com.datacube.fx.*;
import com.datacube.config.ConnectionStore;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.*;
import com.datacube.spi.model.*;
import com.datacube.provider.postgres.PostgresProvider;
import javafx.application.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Synthetic startup only; no prefill or programmatic focus/input. Native interactions only. Never uses DriverManager. */
public final class MetadataNativeKeyboardProbe extends Application {
    static final AtomicInteger opens=new AtomicInteger(), closes=new AtomicInteger(), searches=new AtomicInteger(),
            pages=new AtomicInteger(), ddls=new AtomicInteger(), writes=new AtomicInteger(), executions=new AtomicInteger();
    static final ConnConfig TARGET=new ConnConfig("synthetic-shell", "合成检索连接", DbType.POSTGRESQL,
            "example.invalid", 1, "synthetic", "synthetic", "", Map.of("readOnly","true"));
    static void report() {
        System.out.println("COUNTERS mockOpens="+opens+" mockCloses="+closes+" searches="+searches
                +" pages="+pages+" ddls="+ddls+" writeAttempts="+writes+" executionAttempts="+executions);
    }
    public static final class Launcher {
        public static void main(String[] args) { Application.launch(MetadataNativeKeyboardProbe.class,args); }
    }
    static Object field(Object target,String name) throws Exception {
        var f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type,InvocationHandler h) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type}, (p,m,a)->{
            if (m.getName().equals("toString")) return "Synthetic "+type.getSimpleName();
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("equals")) return p==a[0];
            return h.invoke(p,m,a);
        });
    }
    static void table(Object value) {
        if (!new TableRef("demo","orders").equals(value)) throw new AssertionError("Unexpected synthetic table");
    }
    static Connection connection() {
        opens.incrementAndGet(); var closed=new java.util.concurrent.atomic.AtomicBoolean();
        return proxy(Connection.class,(p,m,a)->switch(m.getName()){
            case "close" -> { if(closed.compareAndSet(false,true)) closes.incrementAndGet(); report(); yield null; }
            case "isClosed" -> closed.get();
            case "isValid" -> !closed.get();
            case "getAutoCommit" -> true;
            case "prepareStatement" -> {
                String sql=(String)a[0];
                if(!sql.startsWith("SELECT t.table_name, t.table_type, ") || !sql.contains("FROM information_schema.tables t"))
                    throw new SQLException("Only fixed metadata search is allowed");
                yield statement(sql,(Connection)p);
            }
            default -> throw new SQLException("Unexpected mock connection method "+m.getName());
        });
    }
    static PreparedStatement statement(String sql,Connection c) {
        var bindings=new HashMap<Integer,String>();
        return proxy(PreparedStatement.class,(p,m,a)->switch(m.getName()){
            case "setString" -> { bindings.put((Integer)a[0],(String)a[1]); yield null; }
            case "setQueryTimeout" -> { if((Integer)a[0]!=10) throw new AssertionError("Timeout"); yield null; }
            case "setMaxRows" -> { if((Integer)a[0]!=201) throw new AssertionError("Rows"); yield null; }
            case "executeQuery" -> {
                if(!"demo".equals(bindings.get(1)) || !Set.of("customer","订单").contains(bindings.get(2)))
                    throw new AssertionError("Unexpected search bindings");
                searches.incrementAndGet();
                System.out.println("SEARCH connection=synthetic-shell schema=demo term="+bindings.get(2)+" mode="+(sql.contains("col_description")?"COLUMN_COMMENT":sql.contains("obj_description")?"OBJECT_COMMENT":"COLUMN_NAME"));
                var cursor=new AtomicInteger();
                yield proxy(ResultSet.class,(rp,rm,ra)->switch(rm.getName()){
                    case "next" -> cursor.incrementAndGet()<=3;
                    case "getString" -> switch((Integer)ra[0]){
                        case 1 -> List.of("orders","customer_events","customer_notes").get(cursor.get()-1);
                        case 2 -> "BASE TABLE";
                        case 3 -> sql.contains("obj_description") ? null : List.of("customer_id","customer_email","customer_note").get(cursor.get()-1);
                        case 4 -> "customer 合成预览 " + cursor.get();
                        default -> throw new SQLException("Unexpected result column");
                    };
                    case "close" -> null;
                    default -> throw new SQLException("Unexpected mock result method "+rm.getName());
                });
            }
            case "close", "cancel" -> null;
            case "getConnection" -> c;
            default -> throw new SQLException("Unexpected mock statement method "+m.getName());
        });
    }
    static DatabaseProvider provider() {
        SqlDialect dialect=new PostgresProvider().dialect(); // Stateless formatting only.
        var factory=new ConnectionFactory(){
            public void ensureDriverLoaded(){}
            public Connection open(ConnConfig cfg) {
                if (!cfg.id().equals(TARGET.id()) || !cfg.host().equals("example.invalid")
                        || cfg.port()!=1 || !cfg.encryptedPassword().isEmpty())
                    throw new AssertionError("Unexpected connection target");
                return connection();
            }
            public String test(ConnConfig cfg){throw new AssertionError("Connection testing forbidden");}
        };
        MetadataReader metadata=proxy(MetadataReader.class,(p,m,a)->switch(m.getName()){
            case "schemas" -> { if(!"synthetic".equals(a[0])) throw new AssertionError(); yield List.of(new SchemaInfo("synthetic","demo")); }
            case "tableAndViewNames","tables" -> {
                if(!"demo".equals(a[0])) throw new AssertionError();
                yield List.of(new TableInfo("demo","orders",TableInfo.Kind.TABLE,null));
            }
            case "columns" -> { table(a[0]); yield List.of(new ColumnInfo("customer_id","integer",false,null,1,false,"合成客户")); }
            case "views","indexes","constraints","routines","packages","triggers","types","sequences" -> List.of();
            default -> throw new AssertionError("Unexpected metadata method "+m.getName());
        });
        DataAccessor data=new DataAccessor(){
            public PagedResult page(TableRef t,long offset,int limit,List<SortKey> sorts,String filter) {
                table(t); if(offset!=0 || (filter!=null&&!filter.isBlank())) throw new AssertionError();
                pages.incrementAndGet(); report();
                return new PagedResult(List.of("customer_id"),List.of(List.of(101),List.of(202)),false);
            }
            public long count(TableRef t,String filter) {table(t); return 2;}
        };
        DdlGenerator ddl=proxy(DdlGenerator.class,(p,m,a)->{
            if(!m.getName().equals("tableDdl")) throw new AssertionError("Unexpected DDL kind");
            table(a[0]); ddls.incrementAndGet(); report();
            return "CREATE TABLE \"demo\".\"orders\" (\n  \"customer_id\" integer NOT NULL\n);\n-- 合成 DDL，仅供本地验收";
        });
        SqlRunner runner=proxy(SqlRunner.class,(p,m,a)->{executions.incrementAndGet(); throw new AssertionError("SQL execution forbidden");});
        DataEditor editor=proxy(DataEditor.class,(p,m,a)->{ if(m.getName().equals("columns")) { table(a[0]); return List.of(new EditableColumn("customer_id",Types.INTEGER,"integer",false,true,false,false,"合成客户")); } writes.incrementAndGet(); throw new AssertionError("Data editing forbidden"); });
        return proxy(DatabaseProvider.class,(p,m,a)->switch(m.getName()){
            case "type" -> DbType.POSTGRESQL;
            case "supports" -> false;
            case "dialect" -> dialect;
            case "connectionFactory" -> factory;
            case "metadataReader" -> metadata;
            case "dataAccessor" -> data;
            case "ddlGenerator" -> ddl;
            case "sqlRunner" -> runner;
            case "dataEditor" -> editor;
            case "schemaDiffCapability","resultFilterSqlRenderer" -> Optional.empty();
            default -> throw new AssertionError("Unexpected provider capability "+m.getName());
        });
    }
    static void state(Window window, String event) {
        if (window.getScene()==null) return;
        Scene scene=window.getScene();
        System.out.println("STATE event="+event+" title="+(window instanceof Stage stage?stage.getTitle():"owned")
                +" geometry="+window.getX()+","+window.getY()+","+window.getWidth()+"x"+window.getHeight()
                +" focus="+(scene.getFocusOwner()==null?"none":scene.getFocusOwner().getClass().getSimpleName()+":"+scene.getFocusOwner().getId()));
        for(String id:List.of("metadata-search-query","metadata-search-mode","metadata-search-results",
                "metadata-search-preview","metadata-search-status","metadata-search-submit",
                "metadata-search-select","metadata-search-data","metadata-search-ddl")) {
            Node node=scene.getRoot().lookup("#"+id); if(node==null)continue;
            Object value=node instanceof TextInputControl input?input.getText():node instanceof Label label?label.getText():
                    node instanceof ChoiceBox<?> choice?choice.getValue():node instanceof ListView<?> list?
                    "items="+list.getItems().size()+", selected="+list.getSelectionModel().getSelectedIndex():"";
            System.out.println("NODE id="+id+" disabled="+node.isDisabled()+" visible="+node.isVisible()
                    +" bounds="+node.localToScene(node.getBoundsInLocal())+" value="+value);
        }
        report();
    }
    static void observe(Window window) {
        if(window.getScene()==null) return;
        Scene scene=window.getScene();
        if(scene.getRoot().lookup("#metadata-search-query")==null) return;
        if(window instanceof Stage owned) owned.setTitle(System.getProperty("probe.title")+" | 字段查找");
        scene.focusOwnerProperty().addListener((o,a,b)->state(window,"focus"));
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED,e->{
            System.out.println("DIALOG_KEY="+e.getCode()+" ctrl="+e.isControlDown()+" shift="+e.isShiftDown());
            Platform.runLater(()->state(window,"after-key-"+e.getCode()));
        });
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED,e->System.out.println("DIALOG_TYPED="+e.getCharacter()));
        ((TextField)scene.getRoot().lookup("#metadata-search-query")).textProperty().addListener((o,a,b)->{
            System.out.println("QUERY_CHANGE="+b); Platform.runLater(()->state(window,"query-change"));
        });
        ((ChoiceBox<?>)scene.getRoot().lookup("#metadata-search-mode")).valueProperty().addListener((o,a,b)->{
            System.out.println("MODE_CHANGE="+b); Platform.runLater(()->state(window,"mode-change"));
        });
        ((ListView<?>)scene.getRoot().lookup("#metadata-search-results")).getSelectionModel()
                .selectedIndexProperty().addListener((o,a,b)->Platform.runLater(()->state(window,"selection")));
        ((Label)scene.getRoot().lookup("#metadata-search-status")).textProperty()
                .addListener((o,a,b)->Platform.runLater(()->state(window,"status")));
        window.widthProperty().addListener((o,a,b)->Platform.runLater(()->state(window,"width")));
        window.heightProperty().addListener((o,a,b)->Platform.runLater(()->state(window,"height")));
        state(window,"shown-empty");
    }
    @Override public void start(Stage stage) throws Exception {
        Path profile=Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("metadata-native-profile") || Files.exists(profile.resolve(".datacube")))
            throw new IllegalStateException("New exclusive synthetic profile required");
        System.setOut(new java.io.PrintStream(Files.newOutputStream(profile.resolve("desktop-runtime.log"), StandardOpenOption.CREATE_NEW),true,java.nio.charset.StandardCharsets.UTF_8));
        Window.getWindows().addListener((javafx.collections.ListChangeListener<Window>)change -> {
            while(change.next()) if(change.wasAdded()) for(Window added:change.getAddedSubList())
                Platform.runLater(() -> observe(added));
        });
        var shell=new AppShell();
        var manager=(ConnectionManager)field(shell,"connMgr");
        var f=ConnectionManager.class.getDeclaredField("providerResolver"); f.setAccessible(true);
        DatabaseProvider mock=provider();
        f.set(manager,(Function<DbType,DatabaseProvider>)type->{
            if(type!=DbType.POSTGRESQL)throw new AssertionError("Unexpected database type"); return mock;
        });
        ((ConnectionStore)field(shell,"store")).saveAll(List.of(TARGET));
        ((ConnectionTreePane)field(shell,"connectionTree")).refresh();
        System.out.println("STARTUP_ONLY_MOCK_PROVIDER_INJECTED no DriverManager path");
        Button theme=new Button("切换明暗"); theme.setOnAction(e->shell.getThemeManager().toggle());
        VBox host=new VBox(4,new FlowPane(theme),shell.getRoot()); VBox.setVgrow(shell.getRoot(),Priority.ALWAYS);
        var scene=new Scene(host,1100,720); shell.getThemeManager().register(scene); shell.getThemeManager().installWindowHook();
        stage.setTitle(System.getProperty("probe.title")); stage.setScene(scene); BrandLogo.applyIcons(stage);
        stage.setOnShown(e->System.out.println("OUTPUT_SCALE="+stage.getOutputScaleX()));
        scene.focusOwnerProperty().addListener((o,a,b)->System.out.println("FOCUS="+(b==null?"none":b.getClass().getSimpleName()+":"+b.getId())));
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED,e->System.out.println("NATIVE_OR_FX_KEY="+e.getCode()+" shift="+e.isShiftDown()));
        stage.setOnCloseRequest(e->{
            e.consume(); host.setDisable(true);
            shell.shutdownAsync().whenComplete((outcome,error)->Platform.runLater(()->{
                if(error!=null){error.printStackTrace();return;}
                report();
                if(outcome!=ShutdownOutcome.COMPLETED){host.setDisable(false);System.out.println("SHUTDOWN_"+outcome);return;}
                if(opens.get()!=closes.get()||writes.get()!=0||executions.get()!=0)throw new AssertionError("Unsafe or leaked mock resources");
                System.out.println("SHUTDOWN_COMPLETED");
                stage.setOnCloseRequest(null);stage.close();
            }));
        }); stage.show();
    }
}
