package com.datacube.fx;

import com.datacube.service.DataBrowseService;
import com.datacube.service.DataEditService;
import com.datacube.spi.model.EditableColumn;
import com.datacube.spi.model.PagedResult;
import com.datacube.spi.model.RowKey;
import com.datacube.spi.model.TableRef;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 表数据网格：分页 + 过滤 + 内联编辑（Navicat 风格）。
 *
 * <p>逐行离开即提交：单元格编辑（UPDATE）、新增行（INSERT）、删除行（DELETE，
 * 二次确认）。读写共用 {@code busy} 串行开关，保护共享连接。写能力经
 * {@link DataEditService}，只读分页经 {@link DataBrowseService}。
 */
public final class DataGridPane {

    private static final int PAGE_SIZE = 200;

    private final DataBrowseService browse;
    private final DataEditService edit;
    private final String connId;
    private final TableRef table;

    private final VBox root = new VBox(8);
    private TableView<EditableGridModel.Row> grid;
    private Label statusLabel;
    private Label hintLabel;
    private TextField filterField;
    private Button prevBtn, nextBtn, reloadBtn, addBtn, deleteBtn;

    private EditableGridModel model;
    private long offset = 0;
    private boolean hasMore = false;
    private volatile boolean busy = false;
    /** 程序化替换 items 期间抑制行离开提交（避免重载引发的误提交）。 */
    private boolean suppressCommit = false;

    public DataGridPane(DataBrowseService browse, DataEditService edit, String connId, TableRef table) {
        this.browse = browse;
        this.edit = edit;
        this.connId = connId;
        this.table = table;
        build();
        load();
    }

    public Node getNode() {
        return root;
    }

    // ---------- 构建 ----------

    private void build() {
        root.setPadding(new Insets(10));
        root.setStyle("-fx-font-family: 'Microsoft YaHei', 'Segoe UI', sans-serif; -fx-font-size: 13px;");

        grid = new TableView<>();
        grid.setPlaceholder(new Label("（无数据）"));
        grid.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        grid.setEditable(true);
        grid.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        grid.setRowFactory(tv -> new StyledRow());
        installRowLeaveCommit();
        installKeyHandlers();

        hintLabel = new Label();
        hintLabel.setVisible(false);
        hintLabel.setManaged(false);
        hintLabel.setStyle("-fx-text-fill: -warn-fg; -fx-background-color: -warn-bg; -fx-padding: 4 8; -fx-background-radius: 4;");

        root.getChildren().addAll(toolbar(), hintLabel, grid, statusBar());
        VBox.setVgrow(grid, Priority.ALWAYS);
    }

    private Node toolbar() {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(table.qualified());
        title.setStyle("-fx-font-weight: bold;");

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

        deleteBtn = new Button("🗑 删除行");
        deleteBtn.setStyle("-fx-text-fill: -status-error;");
        deleteBtn.setOnAction(e -> deleteSelectedRows());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        box.getChildren().addAll(title, filterField, reloadBtn, prevBtn, nextBtn, spacer, addBtn, deleteBtn);
        return box;
    }

    private Node statusBar() {
        statusLabel = new Label("就绪");
        statusLabel.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");
        HBox box = new HBox(statusLabel);
        box.setPadding(new Insets(4, 0, 0, 0));
        return box;
    }

    // ---------- 加载 ----------

