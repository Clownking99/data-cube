package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.*;
import com.datacube.service.SchemaMetadataSearch.*;
import com.datacube.spi.model.*;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.nio.file.*;
import java.util.*;
import javafx.application.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/** Only synthetic data; every provider/session/network request is trapped. Not part of the app image. */
public final class G6DiscoveryDesktopFixture extends Application {
    public static final class Launcher { public static void main(String[] args) { Application.launch(G6DiscoveryDesktopFixture.class,args); } }
    public static void main(String[] args) { launch(args); }
    @Override public void start(Stage stage) throws Exception {
        Path profile=Path.of(System.getProperty("user.home"));
        if(!profile.getFileName().toString().equals("g6-discovery-desktop-profile"))
            throw new IllegalStateException("Explicit disposable G6 profile required");
        Files.createDirectories(profile);
        var probe=new DraftConnectionProbe(); var runner=new FxTaskRunner();
        var tabs=new ContentTabPane(); var registry=new SqlFileTabRegistry(); var created=new ArrayList<SqlEditorPane>();
        var settings=new AppSettings(profile.resolve("settings")); var theme=new ThemeManager(settings);
        var history=new SqlHistoryStore(profile.resolve("synthetic-history")); var recent=new RecentSqlFiles(profile.resolve("recent"));
        var shortcuts=new ShortcutSettings(profile.resolve("shortcuts"));
        var favorite=new SqlFavorite(UUID.fromString("00000000-0000-0000-0000-000000000006"),"订单字段示例","入门",
                "select 'synthetic 中😀' as label;\n-- 仅合成文本：未连接、未执行",1);
        java.util.function.Consumer<SqlFavorite> open=value -> SqlFavoriteTabs.open(tabs,value,() -> {
            var pane=SqlEditorPane.openSqlFile(new SessionContext(),probe.manager,new ObjectTreeService(probe.manager),
                    settings,(id,table) -> { throw new AssertionError("No business navigation"); },history,shortcuts,runner);
            created.add(pane); return pane;
        },List::of,new SqlScriptFileStore(),recent,new AppShell.SqlFileDraftLifecycle() {
            @Override public void bind(SqlEditorPane pane) {}
            @Override public void installed(Node node) {}
        },registry);
        open.accept(favorite);
        var repository=new SqlFavoritesDialog.Repository() {
            final List<SqlFavorite> values=new ArrayList<>(List.of(favorite));
            public SqlFavoriteStore.Snapshot load() { return new SqlFavoriteStore.Snapshot(values,List.of(),0,true); }
            public void save(SqlFavorite v,SqlFavorite old) { values.remove(old); values.add(v); }
            public void delete(SqlFavorite v) { values.remove(v); }
            public SqlFavorite recover(UUID id,long now) { throw new UnsupportedOperationException(); }
        };
        Button favorites=new Button("合成收藏"),metadata=new Button("合成字段/注释"),toggle=new Button("切换明暗"),narrow=new Button("640/1000 宽");
        favorites.setOnAction(e -> { try(var d=new SqlFavoritesDialog(repository,stage,runner,"")) { d.dialog().showAndWait().ifPresent(open); } });
        var target=new ConnConfig("synthetic","合成连接",DbType.POSTGRESQL,"example.invalid",1,"synthetic","","",Map.of());
        metadata.setOnAction(e -> { try(var d=new SchemaMetadataSearchDialog(target,"Exact Schema",stage,runner,(request,control) ->
            new Result(List.of(new Hit(new TableInfo("Exact Schema","orders",TableInfo.Kind.TABLE,null),request.mode(),
                request.mode()==Mode.OBJECT_COMMENT ? null : "customer_id","客户编号 · 仅合成注释，不代表真实数据库完整性")),false),() -> true)) { d.dialog().showAndWait(); } });
        toggle.setOnAction(e -> theme.toggle()); narrow.setOnAction(e -> stage.setWidth(stage.getWidth()>700 ? 640 : 1000));
        Label counts=new Label("仅合成数据：provider/session/metadata/network 均应为 0");
        Button check=new Button("检查离线计数"); check.setOnAction(e -> counts.setText("provider="+probe.providers.get()+" session="+probe.sessions.get()+" metadata="+probe.metadata.get()+" network="+probe.network.get()));
        VBox root=new VBox(6,new FlowPane(8,4,favorites,metadata,toggle,narrow,check),counts,tabs.getNode()); VBox.setVgrow(tabs.getNode(),Priority.ALWAYS);
        Scene scene=new Scene(root,1000,850); theme.register(scene); theme.installWindowHook();
        scene.addEventFilter(KeyEvent.KEY_PRESSED,e -> { if(e.getCode()==KeyCode.F6) { theme.toggle(); e.consume(); } });
        stage.setTitle("DataCube G6 合成验收"); stage.setScene(scene);
        stage.setOnShown(e -> System.out.println("SYNTHETIC_OUTPUT_SCALE="+stage.getOutputScaleX()+"x"+stage.getOutputScaleY()));
        stage.setOnCloseRequest(e -> { e.consume(); root.setDisable(true); Thread.startVirtualThread(() -> {
            created.forEach(SqlEditorPane::closeResources); runner.close(); probe.manager.closeAll();
            Platform.runLater(() -> { created.forEach(SqlEditorPane::finalizeCloseOnFx); stage.setOnCloseRequest(null); stage.close(); });
        }); }); stage.show();
    }
}
