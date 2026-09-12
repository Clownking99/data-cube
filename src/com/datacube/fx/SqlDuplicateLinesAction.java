package com.datacube.fx;

import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import com.datacube.sqleditor.SqlDuplicateLines;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.CodeArea;

/** Text-only duplicate action shared by the visible button and configurable editor shortcut. */
final class SqlDuplicateLinesAction implements AutoCloseable {
    private final CodeArea editor;
    private final ShortcutSettings shortcuts;
    private final BooleanSupplier canEdit;
    private final Runnable beforeEdit;
    private final Consumer<String> feedback;
    private final Button button = new Button("重复行");
    private final EventHandler<KeyEvent> keyHandler = this::onKey;
    private final Runnable shortcutListener = () -> {
        if (Platform.isFxApplicationThread()) refreshHint(); else Platform.runLater(this::refreshHint);
    };
    private boolean closed;

    SqlDuplicateLinesAction(CodeArea editor, ShortcutSettings shortcuts, BooleanSupplier canEdit,
                            Runnable beforeEdit, Consumer<String> feedback) {
        this.editor = editor; this.shortcuts = shortcuts; this.canEdit = canEdit;
        this.beforeEdit = beforeEdit; this.feedback = feedback;
        button.setId("sql-duplicate-lines"); button.setAccessibleText("向下重复当前行或选中行");
        button.setOnAction(event -> apply());
        button.disableProperty().bind(editor.editableProperty().not().or(editor.disabledProperty()));
        editor.addEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
        shortcuts.addChangeListener(shortcutListener); refreshHint();
    }

    Button button() { return button; }

    private void onKey(KeyEvent event) {
        if (shortcuts.get(ShortcutAction.SQL_DUPLICATE_LINES).match(event)) { event.consume(); apply(); }
    }

    private void refreshHint() {
        if (closed) return;
        button.setTooltip(new Tooltip("向下重复当前行或选中整行（"
                + shortcuts.get(ShortcutAction.SQL_DUPLICATE_LINES).getDisplayText()
                + "），光标/原选区移到副本；一次撤销。不使用剪贴板，不自动保存文件或执行 SQL。"));
    }

    private void apply() {
        if (!allowed()) return;
        String text = editor.getText(); int anchor = editor.getAnchor(), caret = editor.getCaretPosition();
        SqlDuplicateLines.Plan plan;
        try { plan = SqlDuplicateLines.plan(text, anchor, caret); }
        catch (IllegalArgumentException limit) {
            feedback.accept("重复行未应用：文本或结果上限 8 Mi 字符，单次最多 10,000 行，请缩小范围。"); return;
        }
        if (!allowed()) return;
        beforeEdit.run();
        // Closing admission or a callback must not apply a plan to a changed editor snapshot.
        if (!allowed() || !text.equals(editor.getText()) || editor.getAnchor() != anchor || editor.getCaretPosition() != caret) return;
        editor.getUndoManager().preventMerge();
        editor.insertText(plan.position(), plan.insertion());
        editor.getUndoManager().preventMerge();
        editor.selectRange(plan.anchor(), plan.caret()); editor.requestFocus(); editor.requestFollowCaret();
        feedback.accept("已向下重复 " + plan.lines() + " 行，光标/选区已移到副本，可撤销；未自动保存文件或执行 SQL。");
    }

    private boolean allowed() { return !closed && canEdit.getAsBoolean() && editor.isEditable() && !editor.isDisabled(); }

    @Override public void close() {
        if (closed) return;
        closed = true; editor.removeEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
        shortcuts.removeChangeListener(shortcutListener); button.disableProperty().unbind(); button.setDisable(true);
    }
}