    private void load() {
        if (busy) return;
        busy = true;
        setControlsDisabled(true);
        info("加载中...");

        final String filter = filterField.getText().trim();
        final long reqOffset = offset;
        new Thread(() -> {
            EditableGridModel m = model;
            PagedResult result = null;
            String err = null;
            try {
                if (m == null) {
                    List<EditableColumn> cols = edit.columns(connId, table);
                    m = new EditableGridModel(cols);
                }
                result = browse.page(connId, table, reqOffset, PAGE_SIZE, null,
                        filter.isEmpty() ? null : filter);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final EditableGridModel fModel = m;
            final PagedResult fResult = result;
            final String fErr = err;
            Platform.runLater(() -> {
                busy = false;
                setControlsDisabled(false);
                if (fErr != null) {
                    error("错误: " + fErr);
                } else {
                    model = fModel;
                    render(fResult);
                }
            });
        }, "DataGrid-Worker").start();
    }

    private void render(PagedResult result) {
        hasMore = result.hasMore();
        List<EditableColumn> cols = model.columns();

        grid.getColumns().clear();
        for (int i = 0; i < cols.size(); i++) {
            final int idx = i;
            EditableColumn ec = cols.get(i);
            TableColumn<EditableGridModel.Row, String> c = new TableColumn<>(ec.name());
            c.setCellValueFactory(d -> {
                EditableGridModel.Cell cell = d.getValue().cell(idx);
                return new javafx.beans.property.SimpleStringProperty(cell.isNull() ? "" : cell.text());
            });
            c.setCellFactory(tc -> new EditCell(idx));
            c.setEditable(model.canLocateRow() && ec.editable());
            c.setPrefWidth(estimateColumnWidth(ec.name(), result.rows(), idx));
            grid.getColumns().add(c);
        }

        ObservableList<EditableGridModel.Row> data = FXCollections.observableArrayList();
        for (List<Object> row : result.rows()) {
            data.add(model.toRow(row));
        }
        suppressCommit = true;
        grid.setItems(data);
        grid.getSelectionModel().clearSelection();
        suppressCommit = false;

        updateHint();
        long from = data.isEmpty() ? 0 : offset + 1;
        long to = offset + data.size();
        info("第 " + from + "–" + to + " 行" + (hasMore ? "（还有更多）" : ""));
        prevBtn.setDisable(offset == 0);
        nextBtn.setDisable(!hasMore);
    }

    private void updateHint() {
        String msg = null;
        if (!model.canLocateRow()) {
            msg = model.readOnlyReason();
        } else if (!model.hasPrimaryKey()) {
            msg = "无主键：更新/删除按全列旧值匹配，提交前校验仅影响 1 行（否则回滚）";
        }
        boolean show = msg != null;
        hintLabel.setText(show ? msg : "");
        hintLabel.setVisible(show);
        hintLabel.setManaged(show);
        boolean readOnly = !model.canLocateRow();
        addBtn.setDisable(readOnly);
        deleteBtn.setDisable(readOnly);
    }

    // ---------- 提交（逐行离开即提交） ----------

    private void installRowLeaveCommit() {
        grid.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
            if (suppressCommit || busy) return;
            if (oldRow != null && oldRow != newRow && oldRow.dirty()) {
                commitRow(oldRow, null);
            }
        });
    }

    private void installKeyHandlers() {
        grid.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isControlDown()) {
                EditableGridModel.Row d = selectedDirtyRow();
                if (d != null && !busy) {
                    commitRow(d, null);
                    e.consume();
                }
            } else if (e.getCode() == KeyCode.DELETE) {
                deleteSelectedRows();
                e.consume();
            }
        });
    }

    private EditableGridModel.Row selectedDirtyRow() {
        for (EditableGridModel.Row r : grid.getItems()) {
            if (r.dirty()) return r;
        }
        return null;
    }

    /** 提交单行；成功后：NEW/改动定位列 → 重载，否则原地清脏。{@code after} 于成功后执行。 */
    private void commitRow(EditableGridModel.Row row, Runnable after) {
        if (busy) return;
        LinkedHashMap<String, String> changed = model.changedValues(row);
        final boolean wasNew = row.state() == EditableGridModel.RowState.NEW;

        // NEW 行未填任何值：直接丢弃
        if (wasNew && changed.isEmpty()) {
            grid.getItems().remove(row);
            if (after != null) after.run();
            return;
        }
        // MODIFIED 但无实际改动：清脏即可
        if (!wasNew && changed.isEmpty()) {
            model.markClean(row);
            grid.refresh();
            if (after != null) after.run();
            return;
        }
        if (!model.canLocateRow()) {
            error("无法定位行：该表只读");
            return;
        }

        final boolean needReload = wasNew || model.changedAnyKeyColumn(row);
        final RowKey key = wasNew ? null : model.keyOf(row);

        busy = true;
        setControlsDisabled(true);
        info("提交中...");
        new Thread(() -> {
            String err = null;
            try {
                if (wasNew) {
                    edit.insert(connId, table, changed);
                } else {
                    edit.update(connId, table, changed, key);
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fErr = err;
            Platform.runLater(() -> {
                busy = false;
                setControlsDisabled(false);
                if (fErr != null) {
                    error("提交失败: " + fErr);
                    // 保留脏状态，焦点停留在该行
                    return;
                }
                if (needReload) {
                    if (after != null) after.run();
                    else load();
                } else {
                    model.markClean(row);
                    grid.refresh();
                    info("已提交 1 行");
                    if (after != null) after.run();
                }
            });
        }, "DataGrid-Commit").start();
    }

    // ---------- 分页（先提交当前脏行） ----------

    private void gotoPage(long newOffset) {
        Runnable act = () -> { offset = newOffset; load(); };
        EditableGridModel.Row d = selectedDirtyRow();
        if (d != null && !busy) commitRow(d, act);
        else act.run();
    }

    private void reloadFromStart() {
        Runnable act = () -> { offset = 0; load(); };
        EditableGridModel.Row d = selectedDirtyRow();
        if (d != null && !busy) commitRow(d, act);
        else act.run();
    }

    // ---------- 新增 / 删除 ----------

    private void addRow() {
        if (busy || model == null || !model.canLocateRow()) return;
        EditableGridModel.Row row = model.newRow();
        grid.getItems().add(row);
        int idx = grid.getItems().size() - 1;
        grid.scrollTo(idx);
        grid.getSelectionModel().clearAndSelect(idx);
    }

    private void deleteSelectedRows() {
        if (busy || model == null || !model.canLocateRow()) return;
        List<EditableGridModel.Row> selected = List.copyOf(grid.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "确认删除选中的 " + selected.size() + " 行？此操作不可撤销。",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        confirm.setTitle("删除确认");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        busy = true;
        setControlsDisabled(true);
        info("删除中...");
        new Thread(() -> {
            String err = null;
            int done = 0;
            for (EditableGridModel.Row row : selected) {
                if (row.state() == EditableGridModel.RowState.NEW) {
                    continue; // 未提交的新增行，仅 UI 移除
                }
                try {
                    edit.delete(connId, table, model.keyOf(row));
                    done++;
                } catch (Exception e) {
                    err = e.getMessage();
                    break;
                }
            }
            final String fErr = err;
            final int fDone = done;
            Platform.runLater(() -> {
                busy = false;
                setControlsDisabled(false);
                if (fErr != null) {
                    error("删除失败（已删 " + fDone + " 行）: " + fErr);
                    load(); // 重载以反映真实状态
                } else {
                    grid.getItems().removeAll(selected);
                    info("已删除 " + selected.size() + " 行");
                }
            });
        }, "DataGrid-Delete").start();
    }

    // ---------- 单元格 ----------

    /** 可编辑单元格：NULL 斜体灰显、右键设为 NULL、非字符列清空即 NULL。 */
    private final class EditCell extends TableCell<EditableGridModel.Row, String> {
        private final int col;
        private TextField editor;
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
            return model != null && model.canLocateRow() && model.columnEditable(col);
        }

        @Override
        public void startEdit() {
            if (!editableCell() || isEmpty()) return;
            super.startEdit();
            if (editor == null) createEditor();
            EditableGridModel.Cell c = cellModel();
            editor.setText(c != null && !c.isNull() ? c.text() : "");
            setText(null);
            setGraphic(editor);
            editor.selectAll();
            editor.requestFocus();
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            renderCell();
        }

        @Override
        public void commitEdit(String newValue) {
            super.commitEdit(newValue);
            renderCell();
        }

        private void createEditor() {
            editor = new TextField();
            editor.setOnAction(e -> doCommit(editor.getText()));
            editor.focusedProperty().addListener((o, was, is) -> {
                if (!is && isEditing()) doCommit(editor.getText());
            });
            editor.setOnKeyReleased(e -> {
                if (e.getCode() == KeyCode.ESCAPE) cancelEdit();
            });
        }

        private void doCommit(String text) {
            EditableGridModel.Cell c = cellModel();
            if (c != null) {
                // 非字符类型清空视作 NULL；字符类型空串保留为空串
                if (text != null && text.isEmpty() && !EditableGridModel.isCharType(model.jdbcType(col))) {
                    c.setNull();
                } else {
                    c.setText(text);
                }
                markDirty();
            }
            commitEdit(text);
            grid.refresh();
        }

        private void markDirty() {
            EditableGridModel.Row row = getTableRow() == null ? null : getTableRow().getItem();
            if (row != null && row.state() == EditableGridModel.RowState.CLEAN) {
                row.setState(EditableGridModel.RowState.MODIFIED);
            }
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
                default -> setStyle("");
            }
        }
    }

    // ---------- 辅助 ----------

    private void setControlsDisabled(boolean disabled) {
        reloadBtn.setDisable(disabled);
        prevBtn.setDisable(disabled);
        nextBtn.setDisable(disabled);
        filterField.setDisable(disabled);
        addBtn.setDisable(disabled || model == null || !model.canLocateRow());
        deleteBtn.setDisable(disabled || model == null || !model.canLocateRow());
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
