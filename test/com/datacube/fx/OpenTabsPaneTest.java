package com.datacube.fx;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.event.Event;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class OpenTabsPaneTest {
    @ParameterizedTest
    @CsvSource({"'  sql  ',0", "'报表',1", "'[a.*]',2", "'nomatch',-1", "'İ',3"})
    void literalTitleFilteringNeverNavigatesOrSearchesContents(String text, int expected) throws Exception {
        FxUiTestSupport.call(() -> {
            TabPane tabs = tabs("SQL orders *", "报表 数据", "DDL [a.*]", "İstanbul");
            ((TextArea) tabs.getTabs().get(0).getContent()).setText("nomatch");
            tabs.getSelectionModel().select(1);
            try (var picker = new OpenTabsPane(tabs, () -> true)) {
                assertSame(tabs.getTabs().get(1), list(picker).getSelectionModel().getSelectedItem());
                query(picker).setText(text);
                assertEquals(expected < 0 ? List.of() : List.of(tabs.getTabs().get(expected)), list(picker).getItems());
                assertSame(tabs.getTabs().get(1), tabs.getSelectionModel().getSelectedItem());
                assertEquals(4, tabs.getTabs().size());
                assertEquals("nomatch", ((TextArea) tabs.getTabs().get(0).getContent()).getText());
            }
            return null;
        });
    }

    @Test void explicitSwitchUsesIdentityForDuplicateTitlesAndKeepsTextSelectionAndOrder() throws Exception {
        FxUiTestSupport.call(() -> {
            TabPane tabs = tabs("SQL duplicate *", "data", "SQL duplicate *");
            Tab target = tabs.getTabs().get(2);
            TextArea editor = (TextArea) target.getContent();
            editor.setText("select 1;\n-- unsaved"); editor.selectRange(8, 2);
            AtomicInteger navigation = new AtomicInteger();
            tabs.getSelectionModel().selectedItemProperty().addListener((o, before, after) -> navigation.incrementAndGet());
            List<Tab> original = List.copyOf(tabs.getTabs());
            try (var picker = new OpenTabsPane(tabs, () -> true)) {
                query(picker).setText("duplicate");
                assertEquals(List.of(original.get(0), target), list(picker).getItems());
                query(picker).fireEvent(key(KeyCode.DOWN));
                assertSame(target, list(picker).getSelectionModel().getSelectedItem());
                assertEquals(0, navigation.get());
                assertTrue(picker.activateSelected());
                assertSame(target, tabs.getSelectionModel().getSelectedItem()); assertEquals(1, navigation.get());
                assertEquals(original, tabs.getTabs());
                assertEquals("select 1;\n-- unsaved", editor.getText());
                assertEquals(8, editor.getAnchor()); assertEquals(2, editor.getCaretPosition());
            }
            return null;
        });
    }

    @Test void liveChangesPreserveIdentityButNeverRetargetRemovedOrRenamedCandidate() throws Exception {
        FxUiTestSupport.call(() -> {
            TabPane tabs = tabs("current", "duplicate", "duplicate");
            Tab current = tabs.getTabs().getFirst(), removed = tabs.getTabs().get(1), remaining = tabs.getTabs().get(2);
            try (var picker = new OpenTabsPane(tabs, () -> true)) {
                query(picker).setText("duplicate");
                tabs.getTabs().add(1, new Tab("new", new Label("only UI")));
                assertSame(removed, list(picker).getSelectionModel().getSelectedItem());
                tabs.getTabs().remove(removed);
                assertEquals(List.of(remaining), list(picker).getItems());
                assertNull(list(picker).getSelectionModel().getSelectedItem());
                assertFalse(picker.activateSelected()); assertSame(current, tabs.getSelectionModel().getSelectedItem());
                list(picker).getSelectionModel().select(remaining);
                remaining.setText("renamed");
                assertTrue(list(picker).getItems().isEmpty()); assertFalse(picker.activateSelected());
                query(picker).setText("renamed");
                assertTrue(picker.activateSelected()); assertSame(remaining, tabs.getSelectionModel().getSelectedItem());
                query(picker).clear();
                assertEquals(List.copyOf(tabs.getTabs()), list(picker).getItems());
            }
            return null;
        });
    }

    @Test void disabledTargetOwnerAndClosedPickerRejectOldActionsAndRecoverAfterCancellation() throws Exception {
        FxUiTestSupport.call(() -> {
            TabPane tabs = tabs("current", "target");
            Tab current = tabs.getTabs().getFirst(), target = tabs.getTabs().get(1);
            AtomicBoolean allowed = new AtomicBoolean(true);
            var picker = new OpenTabsPane(tabs, allowed::get);
            try {
                query(picker).setText("target");
                target.setDisable(true);
                assertFalse(picker.candidateAvailableProperty().get()); assertFalse(picker.activateSelected());
                target.setDisable(false); assertTrue(picker.candidateAvailableProperty().get());
                allowed.set(false); assertFalse(picker.activateSelected());
                allowed.set(true); tabs.setDisable(true); assertFalse(picker.activateSelected());
                assertSame(current, tabs.getSelectionModel().getSelectedItem());
                tabs.setDisable(false); assertTrue(picker.activateSelected());
                tabs.getSelectionModel().select(current);
                picker.close(); picker.close();
                target.setText("later"); tabs.getTabs().add(new Tab("later")); query(picker).setText("later");
                assertTrue(list(picker).getItems().isEmpty()); assertFalse(picker.activateSelected());
                assertFalse(picker.candidateAvailableProperty().get()); assertSame(current, tabs.getSelectionModel().getSelectedItem());
            } finally { picker.close(); }
            return null;
        });
    }

    @Test void emptyAndOverlongQueriesGiveFeedbackWithoutTruncatingToAnotherTarget() throws Exception {
        FxUiTestSupport.call(() -> {
            TabPane tabs = tabs();
            try (var picker = new OpenTabsPane(tabs, () -> true)) {
                assertEquals("匹配 0 / 0 个已打开标签", status(picker).getText());
                assertFalse(picker.activateSelected());
                Tab exact = new Tab("x".repeat(256)); tabs.getTabs().add(exact);
                query(picker).setText("x".repeat(256));
                assertEquals(List.of(exact), list(picker).getItems());
                query(picker).setText("x".repeat(257));
                assertTrue(list(picker).getItems().isEmpty()); assertFalse(picker.candidateAvailableProperty().get());
                assertTrue(status(picker).getText().contains("最多 256"));
                query(picker).setText("   ");
                assertEquals(List.of(exact), list(picker).getItems()); assertTrue(picker.activateSelected());
            }
            return null;
        });
    }

    @Test void realManagedCloseGuardCannotBeBypassedByPicker() throws Exception {
        ContentTabPane content = FxUiTestSupport.call(ContentTabPane::new);
        CompletableFuture<CloseGuardOutcome> decision = new CompletableFuture<>();
        AtomicInteger guards = new AtomicInteger(), finalizers = new AtomicInteger();
        var picker = FxUiTestSupport.call(() -> {
            content.addPermanentTab("current", new Label("current"));
            Tab guarded = content.openManagedTab("guarded", () -> new ContentTabPane.ManagedTabSpec(
                    new Label("synthetic"), () -> { guards.incrementAndGet(); return decision; },
                    finalizers::incrementAndGet, () -> {}));
            TabPane tabs = (TabPane) content.getNode(); tabs.getSelectionModel().selectFirst();
            var pane = new OpenTabsPane(tabs, () -> true);
            query(pane).setText("guarded");
            Event.fireEvent(guarded, new Event(Tab.TAB_CLOSE_REQUEST_EVENT));
            assertTrue(guarded.isDisabled()); assertFalse(pane.activateSelected());
            assertEquals("current", tabs.getSelectionModel().getSelectedItem().getText());
            assertEquals(1, guards.get()); assertEquals(0, finalizers.get());
            return pane;
        });
        try {
            decision.complete(CloseGuardOutcome.REJECTED);
            FxUiTestSupport.call(() -> {
                assertTrue(picker.activateSelected());
                assertEquals("guarded", ((TabPane) content.getNode()).getSelectionModel().getSelectedItem().getText());
                assertEquals(1, guards.get()); assertEquals(0, finalizers.get());
                return null;
            });
        } finally {
            decision.complete(CloseGuardOutcome.REJECTED);
            FxUiTestSupport.call(() -> { picker.close(); return null; });
        }
    }

    static TabPane tabs(String... titles) {
        var tabs = new TabPane();
        for (String title : titles) tabs.getTabs().add(new Tab(title, new TextArea("synthetic")));
        return tabs;
    }
    static TextField query(OpenTabsPane pane) { return (TextField) pane.lookup("#open-tabs-query"); }
    @SuppressWarnings("unchecked") static ListView<Tab> list(OpenTabsPane pane) { return (ListView<Tab>) pane.lookup("#open-tabs-list"); }
    static Label status(OpenTabsPane pane) { return (Label) pane.lookup("#open-tabs-status"); }
    static KeyEvent key(KeyCode code) { return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false); }
}
