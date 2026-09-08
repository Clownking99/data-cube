package com.datacube.fx;

import com.datacube.fx.task.FxTaskScope;
import com.datacube.sqleditor.SqlTextSearch;
import com.datacube.sqleditor.SqlTextReplacement;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Node;
import javafx.scene.Scene;
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

/** Per-editor literal find/replace UI; only an explicit, still-current replacement can edit SQL. */
final class SqlFindBar implements AutoCloseable {
    @FunctionalInterface interface SearchSubmitter {
        Future<?> submit(Callable<SqlTextSearch.Result> work, Consumer<SqlTextSearch.Result> success,
                         Consumer<Throwable> failure);
    }
    @FunctionalInterface interface ReplaceSubmitter {
        Future<?> submit(Callable<SqlTextReplacement.Edit> work, Consumer<SqlTextReplacement.Edit> success,
                         Consumer<Throwable> failure);
    }

    private final CodeArea editor;
    private final SearchSubmitter submitter;
    private final ReplaceSubmitter replaceSubmitter;
    private final BooleanSupplier editingAllowed;
    private final VBox root = new VBox(4);
    private final TextField query = new TextField();
    private final CheckBox matchCase = new CheckBox("区分大小写");
    private final Button previous = new Button("上一个");
    private final Button next = new Button("下一个");
    private final Button dismiss = new Button("关闭查找");
    private final Label status = new Label();
    private final Button toggleReplace = new Button("替换…");
    private final VBox replacePane = new VBox(4);
    private final TextField replacement = new TextField();
    private final Button replaceCurrent = new Button("替换当前");
    private final Button replaceAll = new Button("全部替换");
    private final Label replaceStatus = new Label("仅修改当前 SQL；空文本表示删除，可撤销，不自动保存文件或执行。");
    private final PauseTransition debounce = new PauseTransition(Duration.millis(150));
    private final AtomicBoolean closed = new AtomicBoolean();
    private SqlTextSearch.Result result;
    private boolean replacing;
    private final ChangeListener<String> textListener = (obs, before, after) -> invalidate();
    private final ChangeListener<IndexRange> selectionListener = (obs, before, after) -> {
        if (replacing) cancelReplacement();
        if (result != null) renderCount(-1, "");
        updateReplacementButtons();
    };
    private final ChangeListener<Node> focusListener = (obs, before, after) -> {
        if (replacing && !owns(after)) cancelReplacement();
    };
    private final ChangeListener<Scene> sceneListener = (obs, before, after) -> observeScene(after);
    private final ChangeListener<Boolean> editabilityListener = (obs, before, after) -> editingStateChanged();
    private Scene observedScene;
    private String resultText;
    private volatile Future<?> active;
    private long revision;
    private boolean detached;

    SqlFindBar(CodeArea editor, FxTaskScope tasks, BooleanSupplier editingAllowed) {
        this(editor, tasks::submit, tasks::submit, editingAllowed);
    }

