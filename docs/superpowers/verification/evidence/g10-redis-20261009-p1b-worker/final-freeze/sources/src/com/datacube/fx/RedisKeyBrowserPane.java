package com.datacube.fx;

import com.datacube.redis.*;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.model.ConnConfig;
import com.datacube.fx.task.FxSerialTaskQueue;
import com.datacube.fx.task.FxTaskRunner;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** SCAN candidates and five typed editors, with source-bound single-flight work. */
public final class RedisKeyBrowserPane implements AutoCloseable {
    private static final int SCAN_COUNT=500,COLLECTION_PAGE=200,PREVIEW_BYTES=4096;
    private static final long LARGE_VALUE=1024L*1024;
    private final IntFunction<RedisSession> openSession;
    private final Consumer<RedisSession> closeSession;
    private final Consumer<String> notifyError;
    private final Runnable beforeInstall;
    private final RedisDisplayLimits limits;
    private final FxSerialTaskQueue io;
    private final Object sessionLock=new Object();
    private final BorderPane root=new BorderPane();
    private final TreeView<TreeEntry> tree=new TreeView<>();
    private final VBox details=new VBox(8);
    private final TextField pattern=new TextField("*"),separator=new TextField(":");
    private final ComboBox<Integer> database=new ComboBox<>();
    private final Button loadMore=new Button("加载更多");
    private final Label status=new Label("就绪");
    private RedisSession session,opening;
    private volatile boolean closed;
    private volatile long generation;
    private RedisKeySnapshot snapshot;
    private int sessionDb=-1,wantedDb;
    private boolean busy,installing,pendingRefresh;
    private long nextRequest,activeRequest,valueEpoch;
    private Runnable pending;
    private String desiredKey,displayedKey;
    private KeyMeta displayedMeta;
    private Binding displayedBinding;
    private long displayedPageOffset;

