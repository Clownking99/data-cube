package com.datacube.fx;

import com.datacube.fx.ConnectionTreePane.Kind;
import com.datacube.fx.ConnectionTreePane.NodeData;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionTreeFindBarTest {
    static TreeItem<NodeData> item(Kind kind, String name) {
        return new TreeItem<>(new NodeData(kind, name, null, "synthetic", "schema", name));
    }

    @Test void scanUsesExpandedDepthFirstRowsAndSkipsStatusWithoutOpeningBranches() {
        TreeItem<NodeData> root = item(Kind.CONNECTION, "hidden");
        TreeItem<NodeData> schema = item(Kind.SCHEMA, "east schema");
        TreeItem<NodeData> table = item(Kind.TABLE, "east table");
        TreeItem<NodeData> collapsed = new TreeItem<>(item(Kind.TABLES, "east collapsed").getValue()) {
            @Override public ObservableList<TreeItem<NodeData>> getChildren() {
                throw new AssertionError("Collapsed children must not be accessed");
            }
        };
        schema.getChildren().addAll(List.of(item(Kind.STATUS, "east loading"), table, collapsed));
        schema.setExpanded(true);
        root.getChildren().addAll(List.of(schema, item(Kind.VIEW, "west"), item(Kind.CONNECTION, "east conn")));
        root.setExpanded(true);
        var result = ConnectionTreeFindBar.scan(root, false, " EAST ");
        assertEquals(List.of(schema, table, collapsed, root.getChildren().getLast()),
                result.matches().stream().map(ConnectionTreeFindBar.Match::item).toList());
        assertEquals(List.of(0, 2, 3, 5), result.matches().stream().map(ConnectionTreeFindBar.Match::row).toList());
        assertFalse(result.truncated());
        assertFalse(collapsed.isExpanded());
        schema.setExpanded(false);
        assertEquals(List.of(0, 2), ConnectionTreeFindBar.scan(root, false, "east").matches()
                .stream().map(ConnectionTreeFindBar.Match::row).toList());
    }

    @Test void shownHiddenNullRootsAndLiteralUnicodeUseLocaleIndependentMatching() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TreeItem<NodeData> root = item(Kind.CONNECTION, "INDEX [公司].*");
            root.getChildren().add(item(Kind.TABLE, "child"));
            assertEquals(1, ConnectionTreeFindBar.scan(root, true, "index [公司].*").matches().size());
            assertTrue(ConnectionTreeFindBar.scan(root, false, "index").matches().isEmpty());
            assertTrue(ConnectionTreeFindBar.scan(root, true, "IN.*").matches().isEmpty(), "not a regex");
            root.setExpanded(true);
            assertEquals(1, ConnectionTreeFindBar.scan(root, true, "child").matches().getFirst().row());
            assertEquals(0, ConnectionTreeFindBar.scan(root, false, "child").matches().getFirst().row());
            assertTrue(ConnectionTreeFindBar.scan(null, false, "x").matches().isEmpty());
            assertTrue(ConnectionTreeFindBar.scan(root, true, null).matches().isEmpty());
        } finally { Locale.setDefault(original); }
    }

    @ParameterizedTest @ValueSource(strings = {"", "  ", "\t\n", "missing"})
    void emptyOrAbsentQueryHasNoMatch(String query) {
        assertTrue(ConnectionTreeFindBar.scan(item(Kind.CONNECTION, "name"), true, query).matches().isEmpty());
    }

    @ParameterizedTest @ValueSource(ints = {9999, 10000, 10001})
    void scanLimitReportsTruncationOnlyWhenNodesRemain(int count) {
        TreeItem<NodeData> root = item(Kind.CONNECTION, "hidden");
        for (int i = 0; i < count; i++) root.getChildren().add(item(Kind.TABLE, "target " + i));
        root.setExpanded(true);
        var result = ConnectionTreeFindBar.scan(root, false, "target");
        assertEquals(Math.min(count, 10000), result.matches().size());
        assertEquals(count > 10000, result.truncated());
        assertEquals(Math.min(count, 10000) - 1, result.matches().getLast().row());
    }

    @Test void queryLengthBoundaryAndStatusNodesCountTowardWorkLimit() {
        String allowed = "x".repeat(256);
        TreeItem<NodeData> root = item(Kind.CONNECTION, allowed + "x");
        assertEquals(1, ConnectionTreeFindBar.scan(root, true, allowed).matches().size());
        assertTrue(ConnectionTreeFindBar.scan(root, true, allowed + "x").matches().isEmpty());
        for (int i = 0; i < 10000; i++) root.getChildren().add(item(Kind.STATUS, "target"));
        root.getChildren().add(item(Kind.TABLE, "target"));
        root.setExpanded(true);
        var result = ConnectionTreeFindBar.scan(root, false, "target");
        assertTrue(result.matches().isEmpty());
        assertTrue(result.truncated());
    }

    @Test void typingCountsWithoutSelectionAndNavigationCyclesRelativeToCurrentRow() throws Exception {
        FxUiTestSupport.call(() -> {
            try (Fixture f = new Fixture()) {
                f.query.setText("east");
                assertNull(f.selected());
                assertTrue(f.next.isDisabled());
                f.bar.refreshMatches();
                assertEquals("共 2 处 · 已展开节点", f.status.getText());
                assertNull(f.selected());
                f.next.fire(); assertSame(f.first, f.selected());
                assertEquals("1 / 2 · 已展开节点", f.status.getText());
                f.query.fireEvent(new ActionEvent()); assertSame(f.last, f.selected());
                press(f.query, KeyCode.ENTER, true, false); assertSame(f.first, f.selected());
                f.previous.fire(); assertSame(f.last, f.selected());
                assertTrue(f.status.getText().contains("已回到末尾"));
                press(f.tree, KeyCode.F3, false, false); assertSame(f.first, f.selected());
                assertTrue(f.status.getText().contains("已回到开头"));
                press(f.query, KeyCode.F3, true, false); assertSame(f.last, f.selected());
                f.tree.getSelectionModel().select(f.middle);
                f.next.fire(); assertSame(f.last, f.selected());
                f.tree.getSelectionModel().clearSelection();
                f.previous.fire(); assertSame(f.last, f.selected());
                assertFalse(f.first.isExpanded());
                assertEquals(3, f.root.getChildren().size());
            }
            return null;
        });
    }

    @Test void keyboardClearAndEscapeKeepSelectionAndExpansionAndReturnFocus() throws Exception {
        FxUiTestSupport.call(() -> {
            try (Fixture f = new Fixture()) {
                f.tree.getSelectionModel().select(f.middle);
                press(f.tree, KeyCode.F, false, true);
                assertSame(f.query, f.scene.getFocusOwner());
                f.query.setText("east");
                f.bar.refreshMatches();
                f.clear.fire();
                assertEquals("", f.query.getText());
                assertSame(f.middle, f.selected());
                assertTrue(f.next.isDisabled());
                assertSame(f.query, f.scene.getFocusOwner());
                press(f.query, KeyCode.ESCAPE, false, false);
                assertSame(f.tree, f.scene.getFocusOwner());
                f.query.setText("west");
                press(f.tree, KeyCode.F, false, true);
                assertEquals("west", f.query.getSelectedText());
                press(f.query, KeyCode.ESCAPE, false, false);
                assertEquals("", f.query.getText());
                assertSame(f.tree, f.scene.getFocusOwner());
                assertSame(f.middle, f.selected());
                assertTrue(f.root.isExpanded());
            }
            return null;
        });
    }

    @Test void mutationCollapseRenameAndReplacementCannotNavigateStaleMatches() throws Exception {
        FxUiTestSupport.call(() -> {
            try (Fixture f = new Fixture()) {
                TreeItem<NodeData> child = item(Kind.TABLE, "east child");
                f.first.getChildren().add(child);
                f.first.setExpanded(true);
                f.query.setText("east"); f.bar.refreshMatches();
                assertEquals("共 3 处 · 已展开节点", f.status.getText());
                assertEquals(f.tree.getRow(child), ConnectionTreeFindBar.scan(f.root, false, "child").matches().getFirst().row());
                f.tree.getSelectionModel().select(f.first);
                f.first.setExpanded(false);
                assertTrue(f.next.isDisabled());
                f.query.fireEvent(new ActionEvent()); assertSame(f.last, f.selected());
                f.last.setValue(item(Kind.CONNECTION, "west renamed").getValue());
                f.query.fireEvent(new ActionEvent()); assertSame(f.first, f.selected());
                TreeItem<NodeData> loaded = item(Kind.TABLE, "east loaded");
                f.first.getChildren().setAll(List.of(loaded));
                f.first.setExpanded(true);
                f.query.fireEvent(new ActionEvent()); assertSame(loaded, f.selected());
                f.root.getChildren().clear();
                f.query.fireEvent(new ActionEvent()); assertNull(f.selected());
                assertTrue(f.status.getText().contains("无匹配"));
                TreeItem<NodeData> replacement = item(Kind.CONNECTION, "new root");
                TreeItem<NodeData> fresh = item(Kind.CONNECTION, "east fresh");
                replacement.getChildren().add(fresh); replacement.setExpanded(true);
                f.tree.setRoot(replacement);
                f.query.fireEvent(new ActionEvent()); assertSame(fresh, f.selected());
                String text = f.status.getText();
                f.root.getChildren().add(item(Kind.CONNECTION, "east detached"));
                assertEquals(text, f.status.getText(), "old root listener detached");
            }
            return null;
        });
    }

    @Test void longQueryAndTruncationHaveHonestDisabledOrPartialFeedback() throws Exception {
        FxUiTestSupport.call(() -> {
            try (Fixture f = new Fixture()) {
                f.query.setText("x".repeat(257)); f.bar.refreshMatches();
                assertTrue(f.status.getText().contains("最多 256"));
                assertTrue(f.next.isDisabled());
                f.query.fireEvent(new ActionEvent()); assertNull(f.selected());
                f.root.getChildren().clear();
                for (int i = 0; i < 10001; i++) f.root.getChildren().add(item(Kind.TABLE, "east " + i));
                f.query.setText("east"); f.bar.refreshMatches();
                assertTrue(f.status.getText().contains("10000 处"));
                assertTrue(f.status.getText().contains("仅检查前 10000 个节点"));
                f.previous.fire();
                assertSame(f.root.getChildren().get(9999), f.selected());
            }
            return null;
        });
    }

    @Test void actualDebounceUpdatesCountWithoutSelection() throws Exception {
        CompletableFuture<String> counted = new CompletableFuture<>();
        Fixture f = FxUiTestSupport.call(() -> {
            Fixture fixture = new Fixture();
            fixture.status.textProperty().addListener((obs, old, value) -> {
                if (value.equals("共 2 处 · 已展开节点")) counted.complete(value);
            });
            fixture.query.setText("east");
            return fixture;
        });
        try {
            assertEquals("共 2 处 · 已展开节点", counted.get(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> { assertNull(f.selected()); return null; });
        } finally { FxUiTestSupport.call(() -> { f.close(); return null; }); }
    }

    @Test void offThreadCloseDisablesQueuedNavigationAndDetachesExternalListeners() throws Exception {
        Fixture f = FxUiTestSupport.call(() -> {
            Fixture fixture = new Fixture();
            fixture.query.setText("east");
            return fixture;
        });
        f.close(); f.close();
        FxUiTestSupport.call(() -> {
            assertTrue(f.bar.getNode().isDisabled());
            String text = f.status.getText();
            f.bar.refreshMatches();
            f.query.fireEvent(new ActionEvent());
            f.root.getChildren().clear();
            f.tree.setRoot(item(Kind.CONNECTION, "east"));
            assertNull(f.selected());
            assertEquals(text, f.status.getText());
            return null;
        });
    }

    @ParameterizedTest @CsvSource({"240,dark", "280,dark", "400,dark", "240,light", "280,light", "400,light"})
    void narrowLayoutsKeepControlsInsideAndLeaveSpaceForTree(int width, String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            try (Fixture f = new Fixture(width)) {
                f.scene.getStylesheets().addAll(getClass().getResource("theme-base.css").toExternalForm(),
                        getClass().getResource("theme-" + theme + ".css").toExternalForm());
                f.query.setText("east"); f.bar.refreshMatches();
                f.container.applyCss(); f.container.layout();
                for (var node : List.of(f.query, f.clear, f.previous, f.next, f.status)) {
                    var bounds = node.localToScene(node.getBoundsInLocal());
                    assertTrue(bounds.getMinX() >= -1, node.getId());
                    assertTrue(bounds.getMaxX() <= width + 1, node.getId());
                    assertTrue(bounds.getWidth() > 0, node.getId());
                }
                assertTrue(f.query.getWidth() > 100);
                assertTrue(f.tree.getHeight() > 350);
                f.query.clear();
                f.query.requestFocus();
                // A Scene without a visible Stage does not activate focus; exercise the focused CSS too.
                f.query.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), true);
                f.container.applyCss(); f.container.layout();
                var prompt = f.query.lookupAll(".text").stream()
                        .filter(node -> node instanceof javafx.scene.text.Text text
                                && f.query.getPromptText().equals(text.getText()))
                        .map(node -> (javafx.scene.text.Text) node).findFirst().orElseThrow();
                assertTrue(prompt.isVisible());
                assertEquals(javafx.scene.paint.Color.web(theme.equals("dark") ? "#A8A8B8" : "#555555"), prompt.getFill());
            }
            return null;
        });
    }

    static void press(javafx.scene.Node node, KeyCode code, boolean shift, boolean control) {
        node.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, false));
    }

    private static final class Fixture implements AutoCloseable {
        final TreeItem<NodeData> root = item(Kind.CONNECTION, "hidden");
        final TreeItem<NodeData> first = item(Kind.CONNECTION, "east");
        final TreeItem<NodeData> middle = item(Kind.CONNECTION, "west");
        final TreeItem<NodeData> last = item(Kind.CONNECTION, "east");
        final TreeView<NodeData> tree = new TreeView<>(root);
        final ConnectionTreeFindBar bar;
        final TextField query;
        final Button previous, next, clear;
        final Label status;
        final VBox container;
        final Scene scene;
        Fixture() { this(280); }
        Fixture(int width) {
            root.getChildren().addAll(List.of(first, middle, last));
            root.setExpanded(true); tree.setShowRoot(false);
            bar = new ConnectionTreeFindBar(tree);
            query = (TextField) bar.getNode().lookup("#connection-tree-find-query");
            previous = (Button) bar.getNode().lookup("#connection-tree-find-previous");
            next = (Button) bar.getNode().lookup("#connection-tree-find-next");
            clear = (Button) bar.getNode().lookup("#connection-tree-find-clear");
            status = (Label) bar.getNode().lookup("#connection-tree-find-status");
            container = new VBox(6, bar.getNode(), tree);
            VBox.setVgrow(tree, Priority.ALWAYS);
            scene = new Scene(container, width, 600);
            container.resize(width, 600);
        }
        TreeItem<NodeData> selected() { return tree.getSelectionModel().getSelectedItem(); }
        @Override public void close() { bar.close(); }
    }
}
