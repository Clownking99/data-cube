package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.SchemaMetadataSearch;
import com.datacube.service.SchemaMetadataSearch.*;
import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.TableInfo;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.util.Duration;

/** Explicit remote metadata search. Typing never submits, and each result is bound to its request. */
final class SchemaMetadataSearchDialog implements AutoCloseable {
    enum Action { SELECT, DATA, DDL }
    record Selection(Hit hit, Action action) {}
    @FunctionalInterface interface Loader { Result load(Request request, SqlExecutionControl control) throws Exception; }
    private final Dialog<Selection> dialog = new Dialog<>();
    private final TextField query = new TextField();
    private final ChoiceBox<Mode> mode = new ChoiceBox<>();
    private final ListView<Hit> list = new ListView<>();
    private final TextArea preview = new TextArea();
    private final Label status = new Label("选择匹配来源并输入文字，再点击查找。对象名检索请使用 Schema 菜单中的“查找表/视图”。");
    private final Button search = new Button("查找"), cancelRead = new Button("取消读取");
    private final List<Button> actions = new ArrayList<>();
    private final ConnConfig target;
    private final String schema;
    private final FxTaskRunner runner;
    private final Loader loader;
    private final BooleanSupplier allowed;
    private final AtomicBoolean closed = new AtomicBoolean(), cancelling = new AtomicBoolean();
    private final PauseTransition deadline = new PauseTransition(Duration.seconds(SchemaMetadataSearch.TIMEOUT_SECONDS));
    private volatile Pending active;
    private Request published;

