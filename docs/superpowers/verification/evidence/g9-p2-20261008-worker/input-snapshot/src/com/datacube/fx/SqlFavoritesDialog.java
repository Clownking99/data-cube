package com.datacube.fx;

import com.datacube.config.SqlFavorite;
import com.datacube.config.SqlFavoriteStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxTaskScope;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.*;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;

/** Explicit local library operations. Open returns saved text only, never a connection or execution. */
final class SqlFavoritesDialog implements AutoCloseable {
    interface Repository {
        SqlFavoriteStore.Snapshot load() throws Exception;
        void save(SqlFavorite value,SqlFavorite expected) throws Exception;
        void delete(SqlFavorite expected) throws Exception;
        SqlFavorite recover(UUID id,long now) throws Exception;
    }
    private record Entry(SqlFavorite value,boolean recovery) {
        @Override public String toString() { return (recovery ? "[恢复副本] " : "") + value.name() + (value.group().isBlank() ? "" : " · "+value.group()); }
    }
    private final Dialog<SqlFavorite> dialog=new Dialog<>();
    private final TextField filter=new TextField(),name=new TextField(),group=new TextField();
    private final TextArea sql=new TextArea();
    private final ListView<Entry> list=new ListView<>();
    private final Label status=new Label();
    private final Button save=new Button("保存收藏"),remove=new Button("删除收藏"),restore=new Button("从有效副本恢复"),open=new Button("离线打开"),create=new Button("新建收藏"),reload=new Button("重新读取"),discard=new Button("放弃编辑");
    private final Repository repository;
    private final FxTaskScope tasks;
    private List<Entry> entries=List.of();
    private Set<UUID> protectedIds=Set.of();
    private Entry selected;
    private Completed pendingRefresh;
    private boolean busy,closed,mutating,writable;
    private String initialSql;
    private Runnable removeFocusListener=() -> {};
    private final boolean seedTooLarge;
    BooleanSupplier confirmDiscard=() -> confirm("放弃尚未保存的收藏编辑？");
    Predicate<SqlFavorite> confirmDelete=value -> confirm("删除此收藏及其上一版本备份？此操作不会删除源 SQL 文件。");

