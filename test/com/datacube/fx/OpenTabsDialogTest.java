package com.datacube.fx;

import java.util.concurrent.atomic.AtomicBoolean;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Tab;
import javafx.scene.control.Label;
import javafx.scene.text.Text;
import com.datacube.config.AppSettings;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpenTabsDialogTest {
    @TempDir Path directory;

    @ParameterizedTest @EnumSource(AppSettings.Theme.class)
    void actualDialogKeepsBothInstructionLinesAndVisiblePrompt(AppSettings.Theme theme) throws Exception {
        FxUiTestSupport.call(() -> {
            AppSettings settings = new AppSettings(directory.resolve("settings")); settings.setTheme(theme);
            var dialog = new OpenTabsDialog(OpenTabsPaneTest.tabs("SQL", "SQL"), null, new ThemeManager(settings), () -> true);
            try {
                dialog.show(); dialog.getDialogPane().applyCss(); dialog.getDialogPane().layout();
                var picker = (OpenTabsPane) dialog.getDialogPane().getContent();
                Label hint = picker.getChildren().stream().filter(n -> n instanceof Label label
                                && label.getText().startsWith("选择候选"))
                        .map(n -> (Label) n).findFirst().orElseThrow();
                String rendered = hint.lookupAll(".text").stream().filter(Text.class::isInstance)
                        .map(Text.class::cast).map(Text::getText).findFirst().orElseThrow();
                assertEquals(hint.getText(), rendered, "instructions must not be ellipsized by the list");
                assertTrue(hint.getHeight() + 1 >= hint.prefHeight(hint.getWidth()));
                Text prompt = OpenTabsPaneTest.query(picker).lookupAll(".text").stream()
                        .filter(Text.class::isInstance).map(Text.class::cast)
                        .filter(node -> node.getText().equals("按标签标题筛选（不搜索正文）")).findFirst().orElseThrow();
                assertEquals(1.0, ((javafx.scene.paint.Color) prompt.getFill()).getOpacity());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void enterSwitchesExactCandidateAndClosesDialog() throws Exception {
        FxUiTestSupport.call(() -> {
            var tabs = OpenTabsPaneTest.tabs("current", "target", "target");
            Tab expected = tabs.getTabs().get(2);
            var dialog = new OpenTabsDialog(tabs, null, null, () -> true);
            try {
                dialog.show();
                var picker = (OpenTabsPane) dialog.getDialogPane().getContent();
                OpenTabsPaneTest.query(picker).setText("target");
                OpenTabsPaneTest.list(picker).getSelectionModel().select(expected);
                assertSame(tabs.getTabs().getFirst(), tabs.getSelectionModel().getSelectedItem());
                OpenTabsPaneTest.query(picker).fireEvent(new ActionEvent());
                assertSame(expected, tabs.getSelectionModel().getSelectedItem());
                assertFalse(dialog.isShowing()); assertEquals(Boolean.TRUE, dialog.getResult());
                assertTrue(OpenTabsPaneTest.list(picker).getItems().isEmpty());
            } finally { dialog.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"query-enter", "list-enter", "query-escape", "list-escape"})
    void keyboardEventsOnShownControlsConfirmOrCancelWithoutPreviewNavigation(String action) throws Exception {
        FxUiTestSupport.call(() -> {
            var tabs = OpenTabsPaneTest.tabs("current", "target");
            var dialog = new OpenTabsDialog(tabs, null, null, () -> true);
            try {
                dialog.show(); dialog.getDialogPane().applyCss(); dialog.getDialogPane().layout();
                var picker = (OpenTabsPane) dialog.getDialogPane().getContent();
                OpenTabsPaneTest.query(picker).setText("target");
                assertSame(tabs.getTabs().getFirst(), tabs.getSelectionModel().getSelectedItem());
                var control = action.startsWith("query") ? OpenTabsPaneTest.query(picker) : OpenTabsPaneTest.list(picker);
                boolean confirm = action.endsWith("enter");
                control.requestFocus(); control.fireEvent(OpenTabsPaneTest.key(confirm ? KeyCode.ENTER : KeyCode.ESCAPE));
                assertFalse(dialog.isShowing());
                assertSame(tabs.getTabs().get(confirm ? 1 : 0), tabs.getSelectionModel().getSelectedItem());
                assertEquals(confirm ? Boolean.TRUE : null, dialog.getResult());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void deniedSwitchStaysOpenAndCancelDoesNotNavigate() throws Exception {
        FxUiTestSupport.call(() -> {
            var tabs = OpenTabsPaneTest.tabs("current", "target");
            AtomicBoolean allowed = new AtomicBoolean(true);
            var dialog = new OpenTabsDialog(tabs, null, null, allowed::get);
            try {
                dialog.show();
                var picker = (OpenTabsPane) dialog.getDialogPane().getContent();
                OpenTabsPaneTest.query(picker).setText("target");
                allowed.set(false);
                ((Button) dialog.getDialogPane().lookup("#open-tabs-switch")).fire();
                assertTrue(dialog.isShowing()); assertNull(dialog.getResult());
                assertSame(tabs.getTabs().getFirst(), tabs.getSelectionModel().getSelectedItem());
                var cancel = dialog.getDialogPane().getButtonTypes().stream()
                        .filter(type -> type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
                ((Button) dialog.getDialogPane().lookupButton(cancel)).fire();
                assertFalse(dialog.isShowing()); assertNull(dialog.getResult());
                assertSame(tabs.getTabs().getFirst(), tabs.getSelectionModel().getSelectedItem());
                assertFalse(picker.activateSelected());
            } finally { dialog.close(); }
            return null;
        });
    }
}
