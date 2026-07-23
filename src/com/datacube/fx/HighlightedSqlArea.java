package com.datacube.fx;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

/**
 * {@link CodeArea} 小工厂：统一装配 SQL 高亮编辑区所需的字体 / 行号 / 高亮样式表，
 * 并在文本变化时单遍重算 {@link SqlHighlighter} 样式区间。供 {@link DdlViewPane}
 * 与 {@link ObjectEditorPane} 复用，避免多处重复接线。
 */
final class HighlightedSqlArea {

    private HighlightedSqlArea() {}

    /**
     * @param editable 是否可编辑（{@code false} 用于只读 DDL 查看）
     * @return 已装配行号、等宽字体、高亮样式表与文本变化重算高亮的 {@link CodeArea}
     */
    static CodeArea create(boolean editable) {
        CodeArea area = new CodeArea();
        area.getStyleClass().add("code-area");
        area.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        area.setEditable(editable);
        area.textProperty().addListener((obs, o, n) -> area.setStyleSpans(0, SqlHighlighter.compute(n)));
        var css = HighlightedSqlArea.class.getResource("/com/datacube/fx/sql-highlight.css");
        if (css != null) area.getStylesheets().add(css.toExternalForm());
        return area;
    }
}
