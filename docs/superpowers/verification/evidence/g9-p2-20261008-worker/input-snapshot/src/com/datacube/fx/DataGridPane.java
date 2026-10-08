package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.AppSettings.CommentMode;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.service.DataBrowseService;
import com.datacube.service.DataEditService;
import com.datacube.spi.model.EditableColumn;
import com.datacube.spi.model.PagedResult;
import com.datacube.spi.model.TableRef;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.beans.value.ChangeListener;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 表数据网格：分页 + 过滤 + 内联编辑（Navicat 风格）。
 *
 * <p>修改保留在页面变更集中，只有显式保存触发逐行独立事务。
 * 读写共用 {@code busy} 串行开关。写能力经
 * {@link DataEditService}，只读分页经 {@link DataBrowseService}。
 */
public final class DataGridPane implements AutoCloseable {

    private static final int PAGE_SIZE = 200;

    private final DataBrowseService browse;
    private final DataEditService edit;
    private final com.datacube.service.WriteTarget writeTarget;
    private Runnable stopWatchingSafety = () -> {};
    private Label safetyLabel;
    private final String connId;
    private final String connName;
    private final TableRef table;
    private final AppSettings settings;
    /** 是否强制只读（如视图）：禁用新增/删除/编辑，仅查看数据。 */
    private final boolean readOnly;
    private final FxTaskScope tasks;
    private final ChangeListener<CommentMode> commentModeListener;

    private final VBox root = new VBox(8);
    private TableView<EditableGridModel.Row> grid;
    private Label statusLabel;
    private Label hintLabel;
    private TextField filterField;
    private Button prevBtn, nextBtn, reloadBtn, addBtn, deleteBtn;
    private Button saveBtn, previewBtn, discardBtn, discardAllBtn, cancelSaveBtn;
    private Label changesLabel;
    private GridChangeSet changes;
    private EditCell activeEditor;
    private String appliedFilter = "";
    private volatile SaveAttempt saveAttempt;
    private final AsyncTabCloseGuard cleanupGuard = AsyncTabCloseGuards.blocking(this::closeResources);

    private EditableGridModel model;
    private long offset = 0;
    private boolean hasMore = false;
    private volatile boolean busy = false;
    /** 当前渲染的数据列（不含序号列），供注释模式切换时重刷表头。 */
    private final List<TableColumn<EditableGridModel.Row, String>> dataColumns = new ArrayList<>();

    public DataGridPane(DataBrowseService browse, DataEditService edit, String connId, String connName,
                        TableRef table, AppSettings settings, boolean readOnly, FxTaskRunner runner) {
        this.browse = browse;
        this.edit = edit;
        this.writeTarget = edit.target(connId);
        this.connId = connId;
        this.connName = connName;
        this.table = table;
        this.settings = settings;
        this.readOnly = readOnly || writeTarget.safety().readOnly();
        this.commentModeListener = (o, a, b) -> reapplyHeaders();
        ConstructionOwner construction = new ConstructionOwner();
        try {
            this.tasks = runner.scope();
            construction.own(tasks::close);
            build();
            stopWatchingSafety = WriteSafetyDialog.watch(writeTarget, tasks, () -> {
                WriteSafetyDialog.update(safetyLabel, writeTarget);
                setControlsDisabled(busy);
                if (model != null) updateHint();
            });
            construction.own(stopWatchingSafety::run);
            settings.commentModeProperty().addListener(commentModeListener);
            construction.own(() -> settings.commentModeProperty().removeListener(commentModeListener));
            load();
            construction.commit();
        } catch (Throwable failure) {
            throw construction.close(failure).failure();
        }
    }

    public Node getNode() {
        return root;
    }

    @Override
    @Deprecated(forRemoval = false)
    public void close() {
        closeResources();
        if (Platform.isFxApplicationThread()) finalizeCloseOnFx();
        else Platform.runLater(this::finalizeCloseOnFx);
    }

    void closeResources() {
        stopWatchingSafety.run();
        SaveAttempt attempt = saveAttempt;
        if (attempt != null) attempt.cancelled.set(true);
        tasks.close();
        if (attempt != null) attempt.awaitClose();
    }

