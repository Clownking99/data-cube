package com.datacube.fx;

import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import com.datacube.sqleditor.SqlFormatScope;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.event.EventHandler;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.CodeArea;

/** Editor-scoped formatting, with explicit range, snapshot guards and one undo boundary. */
final class SqlFormatAction implements AutoCloseable {
    @FunctionalInterface interface Planner { SqlFormatScope.Plan plan(String text, int anchor, int caret); }
    private final CodeArea editor;
    private final ShortcutSettings shortcuts;
    private final BooleanSupplier canEdit;
    private final Runnable beforeEdit;
    private final Runnable afterEdit;
    private final Consumer<String> feedback;
    private final Planner planner;
    private final Consumer<Runnable> editWithoutSuggestions;
    private final Button button = new Button();
    private final Tooltip hint = new Tooltip();
    private final EventHandler<KeyEvent> keyHandler = this::onKey;
    private final InvalidationListener selectionListener = ignored -> refreshHint();
    private final InvalidationListener availabilityListener = ignored -> refreshAvailability();
    private final Runnable shortcutListener = () -> {
        if (Platform.isFxApplicationThread()) refreshHint(); else Platform.runLater(this::refreshHint);
    };
    private boolean closed;
    private boolean busy;

    SqlFormatAction(CodeArea editor, ShortcutSettings shortcuts, BooleanSupplier canEdit,
                    Runnable beforeEdit, Runnable afterEdit, Consumer<String> feedback) {
        this(editor, shortcuts, canEdit, beforeEdit, afterEdit, feedback, SqlFormatScope::plan, Runnable::run);
    }

    SqlFormatAction(CodeArea editor, ShortcutSettings shortcuts, BooleanSupplier canEdit,
                    Runnable beforeEdit, Runnable afterEdit, Consumer<String> feedback, Consumer<Runnable> editWithoutSuggestions) {
        this(editor, shortcuts, canEdit, beforeEdit, afterEdit, feedback, SqlFormatScope::plan, editWithoutSuggestions);
    }

    SqlFormatAction(CodeArea editor, ShortcutSettings shortcuts, BooleanSupplier canEdit,
                    Runnable beforeEdit, Runnable afterEdit, Consumer<String> feedback, Planner planner) {
        this(editor, shortcuts, canEdit, beforeEdit, afterEdit, feedback, planner, Runnable::run);
    }

    private SqlFormatAction(CodeArea editor, ShortcutSettings shortcuts, BooleanSupplier canEdit,
                    Runnable beforeEdit, Runnable afterEdit, Consumer<String> feedback, Planner planner, Consumer<Runnable> editWithoutSuggestions) {
        this.editor = editor; this.shortcuts = shortcuts; this.canEdit = canEdit;
        this.beforeEdit = beforeEdit; this.afterEdit = afterEdit; this.feedback = feedback; this.planner = planner;
        this.editWithoutSuggestions = editWithoutSuggestions;
        button.setId("sql-format"); button.setTooltip(hint); button.setOnAction(event -> apply());
        editor.selectionProperty().addListener(selectionListener);
        editor.editableProperty().addListener(availabilityListener);
        editor.disabledProperty().addListener(availabilityListener);
        editor.addEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
        shortcuts.addChangeListener(shortcutListener); refreshHint(); refreshAvailability();
    }

    Button button() { return button; }

    void setBusy(boolean value) { busy = value; refreshAvailability(); }

    void refreshAvailability() { button.setDisable(!allowed()); }

    private void onKey(KeyEvent event) {
        if (shortcuts.get(ShortcutAction.SQL_FORMAT).match(event)) { event.consume(); apply(); }
    }

    private void refreshHint() {
        if (closed) return;
        boolean selection = editor.getAnchor() != editor.getCaretPosition();
        button.setText(selection ? "美化选中" : "美化全文"); button.setAccessibleText(button.getText());
        hint.setText(button.getText() + "（" + shortcuts.get(ShortcutAction.SQL_FORMAT).getDisplayText()
                + "），空白选区不改全文；一次撤销。单次最多 256 Ki 字符。建议选完整语句，执行前检查结果；不自动保存或执行 SQL。");
    }

    void apply() {
        if (!allowed()) return;
        String text = editor.getText(); int anchor = editor.getAnchor(), caret = editor.getCaretPosition();
        SqlFormatScope.Plan plan;
        try { plan = planner.plan(text, anchor, caret); }
        catch (IllegalArgumentException limit) {
            feedback.accept("美化未应用：单次范围最多 256 Ki 字符，原文及结果最多 8 Mi 字符，请缩小范围。"); return;
        } catch (RuntimeException failure) {
            feedback.accept("美化未应用，请检查选中的 SQL 文本；原文未替换。"); return;
        }
        if (!current(text, anchor, caret)) return;
        if (!plan.changed()) {
            feedback.accept(plan.blank() ? "美化未应用：范围内只有空白，不会扩大到全文。" : "格式未变化，无需美化。"); return;
        }
        beforeEdit.run();
        if (!current(text, anchor, caret)) return;
        editWithoutSuggestions.accept(() -> {
            editor.getUndoManager().preventMerge();
            editor.replaceText(plan.start(), plan.end(), plan.replacement());
            editor.getUndoManager().preventMerge();
            editor.selectRange(plan.anchor(), plan.caret());
        });
        afterEdit.run();
        editor.requestFocus(); editor.requestFollowCaret();
        feedback.accept((plan.selection() ? "已美化选中内容" : "已美化全文") + "，可一次撤销；未自动保存或执行 SQL。");
    }

    private boolean current(String text, int anchor, int caret) {
        return allowed() && text.equals(editor.getText()) && editor.getAnchor() == anchor && editor.getCaretPosition() == caret;
    }

    private boolean allowed() {
        return !closed && !busy && canEdit.getAsBoolean() && editor.isEditable() && !editor.isDisabled();
    }

    @Override public void close() {
        if (closed) return;
        closed = true; editor.removeEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
        editor.selectionProperty().removeListener(selectionListener);
        editor.editableProperty().removeListener(availabilityListener);
        editor.disabledProperty().removeListener(availabilityListener);
        shortcuts.removeChangeListener(shortcutListener); button.setDisable(true);
    }
}
