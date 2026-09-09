package com.datacube.fx;

import com.datacube.fx.ConnectionTreePane.NodeData;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Searches only existing expanded nodes. No provider, loader, file or object-action dependency. */
final class ConnectionTreeFindBar implements AutoCloseable {
    static final int MAX_QUERY = 256;
    static final int MAX_NODES = 10_000;
    record Match(TreeItem<NodeData> item, int row) {}
    record Result(List<Match> matches, boolean truncated) {
        Result { matches = List.copyOf(matches); }
    }

    private final TreeView<NodeData> tree;
    private final VBox root = new VBox(4);
    private final TextField query = new TextField();
    private final Button previous = new Button("上一处");
    private final Button next = new Button("下一处");
    private final Label status = new Label();
    private final FlowPane navigation = new FlowPane(6, 4, previous, next);
    private final PauseTransition debounce = new PauseTransition(Duration.millis(180));
    private final AtomicBoolean closed = new AtomicBoolean();
    private Result result = new Result(List.of(), false);
    private boolean pending;
    private TreeItem<NodeData> observedRoot;
    private final EventHandler<TreeItem.TreeModificationEvent<NodeData>> treeListener = ignored -> invalidate();
    private final ChangeListener<TreeItem<NodeData>> rootListener = (obs, before, after) -> observeRoot(after);
    private final InvalidationListener visibilityListener = ignored -> invalidate();
    private final InvalidationListener selectionListener = ignored -> render("");
    private final EventHandler<KeyEvent> shortcutHandler = this::onShortcut;

