package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.SchemaObjectCatalog;
import com.datacube.spi.model.TableInfo;
import com.datacube.spi.model.TableRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Remote name snapshot followed by bounded local filtering; confirmation never executes SQL. */
final class SchemaObjectSearchDialog implements AutoCloseable {
    @FunctionalInterface interface Submitter {
        Future<?> submit(Callable<List<TableInfo>> work, Consumer<List<TableInfo>> success, Consumer<Throwable> failure);
    }
    private record Entry(TableInfo value, String foldedName) { }
    private enum KindFilter {
        ALL("全部", null), TABLE("仅表", TableInfo.Kind.TABLE), VIEW("仅视图", TableInfo.Kind.VIEW);
        private final String label;
        private final TableInfo.Kind kind;
        KindFilter(String label, TableInfo.Kind kind) { this.label = label; this.kind = kind; }
        boolean accepts(TableInfo value) { return kind == null || value.kind() == kind; }
        @Override public String toString() { return label; }
    }
    private final Dialog<TableRef> dialog = new Dialog<>();
    private final TextField query = new TextField();
    private final ChoiceBox<KindFilter> kindFilter = new ChoiceBox<>();
    private final ListView<TableInfo> list = new ListView<>();
    private final TextArea preview = new TextArea();
    private final Label status = new Label();
    private final Label placeholder = new Label();
    private final Button retry = new Button("重新读取");
    private final Button copy = new Button("复制限定名称");
    private final Label copyStatus = new Label();
    private Function<TableRef, ConnectionTreeClipboard.CopyResult> copyAction;
    private final Button confirm;
    private final String schema;
    private final Callable<List<TableInfo>> loader;
    private final Submitter submitter;
    private final Runnable closeScope;
    private final BooleanSupplier allowed;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Future<?> active;
    private List<Entry> all = List.of();
    private long revision;
    private boolean loaded;
    private boolean queryRejected;

    static SchemaObjectSearchDialog create(String connectionName, String schema, Window owner,
                                           Callable<List<TableInfo>> loader, FxTaskRunner runner, BooleanSupplier allowed) {
        var scope = runner.scope();
        try {
            return new SchemaObjectSearchDialog(connectionName, schema, owner, loader, scope::submit, scope::close, allowed);
        } catch (Throwable failure) { scope.close(); throw failure; }
    }