    public CompletionStage<CloseGuardOutcome> requestClose() {
        if (!Platform.isFxApplicationThread()) return CompletableFuture.failedFuture(new IllegalStateException("必须在界面线程请求关闭"));
        if (busy) {
            info("操作正在进行，请等待保存结果后关闭；取消剩余保存不会撤回已提交行。");
            return CompletableFuture.completedFuture(CloseGuardOutcome.REJECTED);
        }
        flushEditor();
        return confirmLeave("关闭页面") ? requestMandatoryClose()
                : CompletableFuture.completedFuture(CloseGuardOutcome.REJECTED);
    }
    public CompletionStage<CloseGuardOutcome> requestMandatoryClose() { return cleanupGuard.requestClose(); }

    void finalizeCloseOnFx() {
        settings.commentModeProperty().removeListener(commentModeListener);
    }

    // ---------- 构建 ----------

    private void build() {
        root.setPadding(new Insets(10));
        root.setStyle("-fx-font-family: 'Microsoft YaHei', 'Segoe UI', sans-serif; -fx-font-size: 13px;");

        grid = new TableView<>();
        grid.setPlaceholder(new Label("（无数据）"));
        grid.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        grid.setEditable(!readOnly);
        grid.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        grid.setRowFactory(tv -> new StyledRow());
        installKeyHandlers();

        hintLabel = new Label();
        hintLabel.setVisible(false);
        hintLabel.setManaged(false);
        hintLabel.setStyle("-fx-text-fill: -warn-fg; -fx-background-color: -warn-bg; -fx-padding: 4 8; -fx-background-radius: 4;");

        safetyLabel = WriteSafetyDialog.label(writeTarget);
        root.getChildren().addAll(toolbar(), changeToolbar(), safetyLabel, hintLabel, grid, statusBar());
        VBox.setVgrow(grid, Priority.ALWAYS);
    }

    private Node toolbar() {
        FlowPane box = new FlowPane(8, 6);
        box.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(table.qualified());
        title.setStyle("-fx-font-weight: bold;");
        title.setMaxWidth(280);
        title.setTooltip(new Tooltip(table.qualified()));

        Label connLabel = new Label();
        connLabel.setStyle("-fx-text-fill: -brand-fg-muted;");
        if (connName != null && !connName.isEmpty()) connLabel.setText("🔗 " + connName);
        connLabel.setMaxWidth(220);
        connLabel.setTooltip(new Tooltip(connLabel.getText()));

        filterField = new TextField();
        filterField.setPromptText("WHERE 过滤（不含 WHERE，如 id > 100）");
        filterField.setPrefWidth(280);
        filterField.setOnAction(e -> reloadFromStart());

        reloadBtn = new Button("查询");
        reloadBtn.setOnAction(e -> reloadFromStart());

        prevBtn = new Button("上一页");
        prevBtn.setOnAction(e -> gotoPage(Math.max(0, offset - PAGE_SIZE)));

        nextBtn = new Button("下一页");
        nextBtn.setOnAction(e -> {
            if (hasMore) gotoPage(offset + PAGE_SIZE);
        });

        addBtn = new Button("＋ 新增行");
        addBtn.setOnAction(e -> addRow());

        deleteBtn = new Button("标记删除");
        deleteBtn.setStyle("-fx-text-fill: -status-error;");
        deleteBtn.setOnAction(e -> deleteSelectedRows());

        box.getChildren().addAll(title, connLabel, filterField, reloadBtn, prevBtn, nextBtn, addBtn, deleteBtn);
        return box;
    }

    private Node statusBar() {
        statusLabel = new Label("就绪");
        statusLabel.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");
        HBox box = new HBox(statusLabel);
        box.setPadding(new Insets(4, 0, 0, 0));
        return box;
    }

