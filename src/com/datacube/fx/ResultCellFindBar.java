package com.datacube.fx;

import com.datacube.sqleditor.SqlTextSearch;
import java.util.List;
import javafx.beans.InvalidationListener;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Local literal navigation over the bounded, inert text shown by ResultCellDialog. */
final class ResultCellFindBar extends VBox {
    private final TextArea value;
    private final TextField query = new TextField();
    private final CheckBox matchCase = new CheckBox("区分大小写");
    private final Button previous = new Button("上一个");
    private final Button next = new Button("下一个");
    private final Label status = new Label();
    private SqlTextSearch.Result result = new SqlTextSearch.Result(List.of(), false);
    private final InvalidationListener searchListener = obs -> refresh();
    private final InvalidationListener selectionListener = obs -> updateStatus();
    private boolean closed;

    ResultCellFindBar(TextArea value) {
        this.value = value;
        setSpacing(4);
        query.setId("result-cell-find-query");
        query.setPromptText("查找已显示内容（Ctrl+F）");
        query.setAccessibleText("查找单元格已显示内容");
        query.setMinWidth(60);
        query.setTooltip(new Tooltip("字面查找，保留空格；Enter / Shift+Enter 或 F3 / Shift+F3 定位，首尾循环。"));
        previous.setId("result-cell-find-previous");
        next.setId("result-cell-find-next");
        previous.setMinWidth(Region.USE_PREF_SIZE);
        next.setMinWidth(Region.USE_PREF_SIZE);
        previous.setOnAction(event -> navigate(false));
        next.setOnAction(event -> navigate(true));
        var input = new HBox(6, query, previous, next);
        HBox.setHgrow(query, Priority.ALWAYS);
        matchCase.setId("result-cell-find-case");
        status.setId("result-cell-find-status");
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        var options = new FlowPane(12, 4, matchCase, status);
        status.maxWidthProperty().bind(options.widthProperty());
        getChildren().addAll(input, options);
        query.textProperty().addListener(searchListener);
        matchCase.selectedProperty().addListener(searchListener);
        value.textProperty().addListener(searchListener);
        value.selectionProperty().addListener(selectionListener);
        refresh();
    }

    void focusQuery() {
        if (closed) return;
        query.requestFocus();
        query.selectAll();
    }

    boolean queryFocused() { return query.getScene() != null && query.getScene().getFocusOwner() == query; }

    void navigate(boolean forward) {
        if (closed || result.matches().isEmpty()) return;
        var selection = value.getSelection();
        int index = result.indexFrom(forward ? selection.getEnd() : selection.getStart(), forward);
        var match = result.matches().get(index);
        // TextArea selects and scrolls without moving keyboard focus out of the find field.
        value.selectRange(match.start(), match.end());
        updateStatus();
    }

    private void refresh() {
        if (closed) return;
        result = query.getLength() > SqlTextSearch.MAX_QUERY_LENGTH
                ? new SqlTextSearch.Result(List.of(), false)
                : SqlTextSearch.find(value.getText(), query.getText(), matchCase.isSelected());
        previous.setDisable(result.matches().isEmpty());
        next.setDisable(result.matches().isEmpty());
        updateStatus();
    }

    private void updateStatus() {
        if (closed) return;
        String message;
        if (query.getLength() > SqlTextSearch.MAX_QUERY_LENGTH)
            message = "查找词最多 " + SqlTextSearch.MAX_QUERY_LENGTH + " 个 UTF-16 单元，请缩短";
        else if (query.getText().isEmpty()) message = "输入查找文本";
        else if (result.matches().isEmpty()) message = "没有匹配";
        else {
            var selection = value.getSelection();
            int selected = -1;
            for (int i = 0; i < result.matches().size(); i++) {
                var match = result.matches().get(i);
                if (match.start() == selection.getStart() && match.end() == selection.getEnd()) { selected = i; break; }
            }
            message = selected < 0 ? "匹配 " + result.matches().size() + " 处"
                    : "第 " + (selected + 1) + " / " + result.matches().size() + " 处";
            if (result.truncated()) message += " · 仅定位前 " + SqlTextSearch.MAX_MATCHES + " 处，请缩小查找范围";
        }
        status.setText(message + " · 仅查找已显示内容");
    }

    void close() {
        if (closed) return;
        closed = true;
        query.textProperty().removeListener(searchListener);
        matchCase.selectedProperty().removeListener(searchListener);
        value.textProperty().removeListener(searchListener);
        value.selectionProperty().removeListener(selectionListener);
        status.maxWidthProperty().unbind();
        result = new SqlTextSearch.Result(List.of(), false);
        setDisable(true);
        previous.setDisable(true);
        next.setDisable(true);
    }
}