    static Optional<SqlFavorite> show(Path directory,Window owner,FxTaskRunner runner,String initialSql) {
        try (var view=new SqlFavoritesDialog(local(directory),owner,runner,initialSql)) { return view.dialog.showAndWait(); }
    }
    static Repository local(Path directory) {
        return new Repository() {
            private SqlFavoriteStore open() throws java.io.IOException {
                java.nio.file.Files.createDirectories(directory.toAbsolutePath().normalize().getParent());
                return SqlFavoriteStore.open(directory);
            }
            @Override public SqlFavoriteStore.Snapshot load() throws Exception { try(var store=open()) { return store.snapshot(); } }
            @Override public void save(SqlFavorite value,SqlFavorite expected) throws Exception { try(var store=open()) { store.save(value,expected); } }
            @Override public void delete(SqlFavorite expected) throws Exception { try(var store=open()) { store.delete(expected); } }
            @Override public SqlFavorite recover(UUID id,long now) throws Exception { try(var store=open()) { return store.recover(id,now); } }
        };
    }
    SqlFavoritesDialog(Repository repository,Window owner,FxTaskRunner runner,String initialSql) {
        this.repository=repository; this.tasks=runner.scope();
        seedTooLarge=initialSql!=null && initialSql.length()>SqlFavoriteStore.MAX_SQL_BYTES;
        this.initialSql=initialSql==null || seedTooLarge ? "" : initialSql;
        dialog.setTitle("SQL 收藏"); dialog.setHeaderText(null); dialog.setResizable(true); dialog.setResultConverter(button -> null);
        if(owner!=null) { dialog.initOwner(owner); if(owner.getScene()!=null) dialog.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets()); }
        filter.setId("favorites-filter"); filter.setPromptText("按名称 / 分组查找"); limit(filter,256);
        name.setId("favorites-name"); name.setPromptText("名称（必填，最多 160 字符）"); limit(name,160);
        group.setId("favorites-group"); group.setPromptText("分组（可选，最多 80 字符）"); limit(group,80);
        sql.setId("favorites-sql"); sql.setPromptText("保存的 SQL 正文，不自动执行"); sql.setWrapText(true);
        sql.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length()<=SqlFavoriteStore.MAX_SQL_BYTES ? change : null));
        list.setId("favorites-list"); list.setPrefHeight(150); list.setMinHeight(80); list.setPlaceholder(new Label("没有匹配的收藏"));
        sql.setPrefRowCount(7); sql.setMinHeight(100);
        for (var pair:Map.of(save,"save",remove,"delete",restore,"recover",open,"open",create,"new",reload,"reload",discard,"discard").entrySet()) pair.getKey().setId("favorites-"+pair.getValue());
        FlowPane commands=new FlowPane(8,6,create,save,discard,remove,restore,open,reload);
        status.setId("favorites-status"); status.setWrapText(true); status.setMinHeight(Region.USE_PREF_SIZE);
        Label privacy=new Label("收藏在本机以明文保存，SQL 可能含敏感值；不保存连接密码或查询结果。最多 100 项，每项 SQL 256 KiB，总额度 16 MiB（含备份）。离线打开后须另行选择连接。损坏项保留原文件，恢复会另存新项。");
        privacy.setId("favorites-privacy"); privacy.setWrapText(true); privacy.setMinHeight(Region.USE_PREF_SIZE);
        VBox content=new VBox(8,filter,list,name,group,sql,commands,status,privacy); content.setPadding(new Insets(12));
        content.setPrefWidth(760); content.setMinWidth(0); content.setMinHeight(Region.USE_PREF_SIZE);
        list.setMinWidth(0); sql.setMinWidth(0); commands.setMinWidth(0);
        VBox.setVgrow(list,Priority.SOMETIMES); VBox.setVgrow(sql,Priority.ALWAYS);
        ScrollPane scroll=new ScrollPane(content);
        scroll.setId("favorites-scroll"); scroll.setFitToWidth(true); scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.setPrefViewportWidth(760); scroll.setPrefViewportHeight(650);
        dialog.getDialogPane().setContent(scroll); dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        dialog.setOnShowing(event -> {
            var scene=dialog.getDialogPane().getScene();
            javafx.beans.value.ChangeListener<javafx.scene.Node> listener=(obs,before,focused) -> reveal(scroll,content,focused);
            removeFocusListener.run(); scene.focusOwnerProperty().addListener(listener);
            removeFocusListener=() -> scene.focusOwnerProperty().removeListener(listener);
        });
        Runnable revealAfterLayout=() -> javafx.application.Platform.runLater(() -> {
            if(!closed && scroll.getScene()!=null) reveal(scroll,content,scroll.getScene().getFocusOwner());
        });
        scroll.viewportBoundsProperty().addListener((obs,before,after) -> {
            if(before.getWidth()!=after.getWidth() || before.getHeight()!=after.getHeight()) revealAfterLayout.run();
        });
        content.heightProperty().addListener((obs,before,after) -> revealAfterLayout.run());
        dialog.setOnCloseRequest(event -> { if(busy || dirty() && !confirmDiscard.getAsBoolean()) event.consume(); });
        dialog.setOnShown(event -> load(null)); dialog.setOnHidden(event -> close());
        filter.textProperty().addListener(ignored -> filtered());
        list.getSelectionModel().selectedItemProperty().addListener((obs,before,entry) -> { if(!mutating && pendingRefresh==null && !closed) edit(entry); });
        name.textProperty().addListener(ignored -> buttons()); group.textProperty().addListener(ignored -> buttons()); sql.textProperty().addListener(ignored -> buttons());
        create.setOnAction(event -> { if(!closed && !busy && pendingRefresh==null && (!dirty() || confirmDiscard.getAsBoolean())) { this.initialSql=""; edit(null); name.requestFocus(); } });
        discard.setOnAction(event -> { if(!closed && !busy && pendingRefresh==null && (!dirty() || confirmDiscard.getAsBoolean())) { this.initialSql=""; edit(selected); } });
        reload.setOnAction(event -> { if(!closed && !busy && (!dirty() || confirmDiscard.getAsBoolean())) { this.initialSql=""; load(null); } });
        save.setOnAction(event -> save());
        remove.setOnAction(event -> {
            if(closed || busy || pendingRefresh!=null || selected==null || selected.recovery() || dirty() || !confirmDelete.test(selected.value())) return;
            SqlFavorite expected=selected.value(); write(Outcome.DELETE,() -> { repository.delete(expected); return expected; });
        });
        restore.setOnAction(event -> {
            if(closed || busy || pendingRefresh!=null || selected==null || !selected.recovery()) return;
            UUID id=selected.value().id(); write(Outcome.RECOVER,() -> repository.recover(id,System.currentTimeMillis()));
        });
        open.setOnAction(event -> { if(!closed && !busy && pendingRefresh==null && !dirty() && selected!=null && !selected.recovery()) { dialog.setResult(selected.value()); dialog.close(); } });
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED,event -> {
            if(event.getCode()==KeyCode.F && event.isControlDown() && !event.isAltDown() && !event.isShiftDown() && !event.isMetaDown()) { if(!filter.isDisabled()) { filter.requestFocus(); filter.selectAll(); reveal(scroll,content,filter); } event.consume(); }
        });
        edit(null); buttons();
    }
    private void reveal(ScrollPane scroll,VBox content,javafx.scene.Node focused) {
        if(closed || focused==null) return;
        boolean within=false;
        for(var current=focused;current!=null;current=current.getParent()) {
            // Reveal the editor/list control, leaving its own scrolling and caret to its skin.
            if(current==sql || current==list) focused=current;
            if(current==content) {within=true;break;}
        }
        if(!within) return;
        scroll.layout();var viewport=scroll.lookup(".viewport");if(viewport==null) return;
        var bounds=focused.localToScene(focused.getBoundsInLocal());
        var visible=viewport.localToScene(viewport.getBoundsInLocal());
        double range=content.getHeight()-visible.getHeight();if(range<=0) return;
        double delta=bounds.getMinY()<visible.getMinY() ? bounds.getMinY()-visible.getMinY()
                : bounds.getMaxY()>visible.getMaxY() ? bounds.getMaxY()-visible.getMaxY() : 0;
        scroll.setVvalue(Math.max(0,Math.min(1,scroll.getVvalue()+delta/range)));
    }
    Dialog<SqlFavorite> dialog() { return dialog; }
    private static void limit(TextField field,int max) { field.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length()<=max ? change : null)); }
    private boolean confirm(String text) {
        var alert=new Alert(Alert.AlertType.CONFIRMATION,text,ButtonType.OK,ButtonType.CANCEL);
        alert.initOwner(dialog.getDialogPane().getScene().getWindow()); alert.setHeaderText(null);
        return alert.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK;
    }
    private boolean dirty() {
        if(mutating || pendingRefresh!=null) return false;
        return selected==null ? !name.getText().isEmpty() || !group.getText().isEmpty() || !sql.getText().isEmpty()
                : !name.getText().equals(selected.value().name()) || !group.getText().equals(selected.value().group()) || !sql.getText().equals(selected.value().sql());
    }
    private void buttons() {
        if(mutating) return;
        boolean unavailable=busy || closed || pendingRefresh!=null; boolean recovery=selected!=null && selected.recovery();
        boolean protectedEntry=selected!=null && protectedIds.contains(selected.value().id());
        list.setDisable(unavailable || dirty()); filter.setDisable(unavailable || dirty());
        name.setDisable(unavailable || recovery); group.setDisable(unavailable || recovery); sql.setDisable(unavailable || recovery);
        save.setDisable(unavailable || !writable || recovery || protectedEntry || !dirty() || name.getText().isBlank() || sql.getText().isBlank());
        remove.setDisable(unavailable || recovery || protectedEntry || selected==null || dirty()); restore.setDisable(unavailable || !writable || !recovery);
        open.setDisable(unavailable || selected==null || recovery || dirty()); create.setDisable(unavailable); reload.setDisable(busy || closed); discard.setDisable(unavailable || !dirty());
    }
    private void edit(Entry entry) {
        mutating=true;
        try { selected=entry; name.setText(entry==null ? "" : entry.value().name()); group.setText(entry==null ? "" : entry.value().group()); sql.setText(entry==null ? initialSql : entry.value().sql()); if(entry==null) list.getSelectionModel().clearSelection(); }
        finally { mutating=false; } buttons();
    }
    private void filtered() {
        if(mutating || closed || pendingRefresh!=null) return;
        String term=filter.getText().strip().toLowerCase(Locale.ROOT); Entry previous=selected;
        mutating=true;
        try { list.getItems().setAll(entries.stream().filter(e -> (e.value().name()+"\n"+e.value().group()).toLowerCase(Locale.ROOT).contains(term)).toList());
            if(previous!=null && list.getItems().contains(previous)) list.getSelectionModel().select(previous); else list.getSelectionModel().clearSelection(); }
        finally { mutating=false; }
        edit(list.getSelectionModel().getSelectedItem());
    }
    private void load(UUID select) { UUID requested=pendingRefresh==null ? select : pendingRefresh.select(); operate(repository::load,snapshot -> loaded(snapshot,requested)); }
    private void loaded(SqlFavoriteStore.Snapshot snapshot,UUID select) {
        pendingRefresh=null; writable=snapshot.writable(); protectedIds=new HashSet<>(snapshot.recoverable().stream().map(SqlFavorite::id).toList());
        var all=new ArrayList<Entry>(); snapshot.favorites().forEach(v -> all.add(new Entry(v,false))); snapshot.recoverable().forEach(v -> all.add(new Entry(v,true))); entries=List.copyOf(all);
        selected=null; filtered();
        if(select!=null) { initialSql=""; entries.stream().filter(e -> !e.recovery() && e.value().id().equals(select)).findFirst().ifPresent(e -> { filter.clear(); list.getSelectionModel().select(e); edit(e); }); }
        status.setText((seedTooLarge ? "当前脚本超过收藏上限，未带入；请缩小文本。 " : "")+"已读取 " + snapshot.favorites().size()+" 项收藏"+(snapshot.protectedCount()>0 ? " · "+snapshot.protectedCount()+" 项文件受保护，可用副本单列，原文件未覆盖" : "")+(!writable ? " · 无法确认文件额度，暂不可保存" : ""));
        buttons();
    }
    private void save() {
        if(closed || pendingRefresh!=null || save.isDisabled()) return;
        SqlFavorite expected=selected==null ? null : selected.value();
        SqlFavorite value=new SqlFavorite(expected==null ? UUID.randomUUID() : expected.id(),name.getText().strip(),group.getText().strip(),sql.getText(),System.currentTimeMillis());
        write(Outcome.SAVE,() -> { repository.save(value,expected); return value; });
    }
    private void write(Outcome outcome,Callable<SqlFavorite> operation) {
        if(closed || busy || pendingRefresh!=null) return;
        operate(() -> {
            // Only a normally returned write (including repository resource close) is confirmed.
            Completed completed=new Completed(outcome,operation.call());
            SqlFavoriteStore.Snapshot snapshot;
            try { snapshot=repository.load(); }
            catch(Exception unreadable) { if(unreadable instanceof InterruptedException) Thread.currentThread().interrupt(); snapshot=null; }
            return new Refreshed(completed,snapshot);
        },result -> {
            pendingRefresh=result.completed(); initialSql=""; entries=List.of(); protectedIds=Set.of(); writable=false;
            mutating=true; try { list.getItems().clear(); } finally { mutating=false; }
            edit(new Entry(pendingRefresh.value(),false));
            if(result.snapshot()!=null) loaded(result.snapshot(),pendingRefresh.select());
            else status.setText(pendingRefresh.notice());
        });
    }
    private <T> void operate(Callable<T> operation,Consumer<T> success) {
        if(busy || closed) return; busy=true; status.setText("正在处理本地收藏，请等待完成…"); buttons();
        try { tasks.submit(operation,result -> { busy=false; success.accept(result); buttons(); },failure -> {
            busy=false; status.setText(pendingRefresh!=null ? pendingRefresh.notice() : failure instanceof SqlFavoriteStore.Failure f ? switch(f.code()) {
                case CAPACITY -> "收藏额度不足，未驱逐旧项；请先明确删除不需要的收藏。";
                case CHANGED -> "收藏已变化，未覆盖；请重新读取后核对。";
                case PROTECTED -> "文件损坏或版本未知，已保留原文件；可从有效副本恢复为新项。";
                case INVALID -> "收藏内容无效或超过 UTF-8 字节额度，尚未保存。";
                default -> "收藏暂不可用，原内容未主动清理，请重试。";
            } : "收藏读取或写入未完成，请重新读取核对；不会自动清理旧内容。"); buttons();
        }); } catch(java.util.concurrent.RejectedExecutionException rejected) { busy=false; status.setText(pendingRefresh==null ? "收藏任务暂不可用。" : pendingRefresh.notice()); buttons(); }
    }
    private enum Outcome { SAVE, DELETE, RECOVER }
    private record Completed(Outcome outcome,SqlFavorite value) {
        UUID select() { return outcome==Outcome.DELETE ? null : value.id(); }
        String notice() {
            return switch(outcome) {
                case SAVE -> "收藏已保存";
                case DELETE -> "收藏已删除";
                case RECOVER -> "已从副本恢复为新收藏";
            } + "，但列表读取未完成；已完成的操作不会重试。请点击重新读取后核对。";
        }
    }
    private record Refreshed(Completed completed,SqlFavoriteStore.Snapshot snapshot) {}
    @Override public void close() { if(closed) return; closed=true; removeFocusListener.run(); tasks.close(); entries=List.of(); selected=null; pendingRefresh=null; initialSql=""; mutating=true; try { list.getItems().clear(); sql.clear(); name.clear(); group.clear(); } finally { mutating=false; } buttons(); }
}
