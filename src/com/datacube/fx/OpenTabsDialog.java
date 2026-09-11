package com.datacube.fx;

import java.util.function.BooleanSupplier;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;

/** Explicit navigation only; no content factories or database services are accepted. */
final class OpenTabsDialog extends Dialog<Boolean> {
    private final OpenTabsPane picker;

    OpenTabsDialog(TabPane tabs, Window owner, ThemeManager theme, BooleanSupplier navigationAllowed) {
        setTitle("查找已打开标签");
        setHeaderText(null);
        if (owner != null) initOwner(owner);
        picker = new OpenTabsPane(tabs, navigationAllowed);
        getDialogPane().setContent(picker);
        ButtonType switchType = new ButtonType("切换", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(switchType, cancelType);
        Button switchButton = (Button) getDialogPane().lookupButton(switchType);
        switchButton.setId("open-tabs-switch");
        switchButton.disableProperty().bind(picker.candidateAvailableProperty().not());
        switchButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!picker.activateSelected()) event.consume();
        });
        picker.onConfirm(switchButton::fire);
        Button cancelButton = (Button) getDialogPane().lookupButton(cancelType);
        // ListView consumes Enter/Escape for its own edit behavior even when not editable.
        // Handle dialog navigation before the control skin, for both query and list focus.
        picker.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() || event.isAltDown() || event.isMetaDown() || event.isShiftDown()) return;
            if (event.getCode() == KeyCode.ENTER) {
                event.consume(); switchButton.fire();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                event.consume(); cancelButton.fire();
            }
        });
        setResultConverter(button -> button == switchType ? Boolean.TRUE : null);
        setOnShown(event -> picker.focusQuery());
        setOnHidden(event -> picker.close());
        if (theme != null) theme.applyTo(getDialogPane());
    }

    static void show(TabPane tabs, Window owner, ThemeManager theme, BooleanSupplier navigationAllowed) {
        if (tabs.getTabs().isEmpty() || tabs.isDisabled() || !navigationAllowed.getAsBoolean()) return;
        OpenTabsDialog dialog = new OpenTabsDialog(tabs, owner, theme, navigationAllowed);
        try {
            dialog.showAndWait();
        } finally {
            dialog.picker.close();
        }
    }
}
