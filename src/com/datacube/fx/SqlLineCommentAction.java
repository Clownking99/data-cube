package com.datacube.fx;

import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import com.datacube.sqleditor.SqlLineComment;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import org.fxmisc.richtext.CodeArea;

/** Shared button/shortcut operation; no SQL execution or file I/O. */
final class SqlLineCommentAction implements AutoCloseable {
    private final CodeArea editor;
    private final ShortcutSettings shortcuts;
    private final BooleanSupplier canEdit;
    private final Runnable beforeEdit;
    private final Consumer<String> feedback;
    private final Button button = new Button("行注释");
    private final Runnable shortcutListener = () -> {
        if (Platform.isFxApplicationThread()) refreshHint();
        else Platform.runLater(this::refreshHint);
    };
    private boolean closed;

    SqlLineCommentAction(CodeArea editor, ShortcutSettings shortcuts, BooleanSupplier canEdit,
                         Runnable beforeEdit, Consumer<String> feedback) {
        this.editor = editor; this.shortcuts = shortcuts; this.canEdit = canEdit;
        this.beforeEdit = beforeEdit; this.feedback = feedback;
        button.setId("sql-line-comment"); button.setAccessibleText("切换当前行或选中行注释");
        button.setOnAction(event -> apply());
        button.disableProperty().bind(editor.editableProperty().not().or(editor.disabledProperty()));
        shortcuts.addChangeListener(shortcutListener); refreshHint();
    }

    Button button() { return button; }

    private void refreshHint() {
        if (closed) return;
        button.setTooltip(new Tooltip("切换当前行或选中行的 -- 注释（"
                + shortcuts.get(ShortcutAction.SQL_LINE_COMMENT).getDisplayText()
                + "）；保留选区方向，添加时包含首行注释标记，可撤销。只编辑文本，不自动保存文件或执行 SQL；多行字符串内也会改文本。"));
    }

    void apply() {
        if (!allowed()) return;
        SqlLineComment.Plan plan;
        try { plan = SqlLineComment.plan(editor.getText(), editor.getAnchor(), editor.getCaretPosition()); }
        catch (IllegalArgumentException limit) {
            feedback.accept("行注释未应用：文本或结果上限 8 Mi 字符，单次最多 10,000 行，请缩小范围。");
            return;
        }
        if (!allowed()) return;
        if (plan.edits().isEmpty()) {
            feedback.accept("所选行均为空白，未修改 SQL。"); editor.requestFocus(); return;
        }
        beforeEdit.run();
        editor.getUndoManager().preventMerge();
        var batch = editor.createMultiChange(plan.edits().size());
        for (int i = plan.edits().size() - 1; i >= 0; i--) {
            var edit = plan.edits().get(i); batch.replaceTextAbsolutely(edit.start(), edit.end(), edit.text());
        }
        batch.commit(); editor.getUndoManager().preventMerge();
        editor.selectRange(plan.anchor(), plan.caret()); editor.requestFocus(); editor.requestFollowCaret();
        feedback.accept("已" + (plan.uncomment() ? "取消" : "添加") + " " + plan.edits().size()
                + " 行注释，可撤销；" + (!plan.uncomment() && plan.anchor() != plan.caret() ? "选区已包含首行注释标记；" : "")
                + "未自动保存文件或执行 SQL。");
    }

    private boolean allowed() { return !closed && canEdit.getAsBoolean() && editor.isEditable() && !editor.isDisabled(); }

    @Override public void close() {
        if (closed) return;
        closed = true; shortcuts.removeChangeListener(shortcutListener);
        button.disableProperty().unbind(); button.setDisable(true);
    }
}
