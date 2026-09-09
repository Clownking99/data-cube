package com.datacube.fx;

import java.util.function.BooleanSupplier;
import javafx.beans.InvalidationListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.CodeArea;

/** Synchronous, local navigation only: never edits or submits SQL. FX-thread owned. */
final class SqlGoToLineBar implements AutoCloseable {
    private final CodeArea editor;
    private final ObservableList<?> paragraphs;
    private final BooleanSupplier navigationAllowed;
    private final Runnable beforeShow;
    private final VBox root = new VBox(4);
    private final TextField line = new TextField();
    private final Label status = new Label();
    private final Button navigate = new Button("定位");
    private final Button launcher = new Button("跳转行…");
    private final InvalidationListener paragraphsListener = ignored -> refresh();
    private boolean closed;

    SqlGoToLineBar(CodeArea editor, BooleanSupplier navigationAllowed, Runnable beforeShow) {
        this.editor = editor;
        // Stay on JavaFX's public collection API; do not widen module access to ReactFX internals.
        this.paragraphs = editor.getParagraphs();
        this.navigationAllowed = navigationAllowed;
        this.beforeShow = beforeShow;
        root.setId("sql-go-to-line-bar");
        root.setPadding(new Insets(4));
        root.setMinHeight(Region.USE_PREF_SIZE);
        launcher.setId("sql-go-to-line");
        launcher.setMinWidth(Region.USE_PREF_SIZE);
        launcher.setOnAction(event -> show());
        line.setId("sql-go-to-line-input");
        line.setAccessibleText("目标行号");
        line.setPrefColumnCount(8);
        line.setPrefWidth(100);
        line.setMinWidth(100);
        line.setMaxWidth(100);
        line.setOnAction(event -> navigate());
        line.textProperty().addListener(ignored -> refresh());
        navigate.setId("sql-go-to-line-submit");
        navigate.setOnAction(event -> navigate());
        Button cancel = new Button("取消");
        cancel.setId("sql-go-to-line-cancel");
        cancel.setOnAction(event -> hide(true));
        Label caption = new Label("行号");
        caption.setLabelFor(line);
        FlowPane controls = new FlowPane(8, 4, caption, line, navigate, cancel);
        controls.setAlignment(Pos.CENTER_LEFT);
        status.setId("sql-go-to-line-status");
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        Label hint = new Label("定位到行首并清除选区；下次执行为全部 SQL。");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);
        root.getChildren().addAll(controls, status, hint);
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) { hide(true); event.consume(); }
        });
        paragraphs.addListener(paragraphsListener);
        hide(false);
    }

    Parent getNode() { return root; }
    Button launcher() { return launcher; }

    void show() {
        if (!canNavigate()) return;
        beforeShow.run();
        root.setVisible(true);
        root.setManaged(true);
        line.setText(Integer.toString(editor.getCurrentParagraph() + 1));
        refresh();
        line.requestFocus();
        line.selectAll();
    }

    void hide(boolean focusEditor) {
        root.setVisible(false);
        root.setManaged(false);
        if (focusEditor && !closed) editor.requestFocus();
    }

    private boolean canNavigate() {
        return !closed && !editor.isDisabled() && navigationAllowed.getAsBoolean();
    }

    private void refresh() {
        if (closed || !root.isVisible()) return;
        int count = paragraphs.size();
        int target = parseLine(line.getText(), count);
        boolean allowed = canNavigate();
        navigate.setDisable(!allowed || target < 0);
        status.setText(!allowed ? "当前编辑状态暂不可定位"
                : target < 0 ? "请输入 1–" + count + " 之间的整数行号"
                : "可定位范围：1–" + count);
        status.setAccessibleText(status.getText());
    }

    private void navigate() {
        if (!root.isVisible() || !canNavigate()) { refresh(); return; }
        int target = parseLine(line.getText(), paragraphs.size());
        if (target < 0) { refresh(); return; }
        editor.moveTo(target - 1, 0);
        editor.requestFollowCaret();
        hide(true);
    }

    /** Returns a one-based line or -1. Rejects overflow without parsing an unbounded number. */
    static int parseLine(String input, int count) {
        if (input == null || count < 1) return -1;
        String value = input.trim();
        if (value.isEmpty() || value.length() > 10) return -1;
        long number = 0;
        for (int i = 0; i < value.length(); i++) {
            char digit = value.charAt(i);
            if (digit < '0' || digit > '9') return -1;
            number = number * 10 + digit - '0';
            if (number > count) return -1;
        }
        return number > 0 ? (int) number : -1;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        paragraphs.removeListener(paragraphsListener);
        launcher.setDisable(true);
        hide(false);
    }
}
