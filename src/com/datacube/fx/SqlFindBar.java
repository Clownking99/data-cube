package com.datacube.fx;

import com.datacube.fx.task.FxTaskScope;
import com.datacube.sqleditor.SqlTextSearch;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.fxmisc.richtext.CodeArea;

/** Per-editor, read-only find UI. Background completions never edit the script. */
final class SqlFindBar implements AutoCloseable {
    @FunctionalInterface interface SearchSubmitter {
        Future<?> submit(Callable<SqlTextSearch.Result> work, Consumer<SqlTextSearch.Result> success,
                         Consumer<Throwable> failure);
    }

    private final CodeArea editor;
    private final SearchSubmitter submitter;
    private final VBox root = new VBox(4);
    private final TextField query = new TextField();
    private final CheckBox matchCase = new CheckBox("区分大小写");
    private final Button previous = new Button("上一个");
    private final Button next = new Button("下一个");
    private final Button dismiss = new Button("关闭查找");
    private final Label status = new Label();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(150));
    private final AtomicBoolean closed = new AtomicBoolean();
    private SqlTextSearch.Result result;
    private final ChangeListener<String> textListener = (obs, before, after) -> invalidate();
    private final ChangeListener<IndexRange> selectionListener = (obs, before, after) -> {
        if (result != null) renderCount(-1, "");
    };
    private volatile Future<?> active;
    private long revision;
    private boolean detached;

    SqlFindBar(CodeArea editor, FxTaskScope tasks) { this(editor, tasks::submit); }

    SqlFindBar(CodeArea editor, SearchSubmitter submitter) {
        this.editor = Objects.requireNonNull(editor);
        this.submitter = Objects.requireNonNull(submitter);
        root.setId("sql-find-bar");
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(4));
        root.setMinHeight(Region.USE_PREF_SIZE);
        FlowPane controls = new FlowPane(6, 4);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setMinHeight(Region.USE_PREF_SIZE);
        query.setId("sql-find-query");
        query.setPromptText("查找当前 SQL…");
        query.setAccessibleText("查找当前 SQL 文本");
        query.setPrefWidth(180);
        matchCase.setId("sql-find-match-case");
        previous.setId("sql-find-previous");
        next.setId("sql-find-next");
        dismiss.setId("sql-find-close");
        status.setId("sql-find-status");
        status.setWrapText(true);
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        status.setMinHeight(Region.USE_PREF_SIZE);
        controls.getChildren().addAll(query, matchCase, previous, next, dismiss);
        root.getChildren().addAll(controls, status);
        for (Region control : new Region[] {query, matchCase, previous, next, dismiss}) {
            control.setMinWidth(Region.USE_PREF_SIZE);
        }
        query.textProperty().addListener((obs, before, after) -> invalidate());
        matchCase.selectedProperty().addListener((obs, before, after) -> invalidate());
        editor.textProperty().addListener(textListener);
        editor.selectionProperty().addListener(selectionListener);
        debounce.setOnFinished(event -> scan(0));
        previous.setOnAction(event -> navigateOrScan(false));
        next.setOnAction(event -> navigateOrScan(true));
        dismiss.setOnAction(event -> hide());
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) { hide(); event.consume(); }
            else if (event.getCode() == KeyCode.ENTER && event.getTarget() == query
                    && !event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
                navigateOrScan(!event.isShiftDown());
                event.consume();
            }
        });
        root.setVisible(false);
        root.setManaged(false);
        setStatus("输入查找内容");
        previous.setDisable(true);
        next.setDisable(true);
    }

    Parent getNode() { return root; }

    void show() {
        if (closed.get()) return;
        root.setVisible(true);
        root.setManaged(true);
        String selected = editor.getSelectedText();
        if (!selected.isBlank() && selected.length() <= SqlTextSearch.MAX_QUERY_LENGTH
                && !selected.contains("\n") && !selected.contains("\r")) query.setText(selected);
        query.requestFocus();
        query.selectAll();
        invalidate();
    }

    void hide() {
        revision++;
        debounce.stop();
        cancelActive();
        result = null;
        root.setVisible(false);
        root.setManaged(false);
        if (!closed.get()) editor.requestFocus();
    }

    private void invalidate() {
        if (closed.get() || !root.isVisible()) return;
        revision++;
        cancelActive();
        result = null;
        previous.setDisable(true);
        next.setDisable(true);
        debounce.stop();
        if (query.getText().isEmpty()) setStatus("输入查找内容");
        else if (query.getLength() > SqlTextSearch.MAX_QUERY_LENGTH) setStatus("查找内容最多 1024 个字符");
        else { setStatus("正在查找…"); debounce.playFromStart(); }
    }

    private void scan(int direction) {
        if (closed.get() || !root.isVisible() || query.getText().isEmpty()
                || query.getLength() > SqlTextSearch.MAX_QUERY_LENGTH) return;
        debounce.stop();
        cancelActive();
        long expected = ++revision;
        String text = editor.getText();
        String needle = query.getText();
        boolean sensitive = matchCase.isSelected();
        try {
            active = submitter.submit(() -> SqlTextSearch.find(text, needle, sensitive), found -> {
                if (!current(expected)) return;
                result = found;
                previous.setDisable(found.matches().isEmpty());
                next.setDisable(found.matches().isEmpty());
                renderCount(-1, "");
                // A delayed Enter must not move a hidden tab or a newly focused editor.
                if (direction != 0 && query.isFocused()) navigate(direction > 0);
            }, failure -> {
                if (current(expected)) setStatus("查找失败，请重新输入后重试");
            });
            if (closed.get()) cancelActive();
        } catch (RuntimeException rejected) {
            if (current(expected)) setStatus("查找暂不可用");
        }
    }

    private boolean current(long expected) {
        return !closed.get() && root.isVisible() && revision == expected;
    }

    private void navigateOrScan(boolean forward) {
        if (closed.get() || !root.isVisible()) return;
        if (result == null) scan(forward ? 1 : -1);
        else navigate(forward);
    }

    private void navigate(boolean forward) {
        int boundary = forward ? editor.getSelection().getEnd() : editor.getSelection().getStart();
        int index = result.indexFrom(boundary, forward);
        if (index < 0) return;
        var match = result.matches().get(index);
        boolean wrapped = forward ? match.start() < boundary : match.start() >= boundary;
        editor.selectRange(match.start(), match.end());
        editor.requestFollowCaret();
        renderCount(index, wrapped ? (forward ? " · 已回到开头" : " · 已回到末尾") : "");
    }

    private void renderCount(int index, String suffix) {
        if (result.matches().isEmpty()) { setStatus("无匹配"); return; }
        String count = result.matches().size() + (result.truncated() ? "+" : "");
        setStatus((index < 0 ? "共 " + count + " 处" : (index + 1) + " / " + count) + suffix
                + (result.truncated() ? " · 仅定位前 10000 处，请缩小范围" : ""));
    }

    private void setStatus(String text) { status.setText(text); status.setAccessibleText(text); }
    private void cancelActive() { Future<?> task = active; if (task != null) task.cancel(true); }

    /** Resource phase: no JavaFX access, safe even when publication is already queued. */
    @Override public void close() { closed.set(true); cancelActive(); }

    /** FX phase: release editor listeners and timers without touching the shared task scope. */
    void detachUi() {
        if (detached) return;
        detached = true;
        close();
        hide();
        editor.textProperty().removeListener(textListener);
        editor.selectionProperty().removeListener(selectionListener);
    }
}