    SqlFindBar(CodeArea editor, SearchSubmitter submitter, ReplaceSubmitter replaceSubmitter,
               BooleanSupplier editingAllowed) {
        this.editor = Objects.requireNonNull(editor);
        this.submitter = Objects.requireNonNull(submitter);
        this.replaceSubmitter = Objects.requireNonNull(replaceSubmitter);
        this.editingAllowed = Objects.requireNonNull(editingAllowed);
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
        toggleReplace.setId("sql-find-replace-toggle");
        toggleReplace.setOnAction(event -> {
            if (replacePane.isVisible()) {
                cancelReplacement();
                replacePane.setVisible(false);
                replacePane.setManaged(false);
                toggleReplace.setText("替换…");
            } else showReplace();
        });
        replacement.setId("sql-replace-text");
        replacement.setPromptText("替换为（空文本表示删除）");
        replacement.setAccessibleText("SQL 替换文本，空文本表示删除");
        replacement.setPrefWidth(220);
        replaceCurrent.setId("sql-replace-current");
        replaceAll.setId("sql-replace-all");
        replaceStatus.setId("sql-replace-status");
        replaceStatus.setWrapText(true);
        replaceStatus.setMinHeight(Region.USE_PREF_SIZE);
        replaceStatus.setMinWidth(0);
        FlowPane replaceControls = new FlowPane(6, 4, replacement, replaceCurrent, replaceAll);
        replaceControls.setMinHeight(Region.USE_PREF_SIZE);
        replacePane.setId("sql-replace-pane");
        replacePane.getChildren().addAll(replaceControls, replaceStatus);
        replacePane.setVisible(false);
        replacePane.setManaged(false);
        replacement.textProperty().addListener((obs, before, after) -> {
            cancelReplacement();
            replaceStatus.setText(after.length() > SqlTextReplacement.MAX_REPLACEMENT_LENGTH
                    ? "替换文本最多 4096 个字符，未修改 SQL。"
                    : "仅修改当前 SQL；空文本表示删除，可撤销，不自动保存文件或执行。");
            updateReplacementButtons();
        });
        replaceCurrent.setOnAction(event -> replace(false));
        replaceAll.setOnAction(event -> replace(true));
        controls.getChildren().addAll(query, matchCase, previous, next, toggleReplace, dismiss);
        root.getChildren().addAll(controls, status, replacePane);
        for (Region control : new Region[] {query, matchCase, previous, next, toggleReplace, dismiss,
                replacement, replaceCurrent, replaceAll}) {
            control.setMinWidth(Region.USE_PREF_SIZE);
        }
        query.textProperty().addListener((obs, before, after) -> invalidate());
        matchCase.selectedProperty().addListener((obs, before, after) -> invalidate());
        editor.textProperty().addListener(textListener);
        editor.selectionProperty().addListener(selectionListener);
        editor.sceneProperty().addListener(sceneListener);
        editor.editableProperty().addListener(editabilityListener);
        editor.disabledProperty().addListener(editabilityListener);
        observeScene(editor.getScene());
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
        updateReplacementButtons();
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

    void showReplace() {
        if (closed.get()) return;
        show();
        replacePane.setVisible(true);
        replacePane.setManaged(true);
        toggleReplace.setText("收起替换");
        updateReplacementButtons();
    }

    void hide() {
        cancelReplacement();
        revision++;
        debounce.stop();
        cancelActive();
        result = null;
        resultText = null;
        root.setVisible(false);
        root.setManaged(false);
        if (!closed.get()) editor.requestFocus();
    }

    private void invalidate() {
        if (closed.get() || !root.isVisible()) return;
        cancelReplacement();
        revision++;
        cancelActive();
        result = null;
        resultText = null;
        updateReplacementButtons();
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
                resultText = text;
                previous.setDisable(found.matches().isEmpty());
                next.setDisable(found.matches().isEmpty());
                renderCount(-1, "");
                updateReplacementButtons();
                if (found.truncated()) replaceStatus.setText("匹配超过 10000 处，不能全部替换；请缩小查找范围。");
                else if (replaceStatus.getText().startsWith("匹配超过"))
                    replaceStatus.setText("仅修改当前 SQL；空文本表示删除，可撤销，不自动保存文件或执行。");
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

    private boolean canEdit() {
        return !closed.get() && editingAllowed.getAsBoolean() && editor.isEditable() && !editor.isDisabled();
    }

    void editingStateChanged() {
        if (!canEdit()) cancelReplacement();
        updateReplacementButtons();
    }

    private SqlTextSearch.Match selectedMatch() {
        if (result == null) return null;
        var selection = editor.getSelection();
        for (var match : result.matches()) {
            if (match.start() == selection.getStart() && match.end() == selection.getEnd()) return match;
        }
        return null;
    }

    private void updateReplacementButtons() {
        boolean allowed = canEdit() && !replacing && result != null && !result.matches().isEmpty()
                && replacement.getLength() <= SqlTextReplacement.MAX_REPLACEMENT_LENGTH;
        replaceCurrent.setDisable(!allowed || selectedMatch() == null);
        replaceAll.setDisable(!allowed || result.truncated());
    }

    private void replace(boolean all) {
        if (!canEdit() || replacing || !root.isVisible() || !replacePane.isVisible() || !activeEditor()
                || result == null || !editor.getText().equals(resultText)
                || replacement.getLength() > SqlTextReplacement.MAX_REPLACEMENT_LENGTH) return;
        var selected = selectedMatch();
        if (all ? result.truncated() || result.matches().isEmpty() : selected == null) return;
        var matches = all ? result : new SqlTextSearch.Result(List.of(selected), false);
        String before = resultText;
        String value = replacement.getText();
        int caret = all ? editor.getCaretPosition() : selected.start();
        debounce.stop();
        cancelActive();
        long expected = ++revision;
        replacing = true;
        updateReplacementButtons();
        replaceStatus.setText("正在准备替换…");
        try {
            active = replaceSubmitter.submit(() -> SqlTextReplacement.replace(before, matches, value), edit -> {
                if (!current(expected) || !replacing) return;
                replacing = false;
                updateReplacementButtons();
                if (!canEdit() || !activeEditor() || !editor.getText().equals(before)) {
                    replaceStatus.setText("编辑状态已变化，未应用替换，请重试。");
                    return;
                }
                if (edit.replacements() == 0) {
                    replaceStatus.setText("内容未变化，未新增撤销记录。");
                    return;
                }
                // One atomic edit, isolated from adjacent typing and other replace actions.
                editor.getUndoManager().preventMerge();
                var batch = editor.createMultiChange(edit.replacements());
                // Descending original offsets preserve untouched text in the file change stream.
                for (int i = matches.matches().size() - 1; i >= 0; i--) {
                    var match = matches.matches().get(i);
                    if (!before.substring(match.start(), match.end()).equals(value))
                        batch.replaceTextAbsolutely(match.start(), match.end(), value);
                }
                batch.commit();
                editor.getUndoManager().preventMerge();
                if (all) editor.moveTo(Math.min(caret, editor.getLength()));
                else editor.selectRange(caret, caret + value.length());
                editor.requestFollowCaret();
                replaceStatus.setText("已替换 " + edit.replacements() + " 处，可撤销；未自动保存文件或执行 SQL。");
            }, failure -> {
                if (!current(expected) || !replacing) return;
                replacing = false;
                updateReplacementButtons();
                replaceStatus.setText(failure instanceof IllegalArgumentException
                        ? "替换内容超出限制或匹配已失效，未修改 SQL。"
                        : "替换失败，未修改 SQL，请重试。");
            });
            if (closed.get()) cancelActive();
        } catch (RuntimeException rejected) {
            replacing = false;
            updateReplacementButtons();
            replaceStatus.setText("替换暂不可用，未修改 SQL。");
        }
    }

    private void cancelReplacement() {
        if (!replacing) return;
        revision++;
        cancelActive();
        replacing = false;
        replaceStatus.setText("编辑状态已变化，未应用替换，请重试。");
        updateReplacementButtons();
    }

    private boolean owns(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == root || current == editor) return true;
        }
        return false;
    }

    private boolean activeEditor() {
        Scene scene = editor.getScene();
        return scene != null && (scene.getWindow() == null
                || (scene.getWindow().isFocused() && owns(scene.getFocusOwner())));
    }

    private void observeScene(Scene scene) {
        if (observedScene != null) observedScene.focusOwnerProperty().removeListener(focusListener);
        if (replacing) cancelReplacement();
        observedScene = scene;
        if (scene != null) scene.focusOwnerProperty().addListener(focusListener);
    }

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
        editor.sceneProperty().removeListener(sceneListener);
        editor.editableProperty().removeListener(editabilityListener);
        editor.disabledProperty().removeListener(editabilityListener);
        observeScene(null);
    }
}
