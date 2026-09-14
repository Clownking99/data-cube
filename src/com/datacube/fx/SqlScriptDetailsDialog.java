package com.datacube.fx;

import com.datacube.sqleditor.SqlScriptExecutionReport.Entry;
import com.datacube.sqleditor.SqlScriptExecutionReport.Text;
import com.datacube.spi.model.QueryResult;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

/** Inert display only: no execution, saving, logging, or automatic clipboard writes. */
final class SqlScriptDetailsDialog extends Dialog<Void> {
    SqlScriptDetailsDialog(Window owner, Entry entry) {
        setTitle("执行详情（只读）"); setHeaderText(null); setResizable(true);
        if (owner != null) {
            initOwner(owner); initModality(Modality.WINDOW_MODAL);
            if (owner.getScene() != null) getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        Label identity = label("sql-script-detail-identity", "语句 #" + entry.index() + " · " + entry.status()
                + " · " + entry.elapsedMillis() + "ms"
                + (entry.kind() == QueryResult.Kind.ERROR ? "" : "\n" + entry.resultDescription()));
        Label boundary = label("sql-script-detail-boundary", "只读：本次已返回的信息，不重新执行 SQL。\n"
                + "正常返回不代表事务已提交；失败、超时或取消不代表已回滚。"
                + (entry.kind() == QueryResult.Kind.QUERY ? "\n这里只记录已加载行数，不保留查询数据。" : ""));
        TextArea sql = area("sql-script-detail-sql", "只读 SQL", entry.sql().value(), 10);
        VBox content = new VBox(8, identity, boundary, label("sql-script-detail-sql-label", "SQL" + notice(entry.sql())), sql);
        VBox.setVgrow(sql, Priority.ALWAYS);
        if (entry.kind() == QueryResult.Kind.ERROR) {
            TextArea error = area("sql-script-detail-error", "只读错误信息", entry.resultDescription(), 5);
            content.getChildren().addAll(label("sql-script-detail-error-label", "错误信息" + notice(entry.error())), error);
            VBox.setVgrow(error, Priority.ALWAYS);
        }
        content.setPrefWidth(680); content.setMinWidth(320);
        getDialogPane().setContent(content);
        ButtonType closeType = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().add(closeType);
        Button close = (Button) getDialogPane().lookupButton(closeType); close.setId("sql-script-detail-close");
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) { event.consume(); close.fire(); }
        });
        setOnShown(event -> { sql.positionCaret(0); sql.requestFocus(); });
    }

    private static String notice(Text text) {
        return text.truncated() ? "（达到显示上限，仅保留前 " + text.value().length() + " 个 UTF-16 单元）" : "";
    }

    private static TextArea area(String id, String accessible, String text, int rows) {
        TextArea area = new TextArea(text); area.setId(id); area.setAccessibleText(accessible);
        area.setEditable(false); area.setWrapText(true); area.setPrefRowCount(rows); area.setMinHeight(70);
        area.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");
        return area;
    }

    private static Label label(String id, String text) {
        Label label = new Label(text); label.setId(id); label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE); label.setMaxWidth(Double.MAX_VALUE); return label;
    }
}