    private Node changeToolbar() {
        saveBtn = new Button("保存到数据库"); saveBtn.setOnAction(e -> saveChanges());
        previewBtn = new Button("预览修改 SQL"); previewBtn.setOnAction(e -> previewChanges());
        discardBtn = new Button("放弃选中修改"); discardBtn.setOnAction(e -> discardChanges(false));
        discardAllBtn = new Button("放弃全部修改"); discardAllBtn.setOnAction(e -> discardChanges(true));
        cancelSaveBtn = new Button("取消剩余保存");
        cancelSaveBtn.setOnAction(e -> {
            SaveAttempt attempt = saveAttempt;
            if (attempt != null) attempt.cancelled.set(true);
            cancelSaveBtn.setDisable(true);
            info("已请求停止后续行；当前行可能提交，等待实际结果。");
        });
        changesLabel = new Label("尚无待保存修改");
        return new FlowPane(8, 6, saveBtn, previewBtn, discardBtn, discardAllBtn, cancelSaveBtn, changesLabel);
    }

    // ---------- 加载 ----------

    private void load() {
        loadPage(offset, appliedFilter);
    }

    private void loadPage(long requestOffset, String requestFilter) {
        if (busy) return;
        busy = true;
        setControlsDisabled(true);
        info("加载中...");

        final String filter = requestFilter;
        final long reqOffset = requestOffset;
        final EditableGridModel existingModel = model;
        tasks.submit(() -> {
            EditableGridModel loadedModel = existingModel;
            if (loadedModel == null) {
                List<EditableColumn> cols = edit.columns(connId, table);
                loadedModel = new EditableGridModel(cols, readOnly);
            }
            PagedResult result = browse.page(connId, table, reqOffset, PAGE_SIZE, null,
                    filter.isEmpty() ? null : filter);
            return new LoadResult(loadedModel, result);
        }, loaded -> {
            busy = false;
            setControlsDisabled(false);
            model = loaded.model();
            offset = reqOffset; appliedFilter = filter;
            render(loaded.result());
        }, failure -> {
            busy = false;
            setControlsDisabled(false);
            error("错误: " + message(failure));
        });
    }

    private void render(PagedResult result) {
        hasMore = result.hasMore();
        List<EditableColumn> cols = model.columns();

        grid.getColumns().clear();
        grid.getColumns().add(buildSeqColumn());
        dataColumns.clear();
        for (int i = 0; i < cols.size(); i++) {
            final int idx = i;
            EditableColumn ec = cols.get(i);
            TableColumn<EditableGridModel.Row, String> c = new TableColumn<>();
            applyColumnHeader(c, ec.name(), ec.comment());
            c.setCellValueFactory(d -> {
                EditableGridModel.Cell cell = d.getValue().cell(idx);
                return new javafx.beans.property.SimpleStringProperty(cell.isNull() ? "" : cell.text());
            });
            c.setCellFactory(tc -> new EditCell(idx));
            c.setEditable(model.canLocateRow() && ec.editable());
            c.setPrefWidth(estimateColumnWidth(ec.name(), result.rows(), idx));
            dataColumns.add(c);
            grid.getColumns().add(c);
        }

        changes = new GridChangeSet(model, result.rows());
        ObservableList<EditableGridModel.Row> data = FXCollections.observableArrayList(changes.rows());
        grid.setItems(data);
        grid.getSelectionModel().clearSelection();

        updateHint();
        long from = data.isEmpty() ? 0 : offset + 1;
        long to = offset + data.size();
        info("第 " + from + "–" + to + " 行" + (hasMore ? "（还有更多）" : ""));
        updateChanges();
    }

