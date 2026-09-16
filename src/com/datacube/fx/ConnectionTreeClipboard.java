package com.datacube.fx;

import com.datacube.service.ConnectionManager;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.TableRef;
import com.datacube.sqleditor.SqlObjectNames;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Region;
import java.util.function.Predicate;

/** Explicit clipboard writes only; no clipboard read, editor, provider or task creation. */
final class ConnectionTreeClipboard {
    record CopyResult(String message, boolean success) { }
    private final ConnectionManager connections;
    private final Predicate<String> writer;
    private final Label status = new Label();

    ConnectionTreeClipboard(ConnectionManager connections, Predicate<String> writer) {
        this.connections = connections;
        this.writer = java.util.Objects.requireNonNull(writer);
        status.setId("tree-object-copy-status");
        status.setWrapText(true);
        status.setMinWidth(0);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.setTooltip(new Tooltip("复制到系统剪贴板；其他应用或系统剪贴板同步功能可能读取名称。"));
        clearStatus();
    }

    Label getNode() { return status; }

    void clearStatus() {
        status.setText("");
        status.setVisible(false);
        status.setManaged(false);
    }

    void copy(ConnConfig connection, TableRef table) {
        CopyResult result = copyResult(connection, table);
        showStatus(result.message(), result.success());
    }

    /** The caller owns feedback placement; writing does not change the tree's label. */
    CopyResult copyResult(ConnConfig connection, TableRef table) {
        if (connection == null || connection.id() == null || connection.id().isBlank()
                || !connection.equals(connections.config(connection.id()))) {
            return new CopyResult("无法复制：所选连接已变更或不可用，请刷新后重试。", false);
        }
        final String text;
        try {
            text = SqlObjectNames.qualifiedName(connection.type(), table);
        } catch (IllegalArgumentException invalid) {
            return new CopyResult("无法复制：" + invalid.getMessage(), false);
        }
        boolean written;
        try { written = writer.test(text); }
        catch (RuntimeException unavailable) { written = false; }
        return new CopyResult(written ? "已复制限定名称；粘贴前请确认目标连接。"
                : "复制失败：无法写入系统剪贴板，请重试。", written);
    }

    private void showStatus(String text, boolean success) {
        status.setText(text);
        status.setStyle("-fx-font-size: 11px; -fx-text-fill: "
                + (success ? "-brand-fg-muted;" : "-status-error;"));
        status.setVisible(true);
        status.setManaged(true);
    }

    static boolean writeSystemClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        return Clipboard.getSystemClipboard().setContent(content);
    }
}
