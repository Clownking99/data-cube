package com.datacube.fx;

import com.datacube.service.DataBrowseService;
import com.datacube.spi.model.PagedResult;
import com.datacube.spi.model.TableRef;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;

/**
 * 表数据浏览面板（只读）：分页 + 过滤，数据来自 {@link DataBrowseService}。
 */
public final class DataGridPane {

    private static final int PAGE_SIZE = 200;

    private final DataBrowseService browse;
    private final String connId;
    private final TableRef table;

    private final VBox root = new VBox(8);
    private TableView<ObservableList<String>> grid;
    private Label statusLabel;
    private TextField filterField;
    private Button prevBtn, nextBtn, reloadBtn;

    private long offset = 0;
    private boolean hasMore = false;
    private volatile boolean loading = false;

    public DataGridPane(DataBrowseService browse, String connId, TableRef table) {
        this.browse = browse;
        this.connId = connId;
        this.table = table;
        build();
        load();
    }

    public Node getNode() {
        return root;
    }

    private void build() {
        root.setPadding(new Insets(10));
        root.setStyle("-fx-font-family: 'Microsoft YaHei', 'Segoe UI', sans-serif; -fx-font-size: 13px;");

        grid = new TableView<>();
        grid.setPlaceholder(new Label("（无数据）"));
        // 不用 CONSTRAINED：它会强制列填满视口、拉宽首列且隐藏底部横向滚动条。
        // UNCONSTRAINED + 逐列估算宽度，宽表可横向滚动。
        grid.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        root.getChildren().addAll(toolbar(), grid, statusBar());
        VBox.setVgrow(grid, Priority.ALWAYS);
    }

    private Node toolbar() {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);

        filterField = new TextField();
        filterField.setPromptText("WHERE 过滤（不含 WHERE，如 id > 100）");
        filterField.setPrefWidth(280);
        filterField.setOnAction(e -> { offset = 0; load(); });

        reloadBtn = new Button("查询");
        reloadBtn.setOnAction(e -> { offset = 0; load(); });

        prevBtn = new Button("上一页");
        prevBtn.setOnAction(e -> {
            offset = Math.max(0, offset - PAGE_SIZE);
            load();
        });

        nextBtn = new Button("下一页");
        nextBtn.setOnAction(e -> {
            if (hasMore) {
                offset += PAGE_SIZE;
                load();
            }
        });

        Label title = new Label(table.qualified());
        title.setStyle("-fx-font-weight: bold;");

        box.getChildren().addAll(title, filterField, reloadBtn, prevBtn, nextBtn);
        return box;
    }

    private Node statusBar() {
        statusLabel = new Label("就绪");
        statusLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");
        HBox box = new HBox(statusLabel);
        box.setPadding(new Insets(4, 0, 0, 0));
        return box;
    }

    private void load() {
        if (loading) return;
        loading = true;
        setControlsDisabled(true);
        statusLabel.setText("加载中...");
        statusLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        final String filter = filterField.getText().trim();
        new Thread(() -> {
            PagedResult result = null;
            String err = null;
            try {
                result = browse.page(connId, table, offset, PAGE_SIZE, null,
                        filter.isEmpty() ? null : filter);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final PagedResult fResult = result;
            final String fErr = err;
            Platform.runLater(() -> {
                loading = false;
                setControlsDisabled(false);
                if (fErr != null) {
                    statusLabel.setText("错误: " + fErr);
                    statusLabel.setStyle("-fx-text-fill: #d32f2f; -fx-font-size: 12px;");
                } else {
                    render(fResult);
                }
            });
        }, "DataGrid-Worker").start();
    }

    private void render(PagedResult result) {
        hasMore = result.hasMore();
        grid.getColumns().clear();
        List<String> cols = result.columns();
        for (int i = 0; i < cols.size(); i++) {
            final int idx = i;
            TableColumn<ObservableList<String>, String> c = new TableColumn<>(cols.get(i));
            c.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                    idx < d.getValue().size() ? d.getValue().get(idx) : ""));
            c.setPrefWidth(estimateColumnWidth(cols.get(i), result.rows(), idx));
            grid.getColumns().add(c);
        }
        ObservableList<ObservableList<String>> data = FXCollections.observableArrayList();
        for (List<Object> row : result.rows()) {
            ObservableList<String> r = FXCollections.observableArrayList();
            for (Object cell : row) {
                r.add(cell == null ? "" : cell.toString());
            }
            data.add(r);
        }
        grid.setItems(data);

        long from = data.isEmpty() ? 0 : offset + 1;
        long to = offset + data.size();
        statusLabel.setText("第 " + from + "–" + to + " 行" + (hasMore ? "（还有更多）" : ""));
        statusLabel.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 12px;");
        prevBtn.setDisable(offset == 0);
        nextBtn.setDisable(!hasMore);
    }

    private void setControlsDisabled(boolean disabled) {
        reloadBtn.setDisable(disabled);
        prevBtn.setDisable(disabled);
        nextBtn.setDisable(disabled);
        filterField.setDisable(disabled);
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