    ConnectionTreeFindBar(TreeView<NodeData> tree) {
        this.tree = tree;
        root.setId("connection-tree-find-bar");
        query.setId("connection-tree-find-query");
        query.setPromptText("查找已展开的连接 / 对象");
        query.setAccessibleText("查找已展开的连接或对象");
        query.setTooltip(new Tooltip("Ctrl+F 聚焦；Enter / Shift+Enter 或 F3 / Shift+F3 定位；不展开或读取数据库。"));
        query.setMinWidth(0);
        HBox.setHgrow(query, Priority.ALWAYS);
        Button clear = new Button("清除");
        clear.setId("connection-tree-find-clear");
        clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.setOnAction(event -> { query.clear(); query.requestFocus(); });
        previous.setId("connection-tree-find-previous");
        next.setId("connection-tree-find-next");
        previous.setOnAction(event -> navigate(false));
        next.setOnAction(event -> navigate(true));
        status.setId("connection-tree-find-status");
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.setStyle("-fx-font-size: 11px;");
        root.getChildren().addAll(new HBox(4, query, clear), navigation, status);
        query.textProperty().addListener(ignored -> invalidate());
        query.setOnAction(event -> navigate(true));
        query.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && event.isShiftDown()
                    && !event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
                navigate(false);
                event.consume();
            }
        });
        root.addEventFilter(KeyEvent.KEY_PRESSED, shortcutHandler);
        tree.addEventFilter(KeyEvent.KEY_PRESSED, shortcutHandler);
        tree.rootProperty().addListener(rootListener);
        tree.showRootProperty().addListener(visibilityListener);
        tree.getSelectionModel().selectedItemProperty().addListener(selectionListener);
        debounce.setOnFinished(event -> refreshMatches());
        observeRoot(tree.getRoot());
    }

    Parent getNode() { return root; }

    private void observeRoot(TreeItem<NodeData> value) {
        if (observedRoot != null) observedRoot.removeEventHandler(TreeItem.treeNotificationEvent(), treeListener);
        observedRoot = value;
        if (value != null) value.addEventHandler(TreeItem.treeNotificationEvent(), treeListener);
        invalidate();
    }

    private void invalidate() {
        if (closed.get()) return;
        debounce.stop();
        result = new Result(List.of(), false);
        boolean searching = !query.getText().strip().isEmpty();
        pending = searching && query.getLength() <= MAX_QUERY;
        navigation.setVisible(searching);
        navigation.setManaged(searching);
        previous.setDisable(true);
        next.setDisable(true);
        if (!searching) setStatus("仅查找已展开节点，不读取数据库");
        else if (query.getLength() > MAX_QUERY) setStatus("查找内容最多 256 个字符");
        else { setStatus("正在查找已展开节点…"); debounce.playFromStart(); }
    }

    /** Also used before navigation, so a queued count can never authorize a stale tree item. */
    void refreshMatches() {
        if (closed.get()) return;
        debounce.stop();
        pending = false;
        result = scan(tree.getRoot(), tree.isShowRoot(), query.getText());
        previous.setDisable(result.matches().isEmpty());
        next.setDisable(result.matches().isEmpty());
        render("");
    }

    private void navigate(boolean forward) {
        if (closed.get()) return;
        refreshMatches();
        List<Match> matches = result.matches();
        if (matches.isEmpty()) return;
        int row = tree.getRow(tree.getSelectionModel().getSelectedItem());
        Match target = null;
        if (forward) {
            for (Match match : matches) if (match.row() > row) { target = match; break; }
        } else {
            for (int i = matches.size() - 1; i >= 0; i--)
                if (matches.get(i).row() < row || row < 0) { target = matches.get(i); break; }
        }
        boolean wrapped = target == null;
        if (wrapped) target = forward ? matches.getFirst() : matches.getLast();
        tree.getSelectionModel().select(target.item());
        tree.scrollTo(target.row());
        render(wrapped ? (forward ? " · 已回到开头" : " · 已回到末尾") : "");
    }

    private void render(String suffix) {
        if (closed.get()) return;
        if (query.getText().strip().isEmpty()) { setStatus("仅查找已展开节点，不读取数据库"); return; }
        if (query.getLength() > MAX_QUERY) { setStatus("查找内容最多 256 个字符"); return; }
        if (pending) { setStatus("正在查找已展开节点…"); return; }
        int current = -1;
        for (int i = 0; i < result.matches().size(); i++)
            if (result.matches().get(i).item() == tree.getSelectionModel().getSelectedItem()) { current = i; break; }
        String count = result.matches().isEmpty() ? "已展开节点中无匹配"
                : current < 0 ? "共 " + result.matches().size() + " 处 · 已展开节点"
                : (current + 1) + " / " + result.matches().size() + " · 已展开节点";
        setStatus(count + suffix + (result.truncated() ? " · 仅检查前 10000 个节点" : ""));
    }

    private void setStatus(String value) { status.setText(value); status.setAccessibleText(value); }

    private void onShortcut(KeyEvent event) {
        if (closed.get()) return;
        if (event.getCode() == KeyCode.F && event.isControlDown()
                && !event.isShiftDown() && !event.isAltDown() && !event.isMetaDown()) {
            query.requestFocus(); query.selectAll(); event.consume();
        } else if (event.getCode() == KeyCode.F3 && !query.getText().strip().isEmpty()
                && !event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
            navigate(!event.isShiftDown()); event.consume();
        } else if (event.getCode() == KeyCode.ESCAPE && (!query.getText().isEmpty()
                || (query.getScene() != null && query.getScene().getFocusOwner() == query))) {
            query.clear(); tree.requestFocus(); event.consume();
        }
    }

    static Result scan(TreeItem<NodeData> root, boolean showRoot, String input) {
        if (root == null || input == null || input.length() > MAX_QUERY || input.strip().isEmpty())
            return new Result(List.of(), false);
        String needle = input.strip().toLowerCase(Locale.ROOT);
        var stack = new ArrayDeque<Iterator<TreeItem<NodeData>>>();
        if (showRoot) stack.push(List.of(root).iterator());
        else if (root.isExpanded()) stack.push(root.getChildren().iterator());
        List<Match> found = new ArrayList<>();
        int visited = 0;
        while (!stack.isEmpty()) {
            if (!stack.peek().hasNext()) { stack.pop(); continue; }
            if (visited == MAX_NODES) return new Result(found, true);
            TreeItem<NodeData> item = stack.peek().next();
            NodeData data = item.getValue();
            if (data != null && data.kind != ConnectionTreePane.Kind.STATUS && data.label != null
                    && data.label.toLowerCase(Locale.ROOT).contains(needle)) found.add(new Match(item, visited));
            visited++;
            // Never touch the children of a collapsed branch, or change expansion to reveal a match.
            if (item.isExpanded()) stack.push(item.getChildren().iterator());
        }
        return new Result(found, false);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Runnable detach = () -> {
            debounce.stop();
            observeRoot(null);
            tree.rootProperty().removeListener(rootListener);
            tree.showRootProperty().removeListener(visibilityListener);
            tree.getSelectionModel().selectedItemProperty().removeListener(selectionListener);
            tree.removeEventFilter(KeyEvent.KEY_PRESSED, shortcutHandler);
            root.removeEventFilter(KeyEvent.KEY_PRESSED, shortcutHandler);
            result = new Result(List.of(), false);
            root.setDisable(true);
        };
        if (Platform.isFxApplicationThread()) detach.run();
        else {
            try { Platform.runLater(detach); }
            catch (IllegalStateException stopped) { /* Toolkit stopped; closed still prevents all callbacks. */ }
        }
    }
}
