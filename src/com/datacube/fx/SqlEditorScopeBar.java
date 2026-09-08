package com.datacube.fx;

import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import com.datacube.sqleditor.SqlExecutionRange;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/** Read-only editor context; never moves a caret, edits text, or admits an execution. */
final class SqlEditorScopeBar implements AutoCloseable {
    private final CodeArea editor;
    private final ReadOnlyStringWrapper executeLabel = new ReadOnlyStringWrapper();
    private final ShortcutSettings shortcuts;
    private final FlowPane root = new FlowPane(12, 4);
    private final Label position = new Label();
    private final Label scope = new Label();
    private final InvalidationListener editorListener = ignored -> refresh();
    private final Runnable shortcutsListener = () -> {
        if (Platform.isFxApplicationThread()) refresh();
        else Platform.runLater(this::refresh);
    };
    private boolean closed;

    SqlEditorScopeBar(CodeArea editor, ShortcutSettings shortcuts) {
        this.editor = editor;
        this.shortcuts = shortcuts;
        root.setId("sql-editor-scope-bar");
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(4));
        root.setMinHeight(Region.USE_PREF_SIZE);
        position.setId("sql-editor-position");
        position.setMinWidth(Region.USE_PREF_SIZE);
        position.setTooltip(new Tooltip("行列从 1 开始；列和选区长度按 UTF-16 单元计，Tab 为一个单元。"));
        scope.setId("sql-execution-scope");
        scope.setWrapText(true);
        scope.setMinWidth(0);
        scope.setMinHeight(Region.USE_PREF_SIZE);
        scope.setTooltip(new Tooltip("提示下一次执行的文本范围，不表示已执行；执行计划仍只处理范围内第一条语句。"));
        root.getChildren().addAll(position, scope);
        editor.textProperty().addListener(editorListener);
        editor.selectionProperty().addListener(editorListener);
        editor.caretPositionProperty().addListener(editorListener);
        shortcuts.addChangeListener(shortcutsListener);
        refresh();
    }

    Parent getNode() { return root; }
    ReadOnlyStringProperty executeLabelProperty() { return executeLabel.getReadOnlyProperty(); }

    private void refresh() {
        if (closed) return;
        String text = editor.getText();
        // RichTextFX may notify text before selection/caret settle during a document replacement.
        int start = Math.min(editor.getSelection().getStart(), text.length());
        int end = Math.min(editor.getSelection().getEnd(), text.length());
        var range = SqlExecutionRange.resolve(text, start, end);
        int caret = Math.min(editor.getCaretPosition(), editor.getLength());
        var at = editor.offsetToPosition(caret, Bias.Forward);
        position.setText("行 " + (at.getMajor() + 1) + " · 列 " + (at.getMinor() + 1)
                + (end > start ? " · 选中 " + (end - start) : ""));
        String label = range.selection() ? "执行选中" : "执行全部";
        executeLabel.set(label + " (" + shortcuts.get(ShortcutAction.SQL_EXECUTE).getDisplayText() + ")");
        scope.setText(range.selection() ? "执行范围：选中内容"
                : end > start ? "执行范围：全部 SQL（选区仅含空白）" : "执行范围：全部 SQL");
        position.setAccessibleText(position.getText());
        scope.setAccessibleText(scope.getText());
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        editor.textProperty().removeListener(editorListener);
        editor.selectionProperty().removeListener(editorListener);
        editor.caretPositionProperty().removeListener(editorListener);
        shortcuts.removeChangeListener(shortcutsListener);
    }
}