    /** 行号列（序号）：显示分页全局序号（offset+行内序号+1），不可编辑、不参与排序。 */
    private TableColumn<EditableGridModel.Row, String> buildSeqColumn() {
        TableColumn<EditableGridModel.Row, String> seq = new TableColumn<>("# / 修改状态");
        seq.setSortable(false);
        seq.setEditable(false);
        seq.setResizable(false);
        seq.setPrefWidth(240);
        seq.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() < 0) {
                    setText(null);
                    setStyle("");
                } else {
                    var row = getTableRow() == null ? null : getTableRow().getItem();
                    String state = row == null ? "" : switch (row.state()) {
                        case CLEAN -> ""; case NEW -> " · 新增"; case MODIFIED -> " · 修改";
                        case DELETED -> " · 待删除"; case SAVED -> " · 待刷新"; case UNKNOWN -> " · 结果不确定";
                    };
                    setText((offset + getIndex() + 1) + state + (row == null || row.result().isEmpty() ? "" : " · " + row.result()));
                    setTooltip(row == null || row.result().isEmpty() ? null : new Tooltip(row.result()));
                    setStyle("-fx-alignment: CENTER_RIGHT; -fx-text-fill: -brand-fg-muted;");
                }
            }
        });
        return seq;
    }

    /** 根据当前注释显示模式设置列头（纯文本 / 悬停 Tooltip / 固定两行）。 */
    private void applyColumnHeader(TableColumn<EditableGridModel.Row, String> c, String name, String comment) {
        boolean hasComment = comment != null && !comment.isEmpty();
        CommentMode mode = settings.getCommentMode();
        if (!hasComment || mode == CommentMode.OFF) {
            c.setGraphic(null);
            c.setText(name);
            return;
        }
        if (mode == CommentMode.INLINE) {
            Label nameLabel = new Label(name);
            Label commentLabel = new Label(comment);
            commentLabel.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 11px;");
            VBox box = new VBox(1, nameLabel, commentLabel);
            c.setText("");
            c.setGraphic(box);
        } else { // HOVER
            Label nameLabel = new Label(name);
            // 让标题 Label 撜满整个表头宽度，悬停表头任意处均可触发 Tooltip
            nameLabel.setMaxWidth(Double.MAX_VALUE);
            nameLabel.prefWidthProperty().bind(c.widthProperty());
            Tooltip tip = new Tooltip(name + "\n" + comment);
            tip.setWrapText(true);
            tip.setMaxWidth(360);
            tip.setShowDelay(Duration.millis(300));
            nameLabel.setTooltip(tip);
            c.setText("");
            c.setGraphic(nameLabel);
        }
    }

    /** 注释显示模式切换时，按当前列元数据重刷数据列表头（不重载数据）。 */
    private void reapplyHeaders() {
        if (model == null) return;
        List<EditableColumn> cols = model.columns();
        for (int i = 0; i < dataColumns.size() && i < cols.size(); i++) {
            applyColumnHeader(dataColumns.get(i), cols.get(i).name(), cols.get(i).comment());
        }
    }

    private void updateHint() {
        String msg = writeTarget.blockedReason().isEmpty() ? null : writeTarget.blockedReason();
        if (msg == null && !model.canLocateRow()) {
            msg = model.readOnlyReason();
        } else if (msg == null && !model.hasPrimaryKey()) {
            msg = "无主键：按可比较旧值匹配，每行仅允许影响 1 行；LOB/二进制/时间戳等不参与匹配。";
        }
        boolean show = msg != null;
        hintLabel.setText(show ? msg : "");
        hintLabel.setVisible(show);
        hintLabel.setManaged(show);
        setControlsDisabled(busy);
    }

    // ---------- 页面变更与显式保存 ----------

    private void installKeyHandlers() {
        grid.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE && activeEditor == null) {
                deleteSelectedRows(); e.consume();
            }
        });
    }

    private void flushEditor() { if (activeEditor != null) activeEditor.flush(); }

    private void updateChanges() {
        if (changesLabel == null) return;
        long count = changes == null ? 0 : changes.pendingCount();
        changesLabel.setText("待保存 " + count + " 行 · 逐行独立提交，失败即停止");
        setControlsDisabled(busy);
        grid.refresh();
    }

    private void refreshRows() {
        var selected = List.copyOf(grid.getSelectionModel().getSelectedItems());
        grid.getItems().setAll(changes.rows());
        for (var row : selected) if (grid.getItems().contains(row)) grid.getSelectionModel().select(row);
        updateChanges();
    }

    private void addRow() {
        if (busy || model == null || !model.canLocateRow() || !writeTarget.blockedReason().isEmpty()) return;
        flushEditor();
        try {
            var row = changes.add(); refreshRows();
            grid.getSelectionModel().clearSelection(); grid.getSelectionModel().select(row); grid.scrollTo(row);
        } catch (IllegalStateException failure) { error(failure.getMessage()); }
    }

    private void deleteSelectedRows() {
        if (busy || model == null || !model.canLocateRow() || !writeTarget.blockedReason().isEmpty()) return;
        flushEditor(); changes.delete(List.copyOf(grid.getSelectionModel().getSelectedItems())); refreshRows();
        info("仅标记删除；点击“保存到数据库”才会执行，可放弃修改。");
    }

    private void discardChanges(boolean all) {
        if (busy || changes == null) return;
        flushEditor();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "放弃" + (all ? "全部" : "选中") + "未保存修改？这不会撤回已提交的行；不确定结果仍需核对数据库。",
                new ButtonType("放弃修改", ButtonBar.ButtonData.OTHER), ButtonType.CANCEL);
        confirm.setHeaderText(null); defaultCancel(confirm);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) return;
        if (tasks.isClosed()) return;
        if (all) changes.discardAll(); else changes.discard(List.copyOf(grid.getSelectionModel().getSelectedItems()));
        refreshRows(); info("已放弃本地修改；没有执行数据库写入。");
    }

    private boolean confirmLeave(String action) {
        if (changes == null || !changes.hasPending()) return true;
        ButtonType discard = new ButtonType("放弃未保存修改并" + action, ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "当前有 " + changes.pendingCount() + " 行未保存修改。需要保存时请取消此操作，再点击“保存到数据库”。\n"
                        + "放弃不会撤回已提交行；结果不确定的行请先核对数据库。", discard, ButtonType.CANCEL);
        alert.setTitle("待保存修改"); alert.setHeaderText(null); defaultCancel(alert);
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != discard || tasks.isClosed()) return false;
        changes.discardAll(); refreshRows(); return true;
    }

    private static void defaultCancel(Dialog<ButtonType> dialog) {
        for (ButtonType type : dialog.getDialogPane().getButtonTypes()) {
            Node node = dialog.getDialogPane().lookupButton(type);
            if (node instanceof Button button) button.setDefaultButton(type == ButtonType.CANCEL);
        }
    }

    private void gotoPage(long newOffset) {
        if (busy || tasks.isClosed()) return;
        flushEditor();
        if (confirmLeave("翻页")) loadPage(newOffset, appliedFilter);
    }

    private void reloadFromStart() {
        if (busy || tasks.isClosed()) return;
        flushEditor();
        if (confirmLeave("重新查询")) loadPage(0, filterField.getText().trim());
    }

    private void previewChanges() {
        if (busy || changes == null || tasks.isClosed()) return;
        flushEditor();
        try {
            String preview = edit.preview(writeTarget, table, changes.snapshot());
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("预览修改 SQL（未执行）");
            TextArea text = new TextArea(preview); text.setEditable(false);
            text.setPrefColumnCount(92); text.setPrefRowCount(24);
            dialog.getDialogPane().setContent(text); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dialog.showAndWait();
        } catch (IllegalArgumentException | IllegalStateException failure) { error(failure.getMessage()); }
    }

    private void saveChanges() {
        if (busy || changes == null || tasks.isClosed()) return;
        flushEditor();
        if (!changes.hasPending()) return;
        GridChangeSet owner = changes;
        SaveAttempt attempt = new SaveAttempt();
        busy = true; setControlsDisabled(true);
        try {
            var snapshot = owner.snapshot();
            var request = edit.prepareSave(writeTarget, table, snapshot, attempt.cancelled::get);
            var confirmation = WriteSafetyDialog.confirm(request, true);
            if (confirmation == null || tasks.isClosed()) { busy = false; updateChanges(); return; }
            saveAttempt = attempt;
            setControlsDisabled(true); info("逐行保存中；取消只能停止剩余行。");
            tasks.submit(() -> {
                if (!attempt.claimed.compareAndSet(false, true)) return new DataEditService.SaveResult(List.of());
                try { return request.execute(confirmation); }
                finally { attempt.settled.complete(null); }
            }, result -> {
                busy = false; saveAttempt = null;
                if (changes != owner) return;
                owner.apply(result); updateChanges();
                String details = result.rows().stream().map(row -> "行标识 " + row.rowId() + ": " + row.message())
                        .collect(java.util.stream.Collectors.joining("\n"));
                statusLabel.setTooltip(new Tooltip(details));
                info("本次已提交 " + result.committedCount() + " / " + result.rows().size()
                        + " 行；其余保留在页面。逐行结果见行号提示；已提交行需刷新后继续编辑。");
            }, failure -> {
                busy = false; saveAttempt = null; updateChanges();
                error("保存未开始：连接安全状态已变化或请求失效。当前修改已保留。");
            });
        } catch (RuntimeException failure) {
            attempt.cancelled.set(true);
            if (attempt.claimed.compareAndSet(false, true)) attempt.settled.complete(null);
            saveAttempt = null; busy = false; updateChanges(); error(message(failure));
        }
    }

    /** Mandatory cleanup must not approve while an admitted row may still be running. */
    private static final class SaveAttempt {
        final AtomicBoolean cancelled = new AtomicBoolean(), claimed = new AtomicBoolean();
        final CompletableFuture<Void> settled = new CompletableFuture<>();
        void awaitClose() {
            cancelled.set(true);
            if (claimed.compareAndSet(false, true)) settled.complete(null);
            try { settled.get(15, TimeUnit.SECONDS); }
            catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException("无法确认在途行保存已结束；不得将关闭视为回滚", failure);
            }
        }
    }

    // ---------- 单元格 ----------

    /** 可编辑单元格：NULL 斜体灰显、右键设为 NULL、非字符列清空即 NULL。 */
    private final class EditCell extends TableCell<EditableGridModel.Row, String> {
        private final int col;
        private TextField editor;
        private EditableGridModel.Row editingRow;
        private boolean textChanged;
        private final ContextMenu menu;

        EditCell(int col) {
            this.col = col;
            MenuItem setNull = new MenuItem("设为 NULL");
            setNull.setOnAction(e -> {
                EditableGridModel.Cell c = cellModel();
                if (c != null && editableCell()) {
                    c.setNull();
                    markDirty();
                    renderCell();
                    grid.refresh();
                }
            });
            this.menu = new ContextMenu(setNull);
        }

        private EditableGridModel.Cell cellModel() {
            TableRow<EditableGridModel.Row> tr = getTableRow();
            EditableGridModel.Row row = tr == null ? null : tr.getItem();
            return row == null ? null : row.cell(col);
        }

        private boolean editableCell() {
            var row = getTableRow() == null ? null : getTableRow().getItem();
            return !busy && !readOnly && writeTarget.blockedReason().isEmpty()
                    && row != null && row.editable() && model != null && model.canLocateRow() && model.columnEditable(col);
        }

        @Override
        public void startEdit() {
            if (!editableCell() || isEmpty()) return;
            super.startEdit();
            if (!isEditing()) return;
            editingRow = getTableRow().getItem(); activeEditor = this;
            if (editor == null) createEditor();
            EditableGridModel.Cell c = cellModel();
            editor.setText(c != null && !c.isNull() ? c.text() : "");
            textChanged = false;
            setControlsDisabled(busy);
            setText(null);
            setGraphic(editor);
            editor.selectAll();
            editor.requestFocus();
        }

        @Override
        public void cancelEdit() {
            boolean hadDraft = editingRow != null;
            stageText();
            editingRow = null;
            if (activeEditor == this) activeEditor = null;
            super.cancelEdit();
            renderCell();
            if (hadDraft) updateChanges();
            else setControlsDisabled(busy);
        }

        @Override
        public void commitEdit(String newValue) {
            super.commitEdit(newValue);
            renderCell();
        }

        private void createEditor() {
            editor = new TextField();
            editor.textProperty().addListener((o, before, after) -> {
                if (editingRow != null) { textChanged = true; setControlsDisabled(busy); }
            });
            editor.setOnAction(e -> doCommit(editor.getText()));
            editor.focusedProperty().addListener((o, was, is) -> {
                if (!is && editingRow != null) doCommit(editor.getText());
            });
            editor.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    editingRow = null; cancelEdit(); e.consume();
                }
            });
        }

        private void doCommit(String text) {
            stageText();
            editingRow = null;
            if (activeEditor == this) activeEditor = null;
            commitEdit(text);
            updateChanges();
        }

        void flush() { if (editingRow != null && editor != null) doCommit(editor.getText()); }

        private void stageText() {
            String text = editor == null ? null : editor.getText();
            EditableGridModel.Cell c = editingRow == null ? null : editingRow.cell(col);
            if (c != null && textChanged) {
                // 非字符类型清空视作 NULL；字符类型空串保留为空串
                if (text != null && text.isEmpty() && !EditableGridModel.isCharType(model.jdbcType(col))) {
                    c.setNull();
                } else {
                    c.setText(text);
                }
                model.reconcile(editingRow);
            }
        }

        private void markDirty() {
            EditableGridModel.Row row = getTableRow() == null ? null : getTableRow().getItem();
            if (row != null) model.reconcile(row);
            updateChanges();
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                setStyle("");
                return;
            }
            if (isEditing()) return;
            renderCell();
        }

        private void renderCell() {
            setGraphic(null);
            EditableGridModel.Cell c = cellModel();
            setContextMenu(editableCell() ? menu : null);
            if (c == null) {
                setText(getItem());
                setStyle("");
                return;
            }
            if (c.isNull()) {
                setText("(NULL)");
                setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-style: italic;");
            } else {
                setText(c.text());
                setStyle(editableCell() ? "" : "-fx-text-fill: -brand-fg-muted;");
            }
        }
    }

    /** 行底纹：MODIFIED 浅黄、NEW 浅绿。 */
    private final class StyledRow extends TableRow<EditableGridModel.Row> {
        @Override
        protected void updateItem(EditableGridModel.Row item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setStyle("");
                return;
            }
            switch (item.state()) {
                case MODIFIED -> setStyle("-fx-background-color: -cell-modified-bg;");
                case NEW -> setStyle("-fx-background-color: -cell-new-bg;");
                case DELETED -> setStyle("-fx-opacity: 0.6;");
                case UNKNOWN -> setStyle("-fx-background-color: -warn-bg;");
                default -> setStyle("");
            }
        }
    }

    // ---------- 辅助 ----------

    private void setControlsDisabled(boolean disabled) {
        reloadBtn.setDisable(disabled);
        prevBtn.setDisable(disabled || offset == 0);
        nextBtn.setDisable(disabled || !hasMore);
        filterField.setDisable(disabled);
        WriteSafetyDialog.update(addBtn, writeTarget, disabled || model == null || !model.canLocateRow());
        WriteSafetyDialog.update(deleteBtn, writeTarget, disabled || model == null || !model.canLocateRow());
        if (saveBtn == null) return;
        boolean pending = changes != null && (changes.hasPending() || activeEditor != null && activeEditor.textChanged);
        long count = changes == null ? 0 : changes.pendingCount();
        if (activeEditor != null && activeEditor.textChanged && activeEditor.editingRow != null && !activeEditor.editingRow.dirty()) count++;
        if (changesLabel != null) changesLabel.setText("待保存 " + count + " 行 · 逐行独立提交，失败即停止");
        WriteSafetyDialog.update(saveBtn, writeTarget, disabled || model == null || !model.canLocateRow() || !pending || changes.hasUnknown());
        previewBtn.setDisable(disabled || !pending || changes.hasUnknown());
        discardBtn.setDisable(disabled || !pending); discardAllBtn.setDisable(disabled || !pending);
        cancelSaveBtn.setDisable(saveAttempt == null || saveAttempt.cancelled.get());
        grid.setEditable(!disabled && !readOnly && writeTarget.blockedReason().isEmpty());
    }

    private void info(String msg) {
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-text-fill: -status-ok; -fx-font-size: 12px;");
    }

    private void error(String msg) {
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-text-fill: -status-error; -fx-font-size: 12px;");
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setHeaderText(null);
        a.setTitle("操作失败");
        a.showAndWait();
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record LoadResult(EditableGridModel model, PagedResult result) {}

    /** 估算列宽：取表头与前若干行内容的最大字符数，换算像素并裁剪到 [60, 360]。 */
    private static double estimateColumnWidth(String header, List<List<Object>> rows, int idx) {
        int maxLen = header == null ? 0 : header.length();
        int sample = Math.min(rows.size(), 100);
        for (int r = 0; r < sample; r++) {
            List<Object> row = rows.get(r);
            if (idx < row.size() && row.get(idx) != null) {
                int len = row.get(idx).toString().length();
                if (len > maxLen) maxLen = len;
            }
        }
        double px = maxLen * 8.0 + 24;
        return Math.max(60, Math.min(360, px));
    }
}
