package com.datacube.fx;

import com.datacube.redis.RedisConsoleSupport;
import com.datacube.redis.RedisDisplayLimits;
import com.datacube.redis.RedisDisplaySupport;
import com.datacube.redis.RedisSession;
import com.datacube.redis.RedisTextRetention;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.model.ConnConfig;
import com.datacube.fx.task.FxSerialTaskQueue;
import com.datacube.fx.task.FxTaskRunner;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Single-flight console with bounded text retention and worker-side formatting. */
public final class RedisConsolePane implements AutoCloseable {
    private final ConnConfig config;
    private final RedisSession session;
    private final Consumer<RedisSession> closeSession;
    private final FxSerialTaskQueue io;
    private final RedisDisplayLimits limits;
    private final Runnable beforeInstall;
    private final VBox root=new VBox(8),output=new VBox(3);
    private final TextField input=new TextField();
    private final RedisTextRetention outputText,history;
    private int historyIndex;
    private boolean busy;
    private volatile boolean closed;
    private long request;

    public RedisConsolePane(ConnectionManager manager,ConnConfig config,FxTaskRunner runner) {
        this(config,db->manager.openRedisSession(config.id(),db),manager::closeRedisSession,runner,RedisDisplayLimits.DEFAULT,()->{});
    }
    RedisConsolePane(ConnConfig config,IntFunction<RedisSession> open,Consumer<RedisSession> close,
                     FxTaskRunner runner,RedisDisplayLimits limits,Runnable beforeInstall) {
        this.config=config; this.closeSession=close; this.limits=limits; this.beforeInstall=beforeInstall;
        outputText=new RedisTextRetention(limits.outputEntries(),limits.outputChars());
        history=new RedisTextRetention(limits.historyEntries(),limits.historyChars());
        ConstructionOwner construction = new ConstructionOwner();
        try {
            this.session=open.apply(parseDatabase(config.database()));
            construction.ownBlocking(()->closeSession.accept(session));
            this.io=new FxSerialTaskQueue(runner); construction.own(io::close);
            build(parseDatabase(config.database())); construction.commit();
        } catch(Throwable failure) { throw construction.close(failure).failure(); }
    }
    public Node getNode() { return root; }
    private void build(int db) {
        root.setPadding(new Insets(10));
        Label title=new Label(RedisDisplaySupport.label("Redis 控制台 · "+config.name()+" · db"+db,limits.labelChars()).text());
        title.setStyle("-fx-font-weight: bold;");
        ScrollPane scroll=new ScrollPane(output); scroll.setFitToWidth(true); output.setPadding(new Insets(8));
        VBox.setVgrow(scroll,Priority.ALWAYS); output.setId("redis-console-output"); input.setId("redis-console-input");
        limit(input,limits.consoleChars());
        input.setPromptText("输入 Redis 命令，支持单/双引号；Enter 执行，↑/↓ 浏览历史");
        input.setOnAction(e->execute());
        input.setOnKeyPressed(e->{ if(e.getCode()==KeyCode.UP || e.getCode()==KeyCode.DOWN) { showHistory(e.getCode()==KeyCode.UP?-1:1); e.consume(); } });
        root.getChildren().addAll(title,scroll,input); append("已连接，输入 PING 开始。",false);
    }
    static void limit(TextInputControl control,int cap) {
        control.setTextFormatter(new TextFormatter<String>(change-> {
            long size=(long)control.getLength()-(change.getRangeEnd()-change.getRangeStart())+change.getText().length();
            return size<=cap ? change : null; // Never build getControlNewText before admission.
        }));
    }
    private void execute() {
        if(closed || busy) return;
        String line=input.getText(); final List<String> args;
        try { args=RedisConsoleSupport.tokenize(line,limits); }
        catch(IllegalArgumentException error) { append(message(error),true); return; }
        if(args.isEmpty()) return;
        var policy=RedisConsoleSupport.policy(args);
        if(policy==RedisConsoleSupport.CommandPolicy.BLOCKED) { append("一期不支持可能长期阻塞连接的命令",true); return; }
        if(policy==RedisConsoleSupport.CommandPolicy.CONFIRM) {
            Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,"该命令可能造成数据丢失或服务中断，确定执行？",ButtonType.YES,ButtonType.NO);
            confirm.setHeaderText(RedisDisplaySupport.label(line,limits.labelChars()).text()); confirm.showAndWait();
            if(closed || busy || confirm.getResult()!=ButtonType.YES) return;
        }
        history.add(line); historyIndex=history.size(); input.clear(); busy=true; input.setDisable(true);
        long owner=++request;
        try {
            append("> "+line,false);
            io.submit(()->RedisConsoleSupport.format(session.raw(args.toArray(String[]::new)),limits),
                    response->complete(owner,()->{beforeInstall.run(); append(response.text(),false);}),
                    failure->complete(owner,()->append(message(failure),true)));
        } catch(RuntimeException failure) { complete(owner,()->append(message(failure),true)); }
        catch(Error failure) { settle(owner); throw failure; }
    }
    private void complete(long owner,Runnable install) {
        if(closed || owner!=request) return;
        try { install.run(); }
        catch(RuntimeException failure) { append(message(failure),true); }
        finally { settle(owner); }
    }
    private void settle(long owner) {
        if(closed || owner!=request) return;
        busy=false; input.setDisable(false); input.requestFocus();
    }
    private void showHistory(int delta) {
        List<String> values=history.values(); if(values.isEmpty() || busy || closed) return;
        historyIndex=Math.max(0,Math.min(values.size(),historyIndex+delta));
        input.setText(historyIndex==values.size()?"":values.get(historyIndex)); input.positionCaret(input.getLength());
    }
    private void append(String text,boolean error) {
        String bounded=RedisDisplaySupport.label(text,Math.min(limits.consoleChars(),limits.outputChars())).text();
        Label line=new Label(bounded); line.setWrapText(true);
        line.setStyle(error?"-fx-text-fill: -status-error; -fx-font-family: Consolas, monospace;":"-fx-font-family: Consolas, monospace;");
        int removed=outputText.add(bounded);
        if(removed>0) output.getChildren().remove(0,removed);
        output.getChildren().add(line);
    }
    @Override public void close() {
        closed=true;
        RedisPaneCloseSequence.close(io::close,()->closeSession.accept(session));
    }
    private static int parseDatabase(String value) { try {return value==null || value.isBlank()?0:Integer.parseInt(value);} catch(NumberFormatException ignored){return 0;} }
    private static String message(Throwable error) { return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage(); }
}