    SchemaMetadataSearchDialog(ConnConfig target, String schema, Window owner, FxTaskRunner runner, Loader loader, BooleanSupplier allowed) {
        this.target=target; this.schema=schema; this.runner=runner; this.loader=loader; this.allowed=allowed;
        dialog.setTitle("字段 / 注释查找"); dialog.setHeaderText(null); dialog.setResizable(true);
        if (owner != null) {
            dialog.initOwner(owner);
            if (owner.getScene()!=null) dialog.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        Label identity = new Label("连接：" + target.name() + "\nSchema：" + schema); identity.setWrapText(true);
        identity.setMinHeight(Region.USE_PREF_SIZE); identity.setId("metadata-search-target");
        query.setId("metadata-search-query"); query.setPromptText("文字包含匹配；% 与 _ 是普通字符（最多 256 字符）");
        query.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= 256 ? change : null));
        mode.setId("metadata-search-mode"); mode.getItems().setAll(Mode.values()); mode.setValue(Mode.COLUMN_NAME);
        mode.setAccessibleText("匹配来源：字段名、对象注释、字段注释");
        search.setId("metadata-search-submit"); search.setOnAction(event -> submit());
        cancelRead.setId("metadata-search-cancel-read"); cancelRead.setOnAction(event -> abandon(
                "已请求取消；等待驱动释放读取资源。", "读取已结束，未采用已取消请求的结果；可重新查找。"));
        FlowPane controls = new FlowPane(8,6,new Label("匹配来源："),mode,search,cancelRead);
        list.setId("metadata-search-results"); list.setPrefHeight(200);
        list.setPlaceholder(new Label("未读取匹配；不会扫描其他 Schema 或连接"));
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(Hit item, boolean empty) {
                super.updateItem(item,empty);
                setText(empty || item==null ? null : compact(item.object().name()) + " · " + item.mode()
                        + (item.column()==null ? "" : " · " + compact(item.column())));
            }
        });
        preview.setId("metadata-search-preview"); preview.setEditable(false); preview.setWrapText(true); preview.setPrefRowCount(4);
        preview.setMinHeight(Region.USE_PREF_SIZE); status.setId("metadata-search-status"); status.setWrapText(true); status.setMinHeight(Region.USE_PREF_SIZE);
        FlowPane commands = new FlowPane(8,6);
        for (Action action : Action.values()) {
            Button button = new Button(switch(action) { case SELECT -> "生成 SELECT（不执行）"; case DATA -> "查看数据（只读）"; case DDL -> "查看 DDL"; });
            button.setId("metadata-search-" + action.name().toLowerCase(Locale.ROOT));
            button.setOnAction(event -> { if (candidateAllowed()) { dialog.setResult(new Selection(list.getSelectionModel().getSelectedItem(),action)); dialog.close(); } });
            actions.add(button); commands.getChildren().add(button);
        }
        VBox content = new VBox(8,identity,query,controls,list,preview,status,commands);
        content.setPadding(new Insets(12)); content.setPrefSize(660,560); VBox.setVgrow(list,Priority.ALWAYS);
        dialog.getDialogPane().setContent(content); dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        dialog.setResultConverter(button -> null);
        dialog.setOnHidden(event -> close()); dialog.setOnShown(event -> query.requestFocus());
        query.textProperty().addListener(ignored -> changed()); mode.valueProperty().addListener(ignored -> changed());
        list.getSelectionModel().selectedItemProperty().addListener((obs,before,hit) -> {
            preview.setText(hit==null ? "" : "Schema：" + hit.object().schema() + "\n对象：" + hit.object().name()
                    + "\n类型：" + (hit.object().kind()==TableInfo.Kind.VIEW ? "视图" : "表")
                    + "\n匹配来源：" + hit.mode() + (hit.column()==null ? "" : " · " + hit.column()) + "\n匹配值预览：" + hit.excerpt());
            buttons();
        });
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED,event -> {
            if (event.getCode()==KeyCode.F && event.isControlDown() && !event.isAltDown() && !event.isShiftDown() && !event.isMetaDown()) {
                query.requestFocus(); query.selectAll(); event.consume();
            }
        });
        // Enter in the query explicitly searches; it never confirms a database action.
        query.setOnAction(event -> { submit(); event.consume(); }); buttons();
    }
    Dialog<Selection> dialog() { return dialog; }
    Optional<Selection> showAndWait() { return dialog.showAndWait(); }
    private static String compact(String value) { String text=value.replaceAll("[\\r\\n\\t]"," "); return text.length()>96 ? text.substring(0,96)+"…" : text; }
    private boolean usable() { return !closed.get() && allowed.getAsBoolean() && !dialog.getDialogPane().isDisabled(); }
    private boolean candidateAllowed() {
        Hit hit=list.getSelectionModel().getSelectedItem();
        return usable() && published!=null && published.mode()==mode.getValue() && published.term().equals(query.getText().strip())
                && hit!=null && hit.mode()==published.mode() && schema.equals(hit.object().schema()) && list.getItems().contains(hit);
    }
    private void buttons() {
        search.setDisable(!usable() || active!=null || cancelling.get() || query.getText().isBlank() || mode.getValue()==null);
        cancelRead.setDisable(!usable() || active==null || active.abandoned);
        for (Button action:actions) action.setDisable(!candidateAllowed());
    }
    private void changed() {
        published=null; list.getItems().clear(); preview.clear();
        if (active!=null) abandon("检索条件已变化，旧结果已失效；等待旧读取结束。",
                "旧读取已结束，旧结果已失效；请按当前条件重新查找。");
        else status.setText("检索条件已变化，请点击查找。");
        buttons();
    }
    private void submit() {
        if (!usable() || active!=null || cancelling.get() || query.getText().isBlank() || mode.getValue()==null) return;
        Request request=new Request(target,schema,mode.getValue(),query.getText().strip());
        Pending pending=new Pending(request); active=pending; published=null; list.getItems().clear(); preview.clear();
        if (closed.get()) { pending.control.requestCancellation(); active=null; return; }
        status.setText("正在读取当前 Schema 的" + request.mode() + "匹配…"); buttons();
        deadline.setOnFinished(event -> { if (active==pending) abandon(
                "读取超时；等待驱动释放资源后可重试。", "读取已结束，未采用超时请求的结果；可重新查找。"); }); deadline.playFromStart();
        try { runner.submit(() -> {
            Result result=null; Exception failure=null;
            try { if (!pending.control.cancellationRequested()) result=loader.load(request,pending.control); }
            catch (Exception error) { failure=error; }
            Result completed=result; Exception error=failure;
            Platform.runLater(() -> finish(pending,completed,error));
        }); } catch (java.util.concurrent.RejectedExecutionException rejected) { finish(pending,null,rejected); }
    }
    private void finish(Pending pending,Result result,Exception failure) {
        if (active!=pending) return;
        pending.readFinished=true; deadline.stop();
        if (pending.abandoned) { settleAbandoned(pending); buttons(); return; }
        active=null;
        if (!usable()) { buttons(); return; }
        if (failure!=null || result==null) status.setText("读取失败或权限不足；未建立结果，请核对目标后重试。");
        else { published=pending.request; list.getItems().setAll(result.hits()); status.setText(result.notice()); }
        buttons();
    }
    private void abandon(String message,String settledNotice) {
        Pending pending=active; if (pending==null || pending.abandoned) return;
        pending.abandoned=true; pending.settledNotice=settledNotice;
        deadline.stop(); pending.control.requestCancellation(); status.setText(message); cancelDriver(pending); buttons();
    }
    private void settleAbandoned(Pending pending) {
        // FX-thread only: retain ownership until both the read and JDBC cancel have returned.
        if (active!=pending || !pending.abandoned || !pending.readFinished || cancelling.get()) return;
        active=null;
        if (usable()) status.setText(pending.settledNotice);
    }
    private void cancelDriver(Pending pending) {
        if (!cancelling.compareAndSet(false,true)) return;
        try { runner.submit(() -> {
            try { pending.control.cancel(); } catch (Exception ignored) { }
            finally { cancelling.set(false); Platform.runLater(() -> { settleAbandoned(pending); buttons(); }); }
        }); } catch (java.util.concurrent.RejectedExecutionException ignored) { cancelling.set(false); }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false,true)) return;
        Pending pending=active; if (pending!=null) { pending.control.requestCancellation(); cancelDriver(pending); }
        Runnable cleanup=() -> { deadline.stop(); published=null; list.getItems().clear(); preview.clear(); buttons(); dialog.close(); };
        if (Platform.isFxApplicationThread()) cleanup.run(); else Platform.runLater(cleanup);
    }
    private static final class Pending {
        final Request request; final SqlExecutionControl control=new SqlExecutionControl();
        boolean abandoned, readFinished; String settledNotice;
        Pending(Request request) { this.request=request; }
    }
}
