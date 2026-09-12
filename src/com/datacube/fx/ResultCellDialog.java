package com.datacube.fx;

import com.datacube.sqleditor.result.ResultCellPreview;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

/** Text is deliberately rendered as inert, read-only content, never markup or executable SQL. */
final class ResultCellDialog extends Dialog<Void> {
    ResultCellDialog(Window owner, ResultCellPreview snapshot) {
        setTitle("查看单元格（只读）");
        setHeaderText(null);
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
            initModality(Modality.WINDOW_MODAL);
            if (owner.getScene() != null)
                getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        Label identity = label("result-cell-identity", "当前显示第 " + snapshot.displayRow()
                + " 行 · 结果第 " + snapshot.sourceRow() + " 行 · 原列 " + snapshot.column()
                + "：" + snapshot.label() + "\n类型：" + snapshot.type() + "（JDBC " + snapshot.jdbcType() + "）"
                + (snapshot.metadataTruncated() ? "\n列名或类型名过长，已省略部分显示。" : ""));
        String kind = snapshot.nullValue() ? "NULL（数据库空值）"
                : snapshot.emptyString() ? "空字符串" : "非 NULL 值";
        Label summary = label("result-cell-summary", kind + " · 显示表示长度 "
                + snapshot.displayLength() + " UTF-16 单元"
                + (snapshot.truncated() ? "\n查看内容已截断：仅显示前 " + snapshot.text().length() + " 个单元。" : ""));
        Label boundary = label("result-cell-boundary", "只读：打开时的已加载快照，不重新查询或修改数据。"
                + (snapshot.displayOnly() ? "\n此值是特殊类型的显示表示，可能已在读取时截断，不代表完整原值。" : ""));
        TextArea value = new TextArea(snapshot.text());
        value.setId("result-cell-text");
        value.setAccessibleText("只读单元格内容");
        value.setEditable(false);
        value.setWrapText(true);
        value.setPrefRowCount(14);
        value.setMinHeight(100);
        value.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");
        CheckBox wrap = new CheckBox("自动换行");
        wrap.setId("result-cell-wrap");
        wrap.setSelected(true);
        wrap.selectedProperty().addListener((obs, before, after) -> value.setWrapText(after));
        ScrollPane metadata = new ScrollPane(identity);
        metadata.setId("result-cell-metadata");
        metadata.setFitToWidth(true);
        metadata.setPrefViewportHeight(60);
        metadata.setMinHeight(45);
        metadata.setMaxHeight(100);
        var find = new ResultCellFindBar(value);
        VBox content = new VBox(8, metadata, summary, boundary, find, wrap, value);
        content.setId("result-cell-content");
        content.setPrefWidth(640);
        content.setMinWidth(320);
        VBox.setVgrow(value, Priority.ALWAYS);
        getDialogPane().setContent(content);
        var closeType = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().add(closeType);
        Button close = (Button) getDialogPane().lookupButton(closeType);
        close.setId("result-cell-close");
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) { event.consume(); close.fire(); }
            else if (event.getCode() == KeyCode.F && event.isShortcutDown() && !event.isAltDown() && !event.isShiftDown()) {
                event.consume(); find.focusQuery();
            } else if (!event.isControlDown() && !event.isAltDown() && !event.isMetaDown()
                    && (event.getCode() == KeyCode.F3 || event.getCode() == KeyCode.ENTER && find.queryFocused())) {
                event.consume(); find.navigate(!event.isShiftDown());
            }
        });
        // The owning SQL pane installs its own onHidden callback; keep cleanup independent of it.
        showingProperty().addListener((obs, before, showing) -> { if (!showing) find.close(); });
        setOnShown(event -> { value.positionCaret(0); value.requestFocus(); });
    }

    private static Label label(String id, String text) {
        Label label = new Label(text);
        label.setId(id);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }
}