    SchemaObjectSearchDialog(String connectionName, String schema, Window owner, Callable<List<TableInfo>> loader,
                             Submitter submitter, Runnable closeScope, BooleanSupplier allowed) {
        this.schema = schema; this.loader = loader; this.submitter = submitter;
        this.closeScope = closeScope; this.allowed = allowed;
        dialog.setTitle("查找表/视图"); dialog.setHeaderText(null); dialog.setResizable(true);
        if (owner != null) {
            dialog.initOwner(owner);
            if (owner.getScene() != null) dialog.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        TextArea target = new TextArea("连接：" + connectionName + "\nSchema：" + schema);
        target.setId("schema-object-target"); target.setWrapText(true); target.setEditable(false);
        target.setMinWidth(0); target.setPrefRowCount(2); target.setAccessibleText("固定的连接和 Schema，只读");
        target.setMinHeight(Region.USE_PREF_SIZE);
        query.setId("schema-object-query"); query.setMinWidth(0);
        query.setPromptText("按对象名称筛选（最多 256 字符）"); query.setAccessibleText("当前 Schema 表和视图名称筛选");
        query.setTextFormatter(new TextFormatter<String>(change -> {
            if (change.getControlNewText().length() <= 256) {
                if (change.isContentChange()) queryRejected = false;
                return change;
            }
            queryRejected = true;
            status.setText("筛选词最多 256 个字符，超长输入未应用"); return null;
        }));
        kindFilter.setId("schema-object-kind"); kindFilter.getItems().setAll(KindFilter.values());
        kindFilter.setValue(KindFilter.ALL); kindFilter.setMinWidth(Region.USE_PREF_SIZE);
        kindFilter.setAccessibleText("对象类型筛选，仅筛选已读取的名称");
        Label kindLabel = new Label("类型："); kindLabel.setLabelFor(kindFilter);
        kindLabel.setMinWidth(Region.USE_PREF_SIZE);
        Button clear = new Button("清除筛选"); clear.setId("schema-object-clear");
        clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.disableProperty().bind(query.textProperty().isEmpty().and(kindFilter.valueProperty().isEqualTo(KindFilter.ALL)));
        clear.setOnAction(event -> {
            queryRejected = false; query.clear(); kindFilter.setValue(KindFilter.ALL); filter(); query.requestFocus();
        });
        HBox search = new HBox(8, query, clear); HBox.setHgrow(query, Priority.ALWAYS);
        status.setId("schema-object-status"); status.setWrapText(true); status.setMinWidth(0); status.setMinHeight(Region.USE_PREF_SIZE);
        HBox filters = new HBox(8, kindLabel, kindFilter, status); filters.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(status, Priority.ALWAYS); filters.setMinHeight(Region.USE_PREF_SIZE);
        placeholder.setWrapText(true);
        list.setId("schema-object-list"); list.setMinWidth(0); list.setPrefHeight(200); list.setPlaceholder(placeholder);
        list.setAccessibleText("表和视图候选，选择后按 Enter 生成未执行的 SELECT");
        list.setCellFactory(view -> new ListCell<>() {
            private final Label name = new Label();
            { name.setMinWidth(0); name.prefWidthProperty().bind(view.widthProperty().subtract(40)); }
            @Override protected void updateItem(TableInfo item, boolean empty) {
                super.updateItem(item, empty); setText(null);
                if (empty || item == null) { setGraphic(null); setAccessibleText(null); return; }
                String text = item.name().replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
                name.setText(kind(item) + "  " + (text.length() <= 128 ? text : text.substring(0, 128) + "…"));
                setAccessibleText(kind(item) + " " + item.name()); setGraphic(name);
            }
        });
        preview.setId("schema-object-preview"); preview.setEditable(false); preview.setWrapText(true);
        preview.setPrefRowCount(3); preview.setMinWidth(0); preview.setPromptText("选择候选后核对完整 Schema、名称和类型");
        preview.setMinHeight(Region.USE_PREF_SIZE);
        preview.setAccessibleText("所选对象完整身份，只读");
        retry.setId("schema-object-reload"); retry.setOnAction(event -> reload());
        copy.setId("schema-object-copy"); copy.setDefaultButton(false); copy.setOnAction(event -> copySelected());
        copy.setTooltip(new Tooltip("复制到系统剪贴板；其他应用或系统剪贴板同步功能可能读取名称。"));
        copyStatus.setId("schema-object-copy-status"); copyStatus.setWrapText(true); copyStatus.setMinWidth(0);
        copyStatus.setMinHeight(Region.USE_PREF_SIZE); clearCopyStatus();
        FlowPane tools = new FlowPane(8, 6, retry, copy); tools.setMinWidth(0);
        Label hint = new Label("读取仅限此 Schema 的名称和类型，筛选不再请求数据库。\n复制写入系统剪贴板，不自动粘贴；粘贴前请核对目标连接。\n确认生成 SELECT 脚本，不自动执行。↓ 选择 · Enter 确认 · Ctrl+F 筛选 · Esc 取消");
        hint.setWrapText(true); hint.setMinHeight(Region.USE_PREF_SIZE);
        VBox content = new VBox(8, target, search, filters, list, preview, tools, copyStatus, hint);
        content.setPadding(new Insets(12)); content.setPrefSize(640, 500); content.setMinWidth(0);
        VBox.setVgrow(list, Priority.ALWAYS); dialog.getDialogPane().setContent(content);
        ButtonType selectType = new ButtonType("生成 SELECT（不执行）", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(selectType, cancelType);
        confirm = (Button) dialog.getDialogPane().lookupButton(selectType); confirm.setId("schema-object-confirm");
        confirm.setDefaultButton(false);
        Button cancel = (Button) dialog.getDialogPane().lookupButton(cancelType); cancel.setId("schema-object-cancel");
        confirm.addEventFilter(ActionEvent.ACTION, event -> { if (!candidateAllowed()) event.consume(); });
        dialog.setResultConverter(button -> button == selectType && candidateAllowed()
                ? list.getSelectionModel().getSelectedItem().ref() : null);
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            clearCopyStatus();
            preview.setText(selected == null ? "" : "Schema：" + selected.schema() + "\n名称：" + selected.name() + "\n类型：" + kind(selected));
            updateConfirm();
        });
        query.textProperty().addListener(ignored -> filter());
        kindFilter.valueProperty().addListener(ignored -> filter());
        query.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (modified(event)) return;
            if (event.getCode() == KeyCode.DOWN) {
                if (!list.getItems().isEmpty()) {
                    if (list.getSelectionModel().isEmpty()) list.getSelectionModel().selectFirst();
                    list.requestFocus(); list.scrollTo(list.getSelectionModel().getSelectedIndex());
                }
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER) { confirm.fire(); event.consume(); }
        });
        list.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!modified(event) && event.getCode() == KeyCode.ENTER) { confirm.fire(); event.consume(); }
        });
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!modified(event) && event.getCode() == KeyCode.ESCAPE) {
                if (kindFilter.isShowing()) kindFilter.hide(); else cancel.fire();
                event.consume();
            }
            else if (event.getCode() == KeyCode.F && event.isControlDown() && !event.isShiftDown()
                    && !event.isAltDown() && !event.isMetaDown()) {
                query.requestFocus(); query.selectAll(); event.consume();
            }
        });
        dialog.setOnShown(event -> { query.requestFocus(); reload(); });
        dialog.setOnHidden(event -> close());
        status.setText("打开后读取当前 Schema 的名称和类型"); updateConfirm();
    }

    Dialog<TableRef> dialog() { return dialog; }
    Optional<TableRef> showAndWait() { return dialog.showAndWait(); }

    private boolean usable() { return !closed.get() && allowed.getAsBoolean() && !dialog.getDialogPane().isDisabled(); }
    private boolean candidateAllowed() {
        return usable() && loaded && list.getItems().contains(list.getSelectionModel().getSelectedItem());
    }
    private void updateConfirm() {
        boolean selectable = candidateAllowed();
        confirm.setDisable(!selectable); copy.setDisable(!selectable || copyAction == null);
    }

    void installCopyAction(Function<TableRef, ConnectionTreeClipboard.CopyResult> action) {
        copyAction = java.util.Objects.requireNonNull(action); clearCopyStatus(); updateConfirm();
    }

    private void copySelected() {
        if (!candidateAllowed() || copyAction == null) { clearCopyStatus(); return; }
        ConnectionTreeClipboard.CopyResult result;
        try { result = copyAction.apply(list.getSelectionModel().getSelectedItem().ref()); }
        catch (RuntimeException unavailable) { result = null; }
        if (!candidateAllowed()) return;
        boolean success = result != null && result.success();
        copyStatus.setText(result == null ? "复制失败：无法写入系统剪贴板，请重试。" : result.message());
        copyStatus.setStyle("-fx-text-fill: " + (success ? "-brand-fg-muted;" : "-status-error;"));
        copyStatus.setVisible(true); copyStatus.setManaged(true);
    }

    private void clearCopyStatus() {
        copyStatus.setText(""); copyStatus.setVisible(false); copyStatus.setManaged(false);
    }

    void reload() {
        if (!usable()) { sourceChanged(); return; }
        long expected = ++revision;
        cancelActive(); all = List.of(); loaded = false; list.getItems().clear(); preview.clear(); clearCopyStatus();
        retry.setDisable(true); updateConfirm();
        status.setText("正在读取当前 Schema 的表/视图名称…"); placeholder.setText("读取中，可取消");
        try {
            active = submitter.submit(() -> {
                if (closed.get()) throw new java.util.concurrent.CancellationException();
                return SchemaObjectCatalog.validate(schema, loader.call());
            }, names -> {
                if (!current(expected)) return;
                all = names.stream().map(n -> new Entry(n, n.name().toLowerCase(Locale.ROOT))).toList();
                loaded = true; query.setDisable(false); retry.setDisable(false); filter();
                // Do not steal focus from Cancel or the preview when metadata arrives.
            }, failure -> {
                if (!current(expected)) return;
                retry.setDisable(false);
                status.setText(failure instanceof SchemaObjectCatalog.TooManyObjectsException
                        ? "此 Schema 超过 10000 个对象，未建立不完整索引；请使用连接树按分类浏览。"
                        : "无法读取表/视图名称，请检查连接与权限后重新读取。");
                placeholder.setText("未加载对象");
            });
            if (closed.get()) cancelActive();
        } catch (RuntimeException rejected) {
            if (current(expected)) {
                retry.setDisable(false); placeholder.setText("未加载对象");
                status.setText("读取暂不可用，请稍后重新读取。");
            }
        }
    }

    private boolean current(long expected) {
        if (closed.get() || revision != expected) return false;
        if (!usable()) { sourceChanged(); return false; }
        return true;
    }

    private void filter() {
        clearCopyStatus();
        if (!loaded || !usable()) return;
        TableInfo selected = list.getSelectionModel().getSelectedItem();
        String term = query.getText().strip().toLowerCase(Locale.ROOT);
        KindFilter type = kindFilter.getValue();
        List<TableInfo> shown = new ArrayList<>(); int count = 0;
        for (Entry entry : all) if (type != null && type.accepts(entry.value()) && entry.foldedName().contains(term)) {
            count++; if (shown.size() < 200) shown.add(entry.value());
        }
        list.getSelectionModel().clearSelection(); list.getItems().setAll(shown);
        if (selected != null && shown.contains(selected)) list.getSelectionModel().select(selected);
        status.setText("匹配 " + count + " / " + all.size() + " 个对象"
                + (count > 200 ? " · 仅显示前 200 个，请继续缩小范围" : "")
                + (queryRejected ? " · 筛选词最多 256 个字符，超长输入未应用" : ""));
        placeholder.setText(all.isEmpty() ? "当前 Schema 没有可见的表/视图" : "没有匹配的对象，请修改或清除筛选");
        updateConfirm();
    }

    /** FX-only source invalidation; stale names cannot be confirmed or retried. */
    void sourceChanged() {
        if (usable()) return;
        close(); loaded = false; all = List.of(); list.getItems().clear(); preview.clear(); clearCopyStatus();
        query.setDisable(true); kindFilter.hide(); kindFilter.setDisable(true); retry.setDisable(true); updateConfirm();
        status.setText("连接或 Schema 已变化，请关闭后重新打开查找。");
    }

    private static String kind(TableInfo item) { return item.kind() == TableInfo.Kind.VIEW ? "视图" : "表"; }
    private static boolean modified(KeyEvent event) {
        return event.isControlDown() || event.isAltDown() || event.isMetaDown() || event.isShiftDown();
    }
    private void cancelActive() { Future<?> task = active; if (task != null) task.cancel(true); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        cancelActive(); closeScope.run();
    }
}
