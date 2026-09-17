package com.datacube.fx;

import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import com.datacube.sqleditor.SqlMoveLines;
import com.datacube.sqleditor.SqlMoveLines.Direction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.CodeArea;

/** Editor-local line movement with one undo step, unchanged physical separators and no clipboard. */
final class SqlMoveLinesActions implements AutoCloseable {
    private final CodeArea editor;
    private final ShortcutSettings shortcuts;
    private final BooleanSupplier canEdit;
    private final Runnable beforeEdit;
    private final Consumer<Runnable> editWithoutSuggestions;
    private final Consumer<String> feedback;
    private final Button up = new Button("上移行"), down = new Button("下移行");
    private final EventHandler<KeyEvent> keyHandler = this::onKey;
    private final Runnable shortcutListener = () -> {
        if (Platform.isFxApplicationThread()) refreshHints(); else Platform.runLater(this::refreshHints);
    };
    private boolean closed;

    SqlMoveLinesActions(CodeArea editor, ShortcutSettings shortcuts, BooleanSupplier canEdit,
                        Runnable beforeEdit, Consumer<Runnable> editWithoutSuggestions, Consumer<String> feedback) {
        this.editor = editor; this.shortcuts = shortcuts; this.canEdit = canEdit;
        this.beforeEdit = beforeEdit; this.editWithoutSuggestions = editWithoutSuggestions; this.feedback = feedback;
        up.setId("sql-move-lines-up"); down.setId("sql-move-lines-down");
        up.setAccessibleText("上移当前行或选中行"); down.setAccessibleText("下移当前行或选中行");
        up.setOnAction(event -> apply(Direction.UP)); down.setOnAction(event -> apply(Direction.DOWN));
        up.disableProperty().bind(editor.editableProperty().not().or(editor.disabledProperty()));
        down.disableProperty().bind(editor.editableProperty().not().or(editor.disabledProperty()));
        editor.addEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
        shortcuts.addChangeListener(shortcutListener); refreshHints();
    }

    Button upButton() { return up; }
    Button downButton() { return down; }

    private void refreshHints() {
        if (closed) return;
        up.setTooltip(hint("上移", ShortcutAction.SQL_MOVE_LINES_UP));
        down.setTooltip(hint("下移", ShortcutAction.SQL_MOVE_LINES_DOWN));
    }

    private Tooltip hint(String direction, ShortcutAction action) {
        return new Tooltip(direction + "当前行或选中整行（" + shortcuts.get(action).getDisplayText()
                + "），保留光标/选区，可一次撤销；原换行保留在分隔位置。只编辑文本，不自动保存或执行 SQL，请检查语句顺序。");
    }

    private void onKey(KeyEvent event) {
        if (shortcuts.get(ShortcutAction.SQL_MOVE_LINES_UP).match(event)) { event.consume(); apply(Direction.UP); }
        else if (shortcuts.get(ShortcutAction.SQL_MOVE_LINES_DOWN).match(event)) { event.consume(); apply(Direction.DOWN); }
    }

    private void apply(Direction direction) {
        if (!allowed()) return;
        String text = editor.getText(); int anchor = editor.getAnchor(), caret = editor.getCaretPosition();
        SqlMoveLines.Plan plan;
        try { plan = SqlMoveLines.plan(text, anchor, caret, direction); }
        catch (IllegalArgumentException limit) {
            feedback.accept("移动行未应用：全文最多 8 Mi 字符，单次最多 10,000 行，请缩小范围。"); return;
        }
        if (!current(text, anchor, caret)) return;
        if (!plan.movable()) {
            feedback.accept("已到" + (direction == Direction.UP ? "首行" : "末行") + "，没有移动 SQL。");
            editor.requestFocus(); return;
        }
        beforeEdit.run();
        if (!current(text, anchor, caret)) return;
        editWithoutSuggestions.accept(() -> {
            if (!current(text, anchor, caret)) return;
            if (!plan.edits().isEmpty()) {
                editor.getUndoManager().preventMerge();
                var batch = editor.createMultiChange(plan.edits().size());
                for (int i = plan.edits().size() - 1; i >= 0; i--) {
                    var edit = plan.edits().get(i);
                    batch.replaceTextAbsolutely(edit.start(), edit.end(), edit.text());
                }
                batch.commit(); editor.getUndoManager().preventMerge();
            }
            editor.selectRange(plan.anchor(), plan.caret()); editor.requestFocus(); editor.requestFollowCaret();
            feedback.accept("已" + (direction == Direction.UP ? "上移" : "下移") + " " + plan.lines() + " 行"
                    + (plan.edits().isEmpty() ? "，正文相同，仅移动光标/选区" : "，可一次撤销")
                    + "；未自动保存或执行 SQL。");
        });
    }

    private boolean current(String text, int anchor, int caret) {
        return allowed() && text.equals(editor.getText()) && editor.getAnchor() == anchor && editor.getCaretPosition() == caret;
    }
    private boolean allowed() { return !closed && canEdit.getAsBoolean() && editor.isEditable() && !editor.isDisabled(); }

    @Override public void close() {
        if (closed) return;
        closed = true; editor.removeEventFilter(KeyEvent.KEY_PRESSED, keyHandler); shortcuts.removeChangeListener(shortcutListener);
        up.disableProperty().unbind(); down.disableProperty().unbind(); up.setDisable(true); down.setDisable(true);
    }
}
