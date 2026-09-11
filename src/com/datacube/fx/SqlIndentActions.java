package com.datacube.fx;

import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import com.datacube.sqleditor.SqlLineIndent;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.event.EventHandler;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.CodeArea;

/** Explicit text-only actions; Tab remains owned by completion/editor navigation. */
final class SqlIndentActions implements AutoCloseable {
    private final CodeArea editor;
    private final ShortcutSettings shortcuts;
    private final BooleanSupplier canEdit;
    private final Runnable beforeEdit;
    private final Consumer<String> feedback;
    private final Button indent = new Button("缩进");
    private final Button outdent = new Button("反缩进");
    private final Runnable shortcutListener = this::refreshHints;
    private final EventHandler<KeyEvent> keyHandler = this::onKey;
    private boolean closed;

    SqlIndentActions(CodeArea editor, ShortcutSettings shortcuts, BooleanSupplier canEdit,
                     Runnable beforeEdit, Consumer<String> feedback) {
        this.editor = editor;
        this.shortcuts = shortcuts;
        this.canEdit = canEdit;
        this.beforeEdit = beforeEdit;
        this.feedback = feedback;
        indent.setId("sql-indent");
        outdent.setId("sql-outdent");
        indent.setOnAction(event -> apply(false));
        outdent.setOnAction(event -> apply(true));
        indent.disableProperty().bind(editor.editableProperty().not().or(editor.disabledProperty()));
        outdent.disableProperty().bind(editor.editableProperty().not().or(editor.disabledProperty()));
        editor.addEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
        shortcuts.addChangeListener(shortcutListener);
        refreshHints();
    }

    Button indentButton() { return indent; }
    Button outdentButton() { return outdent; }

    private void refreshHints() {
        indent.setTooltip(new Tooltip("缩进当前行或选中行：4 个空格（"
                + shortcuts.get(ShortcutAction.SQL_INDENT).getDisplayText() + "）；可撤销，不自动保存文件或执行 SQL"));
        outdent.setTooltip(new Tooltip("反缩进：移除一个行首 Tab 或最多 4 个空格（"
                + shortcuts.get(ShortcutAction.SQL_OUTDENT).getDisplayText() + "）；可撤销"));
    }

    private void onKey(KeyEvent event) {
        if (shortcuts.get(ShortcutAction.SQL_INDENT).match(event)) {
            event.consume(); apply(false);
        } else if (shortcuts.get(ShortcutAction.SQL_OUTDENT).match(event)) {
            event.consume(); apply(true);
        }
    }

    private void apply(boolean reverse) {
        if (closed || !canEdit.getAsBoolean() || !editor.isEditable() || editor.isDisabled()) return;
        SqlLineIndent.Plan plan;
        try {
            plan = SqlLineIndent.plan(editor.getText(), editor.getAnchor(), editor.getCaretPosition(), reverse);
        } catch (IllegalArgumentException tooLarge) {
            feedback.accept("缩进未应用：文本或结果上限 8 Mi 字符，单次最多 10,000 行，请缩小范围。");
            return;
        }
        // Resource admission can close from a background thread while a large plan is built.
        if (closed || !canEdit.getAsBoolean() || !editor.isEditable() || editor.isDisabled()) return;
        if (plan.edits().isEmpty()) {
            feedback.accept("所选行没有可移除的缩进，未修改 SQL。");
            editor.requestFocus();
            return;
        }
        beforeEdit.run();
        editor.getUndoManager().preventMerge();
        var batch = editor.createMultiChange(plan.edits().size());
        for (int i = plan.edits().size() - 1; i >= 0; i--) {
            var edit = plan.edits().get(i);
            batch.replaceTextAbsolutely(edit.start(), edit.end(), edit.text());
        }
        batch.commit();
        editor.getUndoManager().preventMerge();
        editor.selectRange(plan.anchor(), plan.caret());
        editor.requestFocus();
        editor.requestFollowCaret();
        feedback.accept("已" + (reverse ? "反缩进" : "缩进") + " " + plan.edits().size()
                + " 行，可撤销；未自动保存文件或执行 SQL。");
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        editor.removeEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
        shortcuts.removeChangeListener(shortcutListener);
        indent.disableProperty().unbind();
        outdent.disableProperty().unbind();
        indent.setDisable(true);
        outdent.setDisable(true);
    }
}