    public RedisKeyBrowserPane(ConnectionManager manager,ConnConfig config,int db,FxTaskRunner runner) {
        this(db,database->manager.openRedisSession(config.id(),database),manager::closeRedisSession,runner,
                RedisDisplayLimits.DEFAULT,RedisKeyBrowserPane::alert,()->{});
    }
    RedisKeyBrowserPane(int db,IntFunction<RedisSession> open,Consumer<RedisSession> close,FxTaskRunner runner,
                        RedisDisplayLimits limits,Consumer<String> errors,Runnable beforeInstall) {
        this.openSession=open; this.closeSession=close; this.limits=limits; this.notifyError=errors; this.beforeInstall=beforeInstall;
        ConstructionOwner construction = new ConstructionOwner();
        try {
            this.io=new FxSerialTaskQueue(runner); construction.own(this::stopIo); construction.ownBlocking(this::closeCurrentSession);
            build(db); requestRefresh(); construction.commit();
        } catch(Throwable failure) { throw construction.close(failure).failure(); }
    }
    public Node getNode() { return root; }
    private void build(int db) {
        root.setPadding(new Insets(10)); for(int i=0;i<16;i++) database.getItems().add(i);
        wantedDb=Math.max(0,Math.min(15,db)); database.setValue(wantedDb); database.setPrefWidth(82); database.setId("redis-database");
        pattern.setPromptText("SCAN MATCH glob"); separator.setPromptText("分隔符"); separator.setPrefWidth(55);
        RedisConsolePane.limit(pattern,limits.matchChars()); RedisConsolePane.limit(separator,limits.separatorChars());
        pattern.setId("redis-match"); separator.setId("redis-separator"); tree.setId("redis-keys"); details.setId("redis-details"); status.setId("redis-status"); loadMore.setId("redis-load-more");
        Button refresh=new Button("刷新"); refresh.setId("redis-refresh"); refresh.setOnAction(e->requestRefresh());
        Button create=new Button("＋ 新建键"); create.setOnAction(e->createKey());
        HBox controls=new HBox(6,new Label("DB"),database,pattern,new Label("分隔"),separator,refresh,create);
        controls.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(pattern,Priority.ALWAYS);
        tree.setShowRoot(false); tree.setRoot(new TreeItem<>(new TreeEntry("root",null,null,null))); tree.setCellFactory(tv->new KeyCell());
        tree.getSelectionModel().selectedItemProperty().addListener((obs,old,item)-> {
            if(!installing && item!=null && item.getValue().key()!=null) loadKey(item.getValue().key());
        });
        loadMore.setOnAction(e->loadNextKeys()); VBox left=new VBox(6,controls,tree,loadMore,status); VBox.setVgrow(tree,Priority.ALWAYS);
        details.setPadding(new Insets(0,0,0,8)); details.getChildren().add(new Label("选择一个键查看和编辑值"));
        ScrollPane scroll=new ScrollPane(details); scroll.setFitToWidth(true); SplitPane split=new SplitPane(left,scroll);
        split.setOrientation(Orientation.HORIZONTAL); split.setDividerPositions(0.38); root.setCenter(split);
        database.valueProperty().addListener((obs,old,value)-> { if(value!=null && !value.equals(old)) { wantedDb=value; requestRefresh(); } });
        pattern.setOnAction(e->requestRefresh()); separator.setOnAction(e->rebuildTree()); updateControls();
    }
    private record Refresh(int db,String match,String separator,long generation) {}
    private void requestRefresh() {
        if(closed) return;
        String match=pattern.getText().isBlank()?"*":pattern.getText().trim();
        Refresh intent=new Refresh(wantedDb,match,separator.getText(),++generation); desiredKey=null; updateControls();
        if(busy) { pending=()->startRefresh(intent); pendingRefresh=true; }
        else startRefresh(intent);
    }
    private void startRefresh(Refresh intent) {
        if(closed || intent.generation()!=generation) return;
        RedisSession captured=session; boolean fresh=captured==null || sessionDb!=intent.db();
        submit(intent.generation(),()-> {
            RedisSession lease=fresh?openOwned(intent.db(),intent.generation()):captured;
            if(lease==null) return null;
            try {
                if(fresh && !lease.ping()) throw new IllegalStateException("PING 返回异常");
                RedisKeySnapshot candidate=RedisKeySnapshot.candidate(null,lease.scan(0,intent.match(),SCAN_COUNT),intent.db(),intent.match(),intent.separator(),limits);
                ScanResult result=new ScanResult(candidate,treeModel(candidate.tree()),lease,fresh);
                if(!live(intent.generation())) { discardScan(result); return null; }
                return result;
            } catch(Exception | Error failure) { if(fresh) closeOpening(lease,failure); throw failure; }
        },this::installScan,this::discardScan,"扫描 db"+intent.db()+"...");
    }
    private void loadNextKeys() {
        if(closed || busy || !sourceCurrent() || snapshot==null || snapshot.cursor()==0) return;
        RedisKeySnapshot old=snapshot; RedisSession captured=session; long gen=++generation; desiredKey=null;
        submit(gen,()-> {
            RedisKeySnapshot next=RedisKeySnapshot.candidate(old,captured.scan(old.cursor(),old.match(),SCAN_COUNT),old.database(),old.match(),old.separator(),limits);
            return new ScanResult(next,treeModel(next.tree()),captured,false);
        },this::installScan,this::discardScan,"扫描键...");
    }
    private void rebuildTree() {
        if(closed || snapshot==null || !sourceCurrent()) return;
        if(busy) { requestRefresh(); return; }
        String delimiter=separator.getText(); RedisKeySnapshot old=snapshot; RedisSession captured=session; long gen=++generation; desiredKey=null;
        submit(gen,()-> {
            KeyTreeBuilder.Node model=KeyTreeBuilder.build(List.copyOf(old.keys().keySet()),delimiter,limits);
            RedisKeySnapshot next=new RedisKeySnapshot(old.keys(),old.rawBytes(),model,old.cursor(),old.database(),old.match(),delimiter);
            return new ScanResult(next,treeModel(model),captured,false);
        },this::installScan,this::discardScan,"重建键树...");
    }
    private RedisSession openOwned(int db,long gen) {
        RedisSession created=openSession.apply(db); boolean reject;
        synchronized(sessionLock) { reject=!live(gen); if(!reject) opening=created; }
        if(reject) { closeSession.accept(created); return null; }
        return created;
    }
    private void closeOpening(RedisSession lease,Throwable original) {
        synchronized(sessionLock) { if(opening==lease) opening=null; }
        try { closeSession.accept(lease); }
        catch(RuntimeException closing) { if(original!=closing) original.addSuppressed(closing); }
        catch(Error closing) { if(original!=closing) closing.addSuppressed(original); throw closing; }
    }
    private boolean live(long gen) { return !closed && gen==generation; }
    private record TreeModel(String label,String key,List<TreeModel> children) {}
    private TreeModel treeModel(KeyTreeBuilder.Node node) {
        RedisDisplaySupport.Bounded label=new RedisDisplaySupport.Bounded(limits.labelChars()); label.append(node.segment());
        if(!node.children().isEmpty()) { label.append(" ("); label.append(Integer.toString(node.keyCount())); label.append(")"); }
        if(node.fullKey()!=null && !node.children().isEmpty()) label.append(" •");
        List<TreeModel> children=new ArrayList<>(0); for(var child:node.children()) children.add(treeModel(child));
        return new TreeModel(label.finish().text(),node.fullKey(),List.copyOf(children)); // Input depth already <=32.
    }
    private record ScanResult(RedisKeySnapshot snapshot,TreeModel tree,RedisSession lease,boolean fresh) {}
    private TreeItem<TreeEntry> treeItem(ScanResult result) {
        TreeItem<TreeEntry> rootItem=new TreeItem<>(entry(result.tree(),result));
        record Pair(TreeModel model,TreeItem<TreeEntry> item) {}
        ArrayDeque<Pair> todo=new ArrayDeque<>(); todo.push(new Pair(result.tree(),rootItem));
        while(!todo.isEmpty()) {
            Pair pair=todo.pop();
            for(TreeModel child:pair.model().children()) { TreeItem<TreeEntry> item=new TreeItem<>(entry(child,result)); pair.item().getChildren().add(item); todo.push(new Pair(child,item)); }
        }
        return rootItem;
    }
    private static TreeEntry entry(TreeModel node,ScanResult result) { return new TreeEntry(node.label(),node.key(),result.snapshot(),result.lease()); }
    private void installScan(ScanResult result) {
        if(result==null) return;
        TreeItem<TreeEntry> candidate=treeItem(result),oldRoot=tree.getRoot(); boolean committed=false;
        List<Node> oldDetails=List.copyOf(details.getChildren());
        boolean oldVisible=loadMore.isVisible(),oldManaged=loadMore.isManaged(); String oldMore=loadMore.getText(),oldStatus=status.getText();
        RedisSession old=session;
        installing=true;
        try {
            tree.setRoot(candidate); beforeInstall.run();
            details.getChildren().setAll(new Label("选择一个键查看和编辑值 · db"+result.snapshot().database()));
            loadMore.setVisible(result.snapshot().cursor()!=0); loadMore.setManaged(result.snapshot().cursor()!=0);
            loadMore.setText("加载更多（已加载 "+result.snapshot().keys().size()+"）");
            status.setText("db"+result.snapshot().database()+" · "+result.snapshot().keys().size()+" 个键"+(result.snapshot().cursor()==0?" · 扫描完成":" · 未完整加载"));
            synchronized(sessionLock) {
                if(closed) throw new IllegalStateException("Redis pane is closed");
                session=result.lease(); if(opening==result.lease()) opening=null;
                sessionDb=result.snapshot().database(); snapshot=result.snapshot(); displayedKey=null; displayedMeta=null; displayedBinding=null;
            }
            committed=true;
        } finally {
            if(!committed) { tree.setRoot(oldRoot); details.getChildren().setAll(oldDetails); loadMore.setVisible(oldVisible); loadMore.setManaged(oldManaged); loadMore.setText(oldMore); status.setText(oldStatus); }
            installing=false;
        }
        if(result.fresh() && old!=null && old!=result.lease()) closeSession.accept(old);
    }
    private void discardScan(ScanResult result) {
        if(result==null || !result.fresh()) return;
        boolean close;
        synchronized(sessionLock) { close=session!=result.lease(); if(opening==result.lease()) opening=null; }
        if(close) closeSession.accept(result.lease());
    }
    private boolean sourceCurrent() { return snapshot!=null && session!=null && wantedDb==sessionDb && snapshot.database()==sessionDb; }
    private record Binding(RedisSession session,RedisKeySnapshot source,int db,long generation,long epoch,String key,boolean value) {}
    private Binding sourceBinding(String key) { return sourceCurrent()?new Binding(session,snapshot,sessionDb,generation,valueEpoch,key,false):null; }
    private boolean valid(Binding binding) {
        return binding!=null && !closed && binding.session()==session && binding.source()==snapshot && binding.db()==wantedDb
                && binding.db()==sessionDb && binding.generation()==generation
                && (!binding.value() || binding.epoch()==valueEpoch && binding.key().equals(displayedKey) && binding.key().equals(desiredKey));
    }
    private void loadKey(String key) {
        if(closed || !sourceCurrent() || !snapshot.keys().containsKey(key)) return;
        if(busy && pendingRefresh) return; // The pending refresh owns its generation; old tree selections lose priority.
        desiredKey=key; long gen=++generation; updateControls();
        if(busy) { if(!pendingRefresh) pending=()->startKey(key,gen); return; }
        startKey(key,gen);
    }
    private void startKey(String key,long gen) {
        if(!live(gen) || !sourceCurrent()) return;
        Binding binding=new Binding(session,snapshot,sessionDb,gen,valueEpoch+1,key,true);
        submit(gen,()-> {
            KeyMeta meta=new KeyMeta(binding.session().type(key),binding.session().ttl(key));
            return new Loaded(binding,meta,readValue(binding.session(),key,meta.type(),0,false));
        },this::installValue,ignored->{},"读取键...");
    }
    private record KeyMeta(String type,long ttl) {}
    private record StringData(long length,RedisDisplaySupport.StringViews views) {}
    private record Loaded(Binding binding,KeyMeta meta,Object data) {}
    private Object readValue(RedisSession captured,String key,String type,long cursor,boolean full) {
        return switch(type.toLowerCase(Locale.ROOT)) {
            case "string" -> {
                long length=captured.strlen(key);
                if(full && length>RedisResourceLimits.DEFAULT.bulkBytes()) throw RedisDisplaySupport.rejected();
                boolean range=length>LARGE_VALUE && !full;
                byte[] bytes=range?captured.getrange(key,0,PREVIEW_BYTES-1):captured.get(key);
                yield new StringData(range?length:(bytes==null?0:bytes.length),RedisDisplaySupport.stringViews(bytes,!range,limits));
            }
            case "hash" -> RedisDisplaySupport.hash(captured.hscan(key,cursor,COLLECTION_PAGE),limits);
            case "list" -> RedisDisplaySupport.list(captured.lrange(key,cursor,Math.addExact(cursor,COLLECTION_PAGE-1)),cursor,captured.llen(key),limits);
            case "set" -> RedisDisplaySupport.set(captured.sscan(key,cursor,COLLECTION_PAGE),limits);
            case "zset" -> RedisDisplaySupport.zset(captured.zscan(key,cursor,COLLECTION_PAGE),limits);
            default -> "暂不支持的类型";
        };
    }
    private void loadPage(Binding old,String type,long cursor,boolean full) {
        if(busy || !valid(old)) return;
        Binding next=new Binding(old.session(),old.source(),old.db(),generation,valueEpoch+1,old.key(),true);
        KeyMeta meta=displayedMeta;
        submit(generation,()->new Loaded(next,meta,readValue(next.session(),next.key(),type,cursor,full)),this::installValue,ignored->{},"读取页面...");
    }
    private void installValue(Loaded loaded) {
        List<Node> nodes=new ArrayList<>(header(loaded.binding(),loaded.meta()));
        if(loaded.data() instanceof StringData data) nodes.addAll(stringEditor(loaded.binding(),data));
        else if(loaded.data() instanceof RedisDisplaySupport.CollectionPage page) nodes.addAll(collectionEditor(loaded.binding(),loaded.meta().type(),page));
        else nodes.add(new Label("暂不支持的类型"));
        List<Node> old=List.copyOf(details.getChildren());
        try { details.getChildren().setAll(nodes); beforeInstall.run(); }
        catch(RuntimeException | Error failure) { details.getChildren().setAll(old); throw failure; }
        valueEpoch=loaded.binding().epoch(); displayedKey=loaded.binding().key(); displayedMeta=loaded.meta(); displayedBinding=loaded.binding();
        displayedPageOffset=loaded.data() instanceof RedisDisplaySupport.CollectionPage page && loaded.meta().type().equalsIgnoreCase("list")?page.next()-page.rows().size():0;
        status.setText("db"+loaded.binding().db()+" · 已读取键");
    }
    private List<Node> header(Binding binding,KeyMeta meta) {
        Label key=new Label(RedisDisplaySupport.label(binding.key(),limits.labelChars()).text());
        Label type=new Label(meta.type().toUpperCase(Locale.ROOT));
        Button rename=new Button("重命名"); rename.setOnAction(e->promptValue("新键名",limits.singleKeyBytes(),binding,value->mutate(binding,s->{requireKey(value); s.rename(binding.key(),value);return null;},()->requestRefresh())));
        Button delete=new Button("删除"); delete.setOnAction(e->confirmDelete(binding));
        Button reload=new Button("刷新"); reload.setOnAction(e->{if(valid(binding)) loadKey(binding.key());});
        HBox keyBar=new HBox(6,key,type,rename,reload,delete); keyBar.setAlignment(Pos.CENTER_LEFT);
        TextField ttl=new TextField(meta.ttl()>=0?Long.toString(meta.ttl()):""); RedisConsolePane.limit(ttl,32); ttl.setPrefWidth(100); ttl.setPromptText(meta.ttl()==-1?"持久化":"秒");
        Button apply=new Button("设置 TTL"); apply.setOnAction(e->{ try {long seconds=Long.parseLong(ttl.getText().trim()); mutate(binding,s->s.expire(binding.key(),seconds),()->loadKey(binding.key()));} catch(NumberFormatException invalid){warn("TTL 必须是整数秒");} });
        Button persist=new Button("持久化"); persist.setOnAction(e->mutate(binding,s->s.persist(binding.key()),()->loadKey(binding.key())));
        return List.of(keyBar,new HBox(6,new Label("TTL:"),ttl,apply,persist),new Separator());
    }
    private List<Node> stringEditor(Binding binding,StringData data) {
        RedisDisplaySupport.StringViews views=data.views();
        TextArea editor=new TextArea(); editor.setId("redis-string-editor"); RedisConsolePane.limit(editor,limits.editorChars()); editor.setPrefRowCount(18);
        ComboBox<RedisDisplaySupport.Mode> mode=new ComboBox<>(FXCollections.observableArrayList(RedisDisplaySupport.Mode.values())); mode.setId("redis-string-mode");
        mode.setValue(views.printable()?RedisDisplaySupport.Mode.TEXT:RedisDisplaySupport.Mode.HEX);
        Button save=new Button("保存"); save.setId("redis-string-save"); Label preview=new Label();
        Runnable render=()-> {
            var selected=mode.getValue(); var value=views.view(selected); editor.setText(value.text()); editor.setEditable(views.editable(selected)); save.setDisable(!views.editable(selected));
            preview.setText(views.editable(selected)?"完整值":"预览，禁止保存（读取或显示未完整）");
        };
        mode.valueProperty().addListener((observable,old,value)->{try {if(valid(binding)) render.run();} catch(RuntimeException failure){warn(message(failure));}}); render.run();
        save.setOnAction(e->{ if(!valid(binding) || !views.editable(mode.getValue())) return; String value=editor.getText(); boolean hex=mode.getValue()==RedisDisplaySupport.Mode.HEX;
            mutate(binding,s->{s.set(binding.key(),RedisDisplaySupport.valueBytes(value,hex,"SET",binding.key()));return null;},()->loadKey(binding.key())); });
        HBox tools=new HBox(6,mode,save,new Label("大小: "+data.length()+" 字节"));
        if(!views.readComplete()) { Button full=new Button("加载完整值（超过 1 MiB）"); full.setId("redis-string-full"); full.setOnAction(e->loadPage(binding,"string",0,true)); tools.getChildren().add(full); }
        return List.of(tools,preview,editor);
    }
    private List<Node> collectionEditor(Binding binding,String type,RedisDisplaySupport.CollectionPage page) {
        TableView<RedisDisplaySupport.Row> table=table(type,page.rows()); HBox tools=new HBox(6);
        String key=binding.key();
        long offset=type.equalsIgnoreCase("list")?page.next()-page.rows().size():0;
        switch(type.toLowerCase(Locale.ROOT)) {
            case "hash" -> {
                tools.getChildren().addAll(button("新增",()->promptValue("Field",limits.editorChars(),binding,field->promptBytes("Value",binding,new Object[]{"HSET",key,field},value->mutate(binding,s->s.hset(key,RedisDisplaySupport.valueBytes(field,false,"HDEL",key),value),()->loadPage(binding,"hash",0,false))))),
                        button("修改值",()->{var row=table.getSelectionModel().getSelectedItem(); if(row!=null) promptBytes("Value",binding,new Object[]{"HSET",key,row.rawA()},value->updateRow(binding,"hash",row,value,0).run());}),
                        button("删除",()->{var row=table.getSelectionModel().getSelectedItem(); if(row!=null) mutate(binding,s->s.hdel(key,row.rawA()),()->loadPage(binding,"hash",0,false));}));
            }
            case "list" -> {
                tools.getChildren().addAll(button("头部插入",()->promptBytes("值",binding,new Object[]{"LPUSH",key},value->mutate(binding,s->s.lpush(key,value),()->loadPage(binding,"list",0,false)))),
                        button("尾部插入",()->promptBytes("值",binding,new Object[]{"RPUSH",key},value->mutate(binding,s->s.rpush(key,value),()->loadPage(binding,"list",offset,false)))),
                        button("修改",()->{var row=table.getSelectionModel().getSelectedItem(); if(row!=null) promptBytes("新值",binding,new Object[]{"LSET",key,row.index()},value->updateRow(binding,"list",row,value,offset).run());}),
                        button("按值删除",()->{var row=table.getSelectionModel().getSelectedItem(); if(row!=null) mutate(binding,s->s.lrem(key,1,row.rawB()),()->loadPage(binding,"list",offset,false));}));
                Button prev=button("上一页",()->loadPage(binding,"list",Math.max(0,offset-COLLECTION_PAGE),false)); prev.setDisable(offset==0); tools.getChildren().add(prev);
            }
            case "set" -> tools.getChildren().addAll(button("新增",()->promptBytes("Member",binding,new Object[]{"SADD",key},value->addMember(binding,"set",value,0).run())),
                    button("删除",()->{var row=table.getSelectionModel().getSelectedItem(); if(row!=null) mutate(binding,s->s.srem(key,row.rawA()),()->loadPage(binding,"set",0,false));}));
            case "zset" -> tools.getChildren().addAll(button("新增",()->promptBytes("Member",binding,new Object[]{"ZREM",key},member->promptValue("Score",128,binding,score->updateScore(binding,member,score)))),
                    button("修改分数",()->{var row=table.getSelectionModel().getSelectedItem(); if(row!=null) promptValue("Score",128,binding,score->updateScore(binding,row.rawA(),score));}),
                    button("删除",()->{var row=table.getSelectionModel().getSelectedItem(); if(row!=null) mutate(binding,s->s.zrem(key,row.rawA()),()->loadPage(binding,"zset",0,false));}));
            default -> throw new IllegalArgumentException("Unsupported Redis collection");
        }
        Button more=button(type.equalsIgnoreCase("list")?"下一页":"加载更多",()->loadPage(binding,type,page.next(),false)); more.setId("redis-value-more");
        more.setDisable(type.equalsIgnoreCase("list")?page.rows().isEmpty() || page.next()>=page.length():page.next()==0); tools.getChildren().add(more);
        return List.of(tools,table);
    }
    private static Button button(String text,Runnable action) { Button button=new Button(text); button.setOnAction(e->action.run()); return button; }
    private TableView<RedisDisplaySupport.Row> table(String type,List<RedisDisplaySupport.Row> rows) {
        TableView<RedisDisplaySupport.Row> table=new TableView<>(FXCollections.observableArrayList(rows)); table.setId("redis-values");
        TableColumn<RedisDisplaySupport.Row,String> a=new TableColumn<>(type.equalsIgnoreCase("hash")?"Field":type.equalsIgnoreCase("list")?"Index":"Member");
        a.setCellValueFactory(v->new ReadOnlyStringWrapper(v.getValue().a())); a.setPrefWidth(220); table.getColumns().add(a);
        if(!type.equalsIgnoreCase("set")) { TableColumn<RedisDisplaySupport.Row,String> b=new TableColumn<>(type.equalsIgnoreCase("zset")?"Score":"Value"); b.setCellValueFactory(v->new ReadOnlyStringWrapper(v.getValue().b())); b.setPrefWidth(360); table.getColumns().add(b); }
        table.setPrefHeight(430); return table;
    }
    private void updateScore(Binding binding,byte[] member,String score) {
        try { double number=Double.parseDouble(score); addMember(binding,"zset",member,number).run(); }
        catch(NumberFormatException invalid) { warn("Score 必须是数字"); }
    }
    Runnable updateRowAction(String type,RedisDisplaySupport.Row row,byte[] value) { return updateRow(displayedBinding,type,row,value,displayedPageOffset); }
    private Runnable updateRow(Binding binding,String type,RedisDisplaySupport.Row row,byte[] value,long offset) {
        return ()->mutate(binding,s->{switch(type) {
            case "hash" -> s.hset(binding.key(),row.rawA(),value);
            case "list" -> s.lset(binding.key(),row.index(),value);
            default -> throw new IllegalArgumentException("Unsupported Redis edit");
        } return null;},()->loadPage(binding,type,offset,false));
    }
    Runnable updateScoreAction(byte[] member,String score) { Binding captured=displayedBinding; return ()->updateScore(captured,member,score); }
    Runnable addMemberAction(String type,byte[] value,double score) { return addMember(displayedBinding,type,value,score); }
    private Runnable addMember(Binding binding,String type,byte[] value,double score) {
        return ()->mutate(binding,s->{switch(type) {
            case "set" -> s.sadd(binding.key(),value);
            case "list" -> s.rpush(binding.key(),value);
            case "zset" -> s.zadd(binding.key(),score,value);
            default -> throw new IllegalArgumentException("Unsupported Redis member");
        } return null;},()->loadPage(binding,type,0,false));
    }
    private void createKey() {
        Binding binding=sourceBinding(""); if(busy || !valid(binding)) return;
        ChoiceDialog<String> choice=new ChoiceDialog<>("String","String","Hash","List","Set","ZSet"); choice.setTitle("新建 Redis 键");
        choice.showAndWait().ifPresent(kind->{ if(!valid(binding)) return;
            Dialog<List<String>> dialog=new Dialog<>(); ButtonType ok=new ButtonType("确定",ButtonBar.ButtonData.OK_DONE); dialog.getDialogPane().getButtonTypes().addAll(ok,ButtonType.CANCEL);
            TextField key=new TextField(); TextArea value=new TextArea(); RedisConsolePane.limit(key,limits.singleKeyBytes()); RedisConsolePane.limit(value,limits.editorChars());
            dialog.getDialogPane().setContent(new VBox(6,new Label("键名"),key,new Label("初始值"),value)); dialog.setResultConverter(b->b==ok?List.of(key.getText(),value.getText()):null);
            dialog.showAndWait().ifPresent(pair->{ if(!valid(binding) || pair.getFirst().isBlank()) return; String name=pair.getFirst(),text=pair.get(1);
                mutate(binding,s->{requireKey(name); switch(kind) {
                    case "String" -> s.set(name,RedisDisplaySupport.valueBytes(text,false,"SET",name));
                    case "Hash" -> s.hset(name,new byte[]{'f','i','e','l','d'},RedisDisplaySupport.valueBytes(text,false,"HSET",name,"field"));
                    case "List" -> s.rpush(name,RedisDisplaySupport.valueBytes(text,false,"RPUSH",name));
                    case "Set" -> s.sadd(name,RedisDisplaySupport.valueBytes(text,false,"SADD",name));
                    case "ZSet" -> s.zadd(name,0,RedisDisplaySupport.valueBytes(text,false,"ZADD",name,"0"));
                    default -> throw new IllegalArgumentException("Unsupported Redis type");
                } return null;},this::requestRefresh);
            });
        });
    }
    private void requireKey(String key) { RedisDisplaySupport.requireKey(key,limits); }
    private void confirmDelete(Binding binding) {
        if(busy || !valid(binding)) return;
        Runnable action=deleteAction(binding); Alert alert=new Alert(Alert.AlertType.CONFIRMATION,"确定删除键？",ButtonType.YES,ButtonType.NO);
        alert.setHeaderText(RedisDisplaySupport.label(binding.key(),limits.labelChars()).text()); alert.showAndWait(); if(alert.getResult()==ButtonType.YES) action.run();
    }
    Runnable deleteAction(String key) { return deleteAction(sourceBinding(key)); }
    private Runnable deleteAction(Binding binding) { return ()->mutate(binding,s->s.del(binding.key()),this::requestRefresh); }
    private void promptValue(String label,int cap,Binding binding,Consumer<String> action) {
        if(!valid(binding) || busy) return;
        TextInputDialog dialog=new TextInputDialog(); RedisConsolePane.limit(dialog.getEditor(),cap); dialog.setTitle(label); dialog.setHeaderText(null);
        dialog.showAndWait().ifPresent(value->{ if(valid(binding) && !busy) action.accept(value); });
    }
    private void promptBytes(String label,Binding binding,Object[] prefix,Consumer<byte[]> action) {
        if(!valid(binding) || busy) return;
        Dialog<byte[]> dialog=new Dialog<>(); dialog.setTitle(label); ButtonType ok=new ButtonType("确定",ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok,ButtonType.CANCEL); ComboBox<String> mode=new ComboBox<>(FXCollections.observableArrayList("文本","十六进制")); mode.setValue("文本");
        TextArea editor=new TextArea(); RedisConsolePane.limit(editor,limits.editorChars()); editor.setPrefRowCount(5); dialog.getDialogPane().setContent(new VBox(6,mode,editor));
        dialog.setResultConverter(button->{ if(button!=ok || !valid(binding) || busy) return null; try {return RedisDisplaySupport.valueBytes(editor.getText(),"十六进制".equals(mode.getValue()),prefix);} catch(RuntimeException failure){warn(message(failure));return null;} });
        dialog.showAndWait().ifPresent(value->{ if(valid(binding) && !busy) action.accept(value); });
    }
    @FunctionalInterface private interface SessionOperation<T> { T call(RedisSession captured) throws Exception; }
    private <T> void mutate(Binding binding,SessionOperation<T> operation,Runnable after) {
        if(busy || !valid(binding)) return;
        submit(generation,()->operation.call(binding.session()),ignored->{pending=after;pendingRefresh=false;},ignored->{},"保存中...");
    }
    @FunctionalInterface private interface IoSupplier<T> { T get() throws Exception; }
    private <T> void submit(long gen,IoSupplier<T> operation,Consumer<T> install,Consumer<T> discard,String message) {
        if(closed || busy) return;
        busy=true; long owner=activeRequest=++nextRequest; status.setText(message); updateControls();
        try { io.submit(operation::get,value->{
            try { if(live(gen) && owner==activeRequest) install.accept(value); else discard.accept(value); }
            catch(RuntimeException failure) { discard.accept(value); if(live(gen)) failure(failure); }
            catch(Error failure) { discard.accept(value); throw failure; }
            finally { finish(owner); }
        },error->{ try {if(live(gen)) failure(error);} finally {finish(owner);} }); }
        catch(RuntimeException rejected) { try {failure(rejected);} finally {finish(owner);} }
        catch(Error error) { finish(owner); throw error; }
    }
    private void finish(long owner) {
        if(closed || owner!=activeRequest) return;
        busy=false; updateControls(); Runnable next=pending; pending=null; pendingRefresh=false; if(next!=null) next.run();
    }
    private void updateControls() {
        loadMore.setDisable(busy || !sourceCurrent());
        details.setDisable(busy || !sourceCurrent() || displayedKey==null || !displayedKey.equals(desiredKey));
    }
    private void failure(Throwable failure) {
        String source=snapshot==null?"未完整加载":"db"+snapshot.database()+" 原结果 · 未完整加载";
        status.setText(RedisDisplaySupport.label(source+" · 错误: "+message(failure),limits.labelChars()).text()); warn(message(failure));
    }
    private void warn(String message) { notifyError.accept(RedisDisplaySupport.label(message,limits.consoleChars()).text()); }
    private static void alert(String message) { Alert alert=new Alert(Alert.AlertType.ERROR,message,ButtonType.OK); alert.setHeaderText(null); alert.show(); }
    @Override public void close() { RedisPaneCloseSequence.close(this::stopIo,this::closeCurrentSession); }
    private void stopIo() { synchronized(sessionLock){closed=true;} io.close(); }
    private void closeCurrentSession() {
        RedisSession current,created;
        synchronized(sessionLock){current=session; created=opening; session=null; opening=null;}
        BestEffortCloseSequence.run(()->{if(current!=null) closeSession.accept(current);},()->{if(created!=null && created!=current) closeSession.accept(created);});
    }
    private record TreeEntry(String label,String key,RedisKeySnapshot source,RedisSession session) { @Override public String toString(){return label;} }
    private final class KeyCell extends TreeCell<TreeEntry> {
        @Override protected void updateItem(TreeEntry item,boolean empty) {
            super.updateItem(item,empty); if(empty || item==null){setText(null);setContextMenu(null);return;}
            setText(item.label()); if(item.key()==null){setContextMenu(null);return;}
            MenuItem copy=new MenuItem("复制键名"); copy.setOnAction(e->{ClipboardContent content=new ClipboardContent();content.putString(item.key());Clipboard.getSystemClipboard().setContent(content);});
            MenuItem rename=new MenuItem("重命名"); rename.setOnAction(e->{Binding bind=cellBinding(item); if(valid(bind)) promptValue("新键名",limits.singleKeyBytes(),bind,value->mutate(bind,s->{requireKey(value);s.rename(item.key(),value);return null;},RedisKeyBrowserPane.this::requestRefresh));});
            MenuItem delete=new MenuItem("删除"); delete.setOnAction(e->{Binding bind=cellBinding(item);if(valid(bind))confirmDelete(bind);});
            setContextMenu(new ContextMenu(copy,rename,delete));
        }
        private Binding cellBinding(TreeEntry item) { return new Binding(item.session(),item.source(),item.source().database(),generation,valueEpoch,item.key(),false); }
    }
    private static String message(Throwable failure) { return failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage(); }
}
