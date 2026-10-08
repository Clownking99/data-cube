package com.datacube.fx;

import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;

/** Keeps menu entry labels, guards and actions on the existing button/shortcut command. */
final class SqlActionMenus {
    private SqlActionMenus() {}
    static MenuItem item(Button button) {
        MenuItem item=new MenuItem();
        item.setId(button.getId());
        item.textProperty().bind(button.textProperty());
        item.disableProperty().bind(button.disabledProperty());
        item.setOnAction(event -> { if(!item.isDisable() && !button.isDisabled()) button.fire(); });
        return item;
    }
    static MenuButton menu(String text,String id,Button... buttons) {
        var menu=new MenuButton(text); menu.setId(id);
        for(var button:buttons) menu.getItems().add(item(button));
        return menu;
    }
    static MenuButton textMenu(ShortcutSettings shortcuts,Button... buttons) {
        var menu=menu("文本操作","sql-text-actions",buttons);
        var actions=new ShortcutAction[]{ShortcutAction.SQL_INDENT,ShortcutAction.SQL_OUTDENT,
                ShortcutAction.SQL_LINE_COMMENT,ShortcutAction.SQL_DUPLICATE_LINES,
                ShortcutAction.SQL_MOVE_LINES_UP,ShortcutAction.SQL_MOVE_LINES_DOWN};
        Runnable refresh=() -> {
            for(int i=0;i<actions.length;i++) {
                var item=menu.getItems().get(i); item.textProperty().unbind();
                item.setText(buttons[i].getText()+" ("+shortcuts.get(actions[i]).getDisplayText()+")");
            }
        };
        refresh.run(); menu.setOnShowing(event -> refresh.run());
        return menu;
    }
}
