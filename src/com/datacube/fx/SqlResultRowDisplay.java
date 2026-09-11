package com.datacube.fx;

import com.datacube.sqleditor.result.CompactResultText;
import com.datacube.sqleditor.result.ResultValueFormatter;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javafx.collections.ObservableList;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;

/** Per-editor, non-persistent cell presentation; leaves table data and view identity intact. */
final class SqlResultRowDisplay implements AutoCloseable {
    private final TableView<ObservableList<Object>> table;
    private final CheckBox toggle = new CheckBox("紧凑行");
    private boolean available;
    private boolean compact;
    private boolean closed;

    SqlResultRowDisplay(TableView<ObservableList<Object>> table, BooleanSupplier changesAllowed) {
        this.table = Objects.requireNonNull(table);
        Objects.requireNonNull(changesAllowed);
        toggle.setId("sql-result-compact-rows");
        toggle.setTooltip(new Tooltip("仅当前标签：每格预览首行或前 256 UTF-16 单元，↵ / … 表示省略。"
                + "使用“查看单元格”阅读内容；复制和导出不使用预览文本。"));
        toggle.setOnAction(event -> {
            if (closed || !available || toggle.isDisabled() || table.isDisabled() || !changesAllowed.getAsBoolean()) {
                toggle.setSelected(compact);
                return;
            }
            compact = toggle.isSelected();
            table.refresh();
        });
        refreshAvailability(false);
    }

    CheckBox getNode() { return toggle; }

    void refreshAvailability(boolean available) {
        this.available = available;
        toggle.setDisable(closed || !available);
    }

    TableCell<ObservableList<Object>, Object> createCell() {
        return new TableCell<>() {
            @Override protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                String text = empty ? null : ResultValueFormatter.format(item);
                setText(text == null || !compact ? text : CompactResultText.preview(text));
                setGraphic(null);
                setWrapText(false);
            }
        };
    }

    @Override public void close() {
        closed = true;
        toggle.setDisable(true);
    }
}
